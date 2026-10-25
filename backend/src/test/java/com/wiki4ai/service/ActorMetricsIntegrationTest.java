package com.wiki4ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wiki4ai.config.JwtAuthenticationFilter.ApiTokenAuthenticationDetails;
import com.wiki4ai.dto.ActorMetricsDTO;
import com.wiki4ai.dto.ActorMetricsResponseDTO;
import com.wiki4ai.dto.ProjectCreateDTO;
import com.wiki4ai.model.Actor;
import com.wiki4ai.model.Project;
import com.wiki4ai.model.Role;
import com.wiki4ai.model.User;
import com.wiki4ai.model.WorkItem;
import com.wiki4ai.model.WorkItemEvent;
import com.wiki4ai.repository.ActorRepository;
import com.wiki4ai.repository.ApiTokenRepository;
import com.wiki4ai.repository.ProjectActorRepository;
import com.wiki4ai.repository.ProjectPermissionRepository;
import com.wiki4ai.repository.ProjectRepository;
import com.wiki4ai.repository.UserRepository;
import com.wiki4ai.repository.WorkItemEventRepository;
import com.wiki4ai.repository.WorkItemRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.lang.reflect.Field;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the actor metrics of WIKI4AI-147.
 *
 * <h2>Why some of the data here is seeded instead of produced</h2>
 * A metric that is a duration cannot be asserted against events whose timestamps the
 * test does not control: the numbers then depend on how fast the machine ran. So the
 * arithmetic is tested against a timeline written with explicit timestamps, which
 * makes every expected number exact and every assertion independent of the clock —
 * the mistake that made {@code defaultLeaseIs45Minutes} pass at 22:19 and fail at
 * 23:44 is not repeated here on purpose.
 * <p>
 * Seeding is a native {@code INSERT} into {@code work_item_events}. That is not a way
 * around the append-only rule of WIKI4AI-145: that rule governs the application's
 * writes, and it is enforced on {@link WorkItemEventRepository} and on the entity's
 * JPA lifecycle, both of which this test does not use to write. A fixture placing
 * rows directly is the same kind of write migration V21 itself performs.
 * <p>
 * The tests that go through the real API assert counts exactly and durations against
 * the events they produced, which is the strongest statement available when the
 * timestamps are the machine's own.
 *
 * <h2>Rule 5b</h2>
 * {@link #cleanState()} in {@code @BeforeEach} AND {@code @AfterEach}, children first,
 * no {@code @DirtiesContext}. {@code work_item_events} is emptied natively because its
 * repository refuses to delete — see {@link WorkItemEventIntegrationTest} for the same
 * note and for why that is not a loophole.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@DisplayName("Actor metrics derived only from work_item_events (WIKI4AI-147)")
class ActorMetricsIntegrationTest {

    private static final String OWNER = "met_owner";
    private static final String STRANGER = "met_stranger";
    private static final String ADMIN = "met_admin";

    /**
     * Everything is measured against this instant so that no expected number depends
     * on when the test runs. Truncated to microseconds because that is what a
     * TIMESTAMP column stores; an expectation computed from nanoseconds would disagree
     * with the stored value.
     */
    private LocalDateTime base;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private WorkItemRepository workItemRepository;

    @Autowired
    private WorkItemEventRepository workItemEventRepository;

    @Autowired
    private WorkItemEventService workItemEventService;

    @Autowired
    private WorkItemService workItemService;

    @Autowired
    private WorkItemLeaseReaper leaseReaper;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private ProjectActorRepository projectActorRepository;

    @Autowired
    private ProjectPermissionRepository projectPermissionRepository;

    @Autowired
    private ApiTokenRepository apiTokenRepository;

    @Autowired
    private ActorRepository actorRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProjectService projectService;

    private Long agentActorId;
    private Long strangerActorId;
    private Long publicProjectId;
    private Long strangerProjectId;

    // ── Rule 5b: shared H2, cleaned before AND after every test, children first ─

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        cleanState();
        base = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS).minusHours(2);

        saveUser(OWNER, Role.USER);
        saveUser(STRANGER, Role.USER);
        saveUser(ADMIN, Role.ADMIN);

        agentActorId = saveActor("met-agent", OWNER).getId();
        strangerActorId = saveActor("met-stranger-agent", STRANGER).getId();

        publicProjectId = createProject("MET Public", Project.VISIBILITY_PUBLIC, OWNER);
        strangerProjectId = createProject("MET Stranger", Project.VISIBILITY_PUBLIC, STRANGER);

        authenticate(OWNER, agentActorId);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        cleanState();
    }

    /**
     * work_item_comments → work_item_events → work_items → project_actors → permissions
     * → api_tokens → actors → projects → users.
     * <p>
     * The {@code work_item_comments} line is new with WIKI4AI-150: this class now writes
     * a {@code result} comment to drive one item to {@code done} through the real API,
     * and rule 5b requires a class that writes a table to clean it. Native DELETE for
     * the same reason as everywhere else in this suite — the comment repository refuses
     * {@code deleteAll()} by design.
     */
    private void cleanState() {
        jdbcTemplate.execute("DELETE FROM work_item_comments");
        jdbcTemplate.execute("DELETE FROM work_item_events");
        workItemRepository.deleteAll();
        projectActorRepository.deleteAll();
        projectPermissionRepository.deleteAll();
        apiTokenRepository.deleteAll();
        actorRepository.deleteAll();
        projectRepository.deleteAll();
        for (String username : List.of(OWNER, STRANGER, ADMIN)) {
            userRepository.findByUsername(username).ifPresent(userRepository::delete);
        }
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private User saveUser(String username, Role role) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@test.local");
        user.setPassword("not-a-real-hash");
        user.setRole(role);
        return userRepository.save(user);
    }

    private Actor saveActor(String handle, String ownerUsername) {
        Actor actor = new Actor();
        actor.setHandle(handle);
        actor.setKind(Actor.KIND_AGENT);
        actor.setOwnerUserId(userIdOf(ownerUsername));
        return actorRepository.save(actor);
    }

    private Long createProject(String name, String visibility, String username) {
        return projectService.createProject(
                ProjectCreateDTO.builder().name(name).visibility(visibility).build(), username).getId();
    }

    private Long userIdOf(String username) {
        return userRepository.findByUsername(username).orElseThrow().getId();
    }

    private void authenticate(String username, Long actorId) {
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                username, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        authentication.setDetails(new ApiTokenAuthenticationDetails(1L, userIdOf(username), actorId,
                List.of("read", "write")));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private Long createItem(String title, Long projectId) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("projectId", projectId);
        body.put("title", title);
        MvcResult result = mockMvc.perform(post("/api/v1/work-items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private void putStatus(Long id, String status) throws Exception {
        mockMvc.perform(put("/api/v1/work-items/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"" + status + "\"}"))
                .andExpect(status().isOk());
    }

    private String claim(Long id) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/work-items/" + id + "/claim"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("claimToken").asText();
    }

    /**
     * Append the {@code result} comment WIKI4AI-150 makes a precondition of {@code done}.
     * A comment leaves no {@code work_item_events} row, so the metrics this class counts
     * are untouched by it.
     */
    private void postResultComment(Long id) throws Exception {
        mockMvc.perform(post("/api/v1/work-items/" + id + "/comments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"result\",\"body\":\"implemented on main\","
                                + "\"payload\":{\"where\":\"main\",\"next\":\"release\",\"artifacts\":[\"commit abc123\"]}}"))
                .andExpect(status().isCreated());
    }

    private Long claimAndAge(Long id) throws Exception {
        putStatus(id, WorkItem.STATUS_READY);
        claim(id);
        WorkItem item = workItemRepository.findById(id).orElseThrow();
        item.setLeaseExpiresAt(LocalDateTime.now().minusMinutes(1));
        workItemRepository.save(item);
        return id;
    }

    /**
     * A work item whose whole history is a fixture: the row is created through the
     * API so it is a real item, its one real event is removed, and the timeline that
     * the metrics read is written with timestamps this test chose.
     */
    private Long createItemWithSeededTimeline(String title) throws Exception {
        Long id = createItem(title, publicProjectId);
        jdbcTemplate.update("DELETE FROM work_item_events WHERE work_item_id = ?", id);
        return id;
    }

    private void seed(Long itemId, Long actorId, String event, String fromStatus, String toStatus,
                      LocalDateTime at, String payload) {
        jdbcTemplate.update("INSERT INTO work_item_events (work_item_id, actor_id, event, from_status, "
                        + "to_status, payload, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                itemId, actorId, event, fromStatus, toStatus, payload, Timestamp.valueOf(at));
    }

    /**
     * The reference timeline. Six hundred seconds of one actor's work, every boundary
     * an exact multiple of 100 microseconds so that every derived duration is exact.
     *
     * <pre>
     *   +0.0000  created        -> backlog
     *  +60.0004  backlog        -> ready
     * +100.0008  ready          -> claimed
     * +200.0012  lease_extended (same state: the claimed period continues)
     * +300.0016  claimed        -> running
     * +420.0020  running        -> review
     * +540.0024  review         -> done
     * </pre>
     * The window closes at +600.0000, so {@code done} is open for 59.9976 s.
     */
    private void seedReferenceTimeline(Long itemId, Long actorId) {
        seed(itemId, actorId, WorkItemEvent.EVENT_CREATED, null, WorkItem.STATUS_BACKLOG, base, null);
        seed(itemId, actorId, WorkItemEvent.EVENT_STATUS_CHANGED, WorkItem.STATUS_BACKLOG,
                WorkItem.STATUS_READY, base.plusSeconds(60).plusNanos(400_000), null);
        seed(itemId, actorId, WorkItemEvent.EVENT_CLAIMED, WorkItem.STATUS_READY,
                WorkItem.STATUS_CLAIMED, base.plusSeconds(100).plusNanos(800_000), null);
        seed(itemId, actorId, WorkItemEvent.EVENT_LEASE_EXTENDED, WorkItem.STATUS_CLAIMED,
                WorkItem.STATUS_CLAIMED, base.plusSeconds(200).plusNanos(1_200_000), null);
        seed(itemId, actorId, WorkItemEvent.EVENT_STATUS_CHANGED, WorkItem.STATUS_CLAIMED,
                WorkItem.STATUS_RUNNING, base.plusSeconds(300).plusNanos(1_600_000), null);
        seed(itemId, actorId, WorkItemEvent.EVENT_STATUS_CHANGED, WorkItem.STATUS_RUNNING,
                WorkItem.STATUS_REVIEW, base.plusSeconds(420).plusNanos(2_000_000), null);
        seed(itemId, actorId, WorkItemEvent.EVENT_STATUS_CHANGED, WorkItem.STATUS_REVIEW,
                WorkItem.STATUS_DONE, base.plusSeconds(540).plusNanos(2_400_000), null);
    }

    private String windowQuery() {
        // The window is explicit: an open period is closed at its end, so an explicit
        // end is what makes the last duration exact instead of "whatever the clock said".
        return "?from=" + base.minusMinutes(10) + "&to=" + base.plusSeconds(600);
    }

    private JsonNode metricsJson(String query) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/metrics/actors" + query)).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private ActorMetricsResponseDTO metrics(String query) throws Exception {
        return objectMapper.readValue(metricsJson(query).toString(), ActorMetricsResponseDTO.class);
    }

    private ActorMetricsDTO actorOf(JsonNode response, Long actorId) {
        for (JsonNode node : response.get("actors")) {
            if (node.get("actorId").asLong() == actorId) {
                return objectMapper.convertValue(node, ActorMetricsDTO.class);
            }
        }
        throw new AssertionError("actor " + actorId + " is not in the response: " + response);
    }

    private static double seconds(long whole, long micros) {
        return whole + micros / 1_000_000.0;
    }

    // ── AC 147: the arithmetic ────────────────────────────────────────────────

    @Nested
    @DisplayName("AC 147: cycle time, time in a state and the average per completion")
    class DerivedArithmetic {

        @Test
        @DisplayName("cycle time created -> done is measured from the events, and it is not a whole number")
        void cycleTimeIsDerivedAndNotAnInteger() throws Exception {
            Long id = createItemWithSeededTimeline("Cycle item");
            seedReferenceTimeline(id, agentActorId);

            ActorMetricsDTO metrics = actorOf(metricsJson(windowQuery()), agentActorId);

            assertThat(metrics.getCycleCount()).isEqualTo(1);
            assertThat(metrics.getTotalCycleSeconds())
                    .as("540.0024 s between the 'created' event and the one that moved the item to done")
                    .isCloseTo(seconds(540, 2400), within(0.000001));
            assertThat(metrics.getAverageCycleSeconds())
                    .as("one completion, so the average per completion is that completion")
                    .isCloseTo(seconds(540, 2400), within(0.000001));
            assertThat(metrics.getAverageCycleSeconds() % 1.0)
                    .as("the story asks for a number that is not an integer: the trail stores "
                            + "microseconds and a metric rounded to whole seconds would report a "
                            + "coarser measurement than the data supports")
                    .isNotZero();
        }

        @Test
        @DisplayName("time in each state is the gap to the next event, and it is not a whole number")
        void timeInStatusIsDerivedAndNotAnInteger() throws Exception {
            Long id = createItemWithSeededTimeline("Status time item");
            seedReferenceTimeline(id, agentActorId);

            Map<String, Double> statusSeconds = actorOf(metricsJson(windowQuery()), agentActorId)
                    .getStatusSeconds();

            assertThat(statusSeconds)
                    .containsOnlyKeys(WorkItem.STATUS_BACKLOG, WorkItem.STATUS_READY, WorkItem.STATUS_CLAIMED,
                            WorkItem.STATUS_RUNNING, WorkItem.STATUS_REVIEW, WorkItem.STATUS_DONE);
            assertThat(statusSeconds.get(WorkItem.STATUS_BACKLOG)).isCloseTo(seconds(60, 400), within(0.000001));
            assertThat(statusSeconds.get(WorkItem.STATUS_READY)).isCloseTo(seconds(40, 400), within(0.000001));
            assertThat(statusSeconds.get(WorkItem.STATUS_CLAIMED))
                    .as("the claimed period spans the lease_extended event: a renewal does not "
                            + "change the state, so it does not end the time spent in it")
                    .isCloseTo(seconds(200, 800), within(0.000001));
            assertThat(statusSeconds.get(WorkItem.STATUS_RUNNING)).isCloseTo(seconds(120, 400), within(0.000001));
            assertThat(statusSeconds.get(WorkItem.STATUS_REVIEW)).isCloseTo(seconds(120, 400), within(0.000001));
            assertThat(statusSeconds.get(WorkItem.STATUS_DONE))
                    .as("the last state has no closing event; it is measured to the end of the window")
                    .isCloseTo(seconds(59, 997_600), within(0.000001));

            assertThat(statusSeconds.values())
                    .anySatisfy(value -> assertThat(value % 1.0).isNotZero());
        }

        @Test
        @DisplayName("the status durations add up to exactly the window the item lived through")
        void statusDurationsAreConsistent() throws Exception {
            Long id = createItemWithSeededTimeline("Consistency item");
            seedReferenceTimeline(id, agentActorId);

            ActorMetricsDTO metrics = actorOf(metricsJson(windowQuery()), agentActorId);

            assertThat(metrics.getTotalStatusSeconds())
                    .as("600 s from the first event to the end of the window, with no gap and no overlap")
                    .isCloseTo(600.0, within(0.000001));
        }

        @Test
        @DisplayName("the average per completion is a mean over the completions, not a sum")
        void averagePerCompletionIsAMean() throws Exception {
            Long first = createItemWithSeededTimeline("First done");
            seedReferenceTimeline(first, agentActorId);
            Long second = createItemWithSeededTimeline("Second done");
            seed(second, agentActorId, WorkItemEvent.EVENT_CREATED, null, WorkItem.STATUS_BACKLOG, base, null);
            seed(second, agentActorId, WorkItemEvent.EVENT_STATUS_CHANGED, WorkItem.STATUS_BACKLOG,
                    WorkItem.STATUS_DONE, base.plusSeconds(120).plusNanos(800_000), null);

            ActorMetricsDTO metrics = actorOf(metricsJson(windowQuery()), agentActorId);

            assertThat(metrics.getCycleCount()).isEqualTo(2);
            assertThat(metrics.getTotalCycleSeconds())
                    .isCloseTo(seconds(540, 2400) + seconds(120, 800), within(0.000001));
            assertThat(metrics.getAverageCycleSeconds())
                    .as("(540.0024 + 120.0008) / 2 — the number the story asks for: average per "
                            + "completion per actor")
                    .isCloseTo((seconds(540, 2400) + seconds(120, 800)) / 2.0, within(0.000001));
            assertThat(metrics.getAverageCycleSeconds() % 1.0).isNotZero();
        }

        @Test
        @DisplayName("an actor that completed nothing has a zero average, not a division by zero")
        void noCompletionsMeansZeroAverage() throws Exception {
            Long id = createItemWithSeededTimeline("Never finished");
            seed(id, agentActorId, WorkItemEvent.EVENT_CREATED, null, WorkItem.STATUS_BACKLOG, base, null);
            seed(id, agentActorId, WorkItemEvent.EVENT_STATUS_CHANGED, WorkItem.STATUS_BACKLOG,
                    WorkItem.STATUS_READY, base.plusSeconds(30), null);

            ActorMetricsDTO metrics = actorOf(metricsJson(windowQuery()), agentActorId);

            assertThat(metrics.getCycleCount()).isZero();
            assertThat(metrics.getAverageCycleSeconds()).isZero();
            assertThat(metrics.getCompletedCount()).isZero();
        }
    }

    // ── AC 147: the counts ────────────────────────────────────────────────────

    @Nested
    @DisplayName("AC 147: blocked, lease_expired and completions are counted per actor")
    class DerivedCounts {

        @Test
        @DisplayName("the blocked count is the number of transitions into blocked, per actor")
        void blockedCountIsCountedPerActor() throws Exception {
            Long id = createItem("Blocked item", publicProjectId);
            putStatus(id, WorkItem.STATUS_BLOCKED);
            putStatus(id, WorkItem.STATUS_READY);
            putStatus(id, WorkItem.STATUS_BLOCKED);

            authenticate(ADMIN, null);
            JsonNode response = metricsJson("");

            assertThat(actorOf(response, agentActorId).getBlockedCount())
                    .as("two transitions into blocked by this actor; the transition out of blocked "
                            + "is not a blocked event")
                    .isEqualTo(2);
            assertThat(response.get("summary").get("blockedCount").asLong()).isEqualTo(2);
        }

        @Test
        @DisplayName("lease_expired is counted per actor, from the reaper's own events")
        void leaseExpiredIsCountedPerActor() throws Exception {
            claimAndAge(createItem("Lost lease one", publicProjectId));
            claimAndAge(createItem("Lost lease two", publicProjectId));

            authenticate(STRANGER, strangerActorId);
            claimAndAge(createItem("Their lost lease", strangerProjectId));

            assertThat(leaseReaper.releaseExpired()).isEqualTo(3);

            authenticate(ADMIN, null);
            JsonNode response = metricsJson("");

            assertThat(actorOf(response, agentActorId).getLeaseExpiredCount())
                    .as("this actor held two leases that the reaper took back")
                    .isEqualTo(2);
            assertThat(actorOf(response, strangerActorId).getLeaseExpiredCount())
                    .as("and one belongs to a different actor, counted for that actor alone")
                    .isEqualTo(1);
            assertThat(response.get("summary").get("leaseExpiredCount").asLong()).isEqualTo(3);
        }

        @Test
        @DisplayName("a full lifecycle through the real API produces exactly one completion and one cycle")
        void realLifecycleCountsAreExact() throws Exception {
            Long id = createItem("Real lifecycle", publicProjectId);
            putStatus(id, WorkItem.STATUS_READY);
            claim(id);
            putStatus(id, WorkItem.STATUS_RUNNING);
            putStatus(id, WorkItem.STATUS_REVIEW);
            // WIKI4AI-150: 'review' -> 'done' now requires a 'result' comment. The
            // assertions are unchanged — a comment is not an event and not a completion.
            postResultComment(id);
            putStatus(id, WorkItem.STATUS_DONE);

            ActorMetricsDTO metrics = actorOf(metricsJson(""), agentActorId);

            assertThat(metrics.getCreatedCount()).isEqualTo(1);
            assertThat(metrics.getClaimedCount()).isEqualTo(1);
            assertThat(metrics.getCompletedCount()).isEqualTo(1);
            assertThat(metrics.getCycleCount()).isEqualTo(1);
            assertThat(metrics.getLeaseExpiredCount()).isZero();
            assertThat(metrics.getStatusSeconds())
                    .containsOnlyKeys(WorkItem.STATUS_BACKLOG, WorkItem.STATUS_READY, WorkItem.STATUS_CLAIMED,
                            WorkItem.STATUS_RUNNING, WorkItem.STATUS_REVIEW, WorkItem.STATUS_DONE);

            // The strongest statement available when the timestamps are the machine's
            // own: the reported cycle time is exactly the gap between the two events
            // the trail holds, not an approximation of it.
            List<WorkItemEvent> events = workItemEventRepository
                    .findByWorkItemIdOrderByCreatedAtAscIdAsc(id);
            LocalDateTime created = events.stream()
                    .filter(event -> WorkItemEvent.EVENT_CREATED.equals(event.getEvent()))
                    .map(WorkItemEvent::getCreatedAt).findFirst().orElseThrow();
            LocalDateTime done = events.stream()
                    .filter(event -> WorkItem.STATUS_DONE.equals(event.getToStatus()))
                    .map(WorkItemEvent::getCreatedAt).findFirst().orElseThrow();

            assertThat(metrics.getTotalCycleSeconds())
                    .isCloseTo(java.time.Duration.between(created, done).toNanos() / 1_000_000_000.0,
                            within(0.000001));
            assertThat(metrics.getTotalCycleSeconds()).isGreaterThan(0.0);
        }

        @Test
        @DisplayName("an actor with no events does not appear at all")
        void actorWithoutEventsIsAbsent() throws Exception {
            Long id = createItem("Only mine", publicProjectId);
            putStatus(id, WorkItem.STATUS_READY);

            authenticate(ADMIN, null);
            JsonNode response = metricsJson("");

            assertThat(response.get("actors"))
                    .as("there is nothing to derive for an actor that did nothing")
                    .extracting(node -> node.get("actorId").asLong())
                    .doesNotContain(strangerActorId);
            assertThat(response.get("summary").get("actorCount").asLong()).isEqualTo(1);
        }
    }

    // ── AC 147: derived from events and from nothing else ─────────────────────

    @Nested
    @DisplayName("AC 147: the numbers come from work_item_events and from no other table")
    class DerivedFromEventsOnly {

        @Test
        @DisplayName("the service has no WorkItemRepository: it cannot read the current state")
        void serviceHasNoWorkItemRepositoryField() {
            List<String> forbidden = new ArrayList<>();
            for (Field field : ActorMetricsService.class.getDeclaredFields()) {
                String typeName = field.getType().getName();
                if (typeName.contains("WorkItemRepository") || typeName.contains("ActorRepository")
                        || typeName.contains("ProjectRepository")) {
                    forbidden.add(field.getName() + " : " + typeName);
                }
            }
            assertThat(forbidden)
                    .as("AC 147: 'ziadna dalsia tabulka' — the only repository this class may hold "
                            + "is the event repository")
                    .isEmpty();
            assertThat(Arrays.stream(ActorMetricsService.class.getDeclaredFields())
                    .map(Field::getType))
                    .contains(WorkItemEventRepository.class);
        }

        @Test
        @DisplayName("editing a work_items row behind the trail's back changes no metric")
        void directRowEditChangesNoMetric() throws Exception {
            Long id = createItemWithSeededTimeline("Immutable metrics");
            seedReferenceTimeline(id, agentActorId);

            ActorMetricsResponseDTO before = metrics(windowQuery());

            jdbcTemplate.update("UPDATE work_items SET status = 'cancelled', title = 'rewritten by hand', "
                    + "updated_at = ? WHERE id = ?", Timestamp.valueOf(base.plusSeconds(900)), id);

            ActorMetricsResponseDTO after = metrics(windowQuery());

            assertThat(after)
                    .as("the item now says 'cancelled' while the trail says it ended in 'done'. The "
                            + "metrics report the trail, because that is the only thing they read — "
                            + "which is what makes them falsifiable at all")
                    .isEqualTo(before);
            assertThat(workItemRepository.findById(id).orElseThrow().getStatus())
                    .as("the row really was changed, so the equality above is not an accident")
                    .isEqualTo(WorkItem.STATUS_CANCELLED);
        }

        @Test
        @DisplayName("deleting the work item row leaves the metrics untouched")
        void deletingTheItemRowChangesNoMetric() throws Exception {
            Long id = createItemWithSeededTimeline("Item without a row");
            seedReferenceTimeline(id, agentActorId);

            ActorMetricsResponseDTO before = metrics(windowQuery());

            jdbcTemplate.update("DELETE FROM work_items WHERE id = ?", id);

            assertThat(metrics(windowQuery()))
                    .as("the events outlived the row here because the test schema carries no foreign "
                            + "key; on PostgreSQL V21 declares ON DELETE CASCADE and they would not. "
                            + "What this proves is only that no metric reads work_items.")
                    .isEqualTo(before);
            assertThat(workItemRepository.existsById(id)).isFalse();
        }

        @Test
        @DisplayName("backfilled events are counted, and reported as backfilled")
        void backfilledEventsAreVisibleSeparately() throws Exception {
            Long id = createItemWithSeededTimeline("Pre-existing item");
            seed(id, agentActorId, WorkItemEvent.EVENT_CREATED, null, WorkItem.STATUS_BACKLOG, base,
                    "{\"backfilled\":true}");
            seed(id, agentActorId, WorkItemEvent.EVENT_STATUS_CHANGED, WorkItem.STATUS_BACKLOG,
                    WorkItem.STATUS_DONE, base.plusSeconds(100).plusNanos(500_000), "{\"backfilled\":true}");

            authenticate(ADMIN, null);
            JsonNode response = metricsJson(windowQuery());

            assertThat(actorOf(response, agentActorId).getCompletedCount())
                    .as("the state change really happened; excluding it would report an item that is "
                            + "demonstrably done as never worked on")
                    .isEqualTo(1);
            assertThat(response.get("summary").get("backfilledEventCount").asLong())
                    .as("and the reader can see that both of these events were reconstructed from the "
                            + "row's own timestamps rather than observed")
                    .isEqualTo(2);
            assertThat(response.get("summary").get("claimedCount").asLong())
                    .as("nothing about a claim is backfilled, so this count is always observed history")
                    .isZero();
        }
    }

    // ── AC 147: the window and its errors ─────────────────────────────────────

    @Nested
    @DisplayName("AC 147: the time window")
    class Window {

        @Test
        @DisplayName("an unparseable 'from' is a 400, not a 500")
        void invalidFromIs400() throws Exception {
            mockMvc.perform(get("/api/v1/metrics/actors").param("from", "yesterday"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("Bad Request"))
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("from")));
        }

        @Test
        @DisplayName("an impossible date is a 400")
        void impossibleDateIs400() throws Exception {
            mockMvc.perform(get("/api/v1/metrics/actors").param("from", "2026-13-45"))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(get("/api/v1/metrics/actors").param("to", "2026-10-10T99:00:00"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("a window that ends before it starts is a 400")
        void invertedWindowIs400() throws Exception {
            mockMvc.perform(get("/api/v1/metrics/actors")
                            .param("from", "2026-10-09T00:00:00")
                            .param("to", "2026-10-01T00:00:00"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("before")));
        }

        @Test
        @DisplayName("a bare date is accepted and means the whole day")
        void bareDateIsAccepted() throws Exception {
            Long id = createItemWithSeededTimeline("Bare window");
            seed(id, agentActorId, WorkItemEvent.EVENT_CREATED, null, WorkItem.STATUS_BACKLOG, base, null);

            mockMvc.perform(get("/api/v1/metrics/actors")
                            .param("from", base.toLocalDate().toString())
                            .param("to", base.toLocalDate().toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.actors.length()").value(1))
                    .andExpect(jsonPath("$.summary.eventCount").value(1));
        }

        @Test
        @DisplayName("no window means everything, and the window is echoed back")
        void defaultWindowIsTheWholeTrail() throws Exception {
            Long id = createItemWithSeededTimeline("No window");
            seed(id, agentActorId, WorkItemEvent.EVENT_CREATED, null, WorkItem.STATUS_BACKLOG, base, null);

            JsonNode response = metricsJson("");

            assertThat(response.get("from").asText()).startsWith("1970-01-01");
            assertThat(response.get("to").asText()).isNotBlank();
            assertThat(response.get("summary").get("eventCount").asLong()).isEqualTo(1);
        }

        @Test
        @DisplayName("events outside the window are not counted")
        void windowExcludesEventsOutsideIt() throws Exception {
            Long id = createItemWithSeededTimeline("Window edges");
            seed(id, agentActorId, WorkItemEvent.EVENT_CREATED, null, WorkItem.STATUS_BACKLOG, base, null);
            seed(id, agentActorId, WorkItemEvent.EVENT_STATUS_CHANGED, WorkItem.STATUS_BACKLOG,
                    WorkItem.STATUS_READY, base.plusSeconds(60), null);

            authenticate(ADMIN, null);
            JsonNode narrow = metricsJson("?from=" + base.minusMinutes(1) + "&to=" + base.plusSeconds(10));

            assertThat(narrow.get("summary").get("eventCount").asLong())
                    .as("only the first event falls inside a window that ends 10 s after it")
                    .isEqualTo(1);
            assertThat(narrow.get("actors").get(0).get("createdCount").asLong()).isEqualTo(1);
        }
    }

    // ── Who may see whose numbers ─────────────────────────────────────────────

    @Nested
    @DisplayName("AC 147: who sees what")
    class Visibility {

        @Test
        @DisplayName("an authenticated caller sees their own actor's metrics")
        void ownMetricsAreVisible() throws Exception {
            authenticate(STRANGER, strangerActorId);
            claimAndAge(createItem("Theirs", strangerProjectId));
            leaseReaper.releaseExpired();

            JsonNode response = metricsJson("");

            assertThat(response.get("scope").asText()).isEqualTo(ActorMetricsResponseDTO.SCOPE_SELF);
            assertThat(response.get("actors"))
                    .extracting(node -> node.get("actorId").asLong())
                    .containsExactly(strangerActorId);
            assertThat(response.get("actors").get(0).get("leaseExpiredCount").asLong()).isEqualTo(1);
            assertThat(response.get("summary").get("actorCount").asLong()).isEqualTo(1);
        }

        @Test
        @DisplayName("a non-ADMIN caller does not see another actor's numbers")
        void foreignActorMetricsAreNotVisible() throws Exception {
            claimAndAge(createItem("Mine", publicProjectId));
            authenticate(STRANGER, strangerActorId);
            claimAndAge(createItem("Theirs", strangerProjectId));
            leaseReaper.releaseExpired();

            authenticate(OWNER, agentActorId);
            JsonNode response = metricsJson("");

            assertThat(response.get("actors"))
                    .extracting(node -> node.get("actorId").asLong())
                    .doesNotContain(strangerActorId);
            assertThat(response.get("summary").get("leaseExpiredCount").asLong())
                    .as("the summary is the sum over the visible actors only, so it cannot leak the "
                            + "hidden one either")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("an ADMIN user sees every actor in the window")
        void adminSeesAll() throws Exception {
            claimAndAge(createItem("Mine", publicProjectId));
            authenticate(STRANGER, strangerActorId);
            claimAndAge(createItem("Theirs", strangerProjectId));
            leaseReaper.releaseExpired();

            authenticate(ADMIN, null);
            JsonNode response = metricsJson("");

            assertThat(response.get("scope").asText()).isEqualTo(ActorMetricsResponseDTO.SCOPE_ALL);
            assertThat(response.get("actors"))
                    .extracting(node -> node.get("actorId").asLong())
                    .containsExactlyInAnyOrder(agentActorId, strangerActorId);
            assertThat(response.get("summary").get("leaseExpiredCount").asLong()).isEqualTo(2);
        }

        @Test
        @DisplayName("a credential that names no actor is told so, with a 403")
        void callerWithoutActorIs403() throws Exception {
            claimAndAge(createItem("Somebody's work", publicProjectId));

            authenticate(STRANGER, null);
            mockMvc.perform(get("/api/v1/metrics/actors"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("Forbidden"));
        }

        @Test
        @DisplayName("an unauthenticated caller is 403, not a list of everybody's numbers")
        void anonymousCallerIs403() throws Exception {
            claimAndAge(createItem("Somebody's work", publicProjectId));
            SecurityContextHolder.clearContext();

            mockMvc.perform(get("/api/v1/metrics/actors"))
                    .andExpect(status().isForbidden());
        }
    }

    // ── WIKI4AI-173: a project that does not exist is 404, not 200 with zeros ──

    /**
     * The bug was measured live: {@code GET /api/v1/metrics/actors?projectId=999999}
     * answered <b>200 with empty metrics</b>. Metrics are the only evidence an agent
     * acts on, and those two answers assert different facts — "this project exists and
     * nobody did anything" versus "your question has no referent". A caller holding a
     * wrong project id got its wrong assumption confirmed.
     * <p>
     * These tests hold the difference, so a regression that turns the 404 back into a
     * quiet 200 is caught by the pair that asks about a real empty project and a
     * nonexistent one and requires them to be answered differently.
     */
    @Nested
    @DisplayName("WIKI4AI-173: projectId narrows the window, and an unknown one is 404")
    class ProjectScopedMetrics {

        @Test
        @DisplayName("AC: an unknown projectId is 404 naming projectId — not 200 with zeros")
        void unknownProjectIs404() throws Exception {
            claimAndAge(createItem("Real work in a real project", publicProjectId));
            leaseReaper.releaseExpired();

            MvcResult result = mockMvc.perform(get("/api/v1/metrics/actors")
                            .param("projectId", "999999"))
                    .andReturn();

            assertThat(result.getResponse().getStatus())
                    .as("the whole defect: this was 200. 404 is the answer that does not "
                            + "confirm a caller's wrong assumption about the project")
                    .isEqualTo(404);
            assertThat(result.getResponse().getStatus())
                    .as("and it is 404, not the 403 a caller without an actor gets, and not 200")
                    .isNotIn(java.util.List.of(200, 403));

            JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
            assertThat(body.get("error").asText()).isEqualTo("Not Found");
            assertThat(body.get("message").asText())
                    .as("AC: the message names projectId")
                    .contains("999999");
            assertThat(body.has("actors"))
                    .as("a 404 carries no metrics — there is nothing to be empty about")
                    .isFalse();
        }

        @Test
        @DisplayName("an existing project with no events is 200 with empty actors — the answer 404 must not replace")
        void emptyExistingProjectIsStill200WithEmptyActors() throws Exception {
            // A project that really exists and really has nothing in it. This is the
            // other half of the defect: 200-with-zeros is a CORRECT answer here and a
            // WRONG one for projectId=999999, so both have to stay reachable.
            Long emptyProjectId = createProject("MET Empty", Project.VISIBILITY_PUBLIC, OWNER);

            MvcResult found = mockMvc.perform(get("/api/v1/metrics/actors")
                            .param("projectId", String.valueOf(emptyProjectId)))
                    .andReturn();
            assertThat(found.getResponse().getStatus()).isEqualTo(200);

            JsonNode body = objectMapper.readTree(found.getResponse().getContentAsString());
            assertThat(body.get("actors")).isEmpty();
            assertThat(body.get("summary").get("actorCount").asLong()).isZero();
            assertThat(body.get("projectId").asLong())
                    .as("the response says which project these (empty) numbers are for")
                    .isEqualTo(emptyProjectId);
            assertThat(body.get("scope").asText()).isEqualTo(ActorMetricsResponseDTO.SCOPE_PROJECT);
        }

        @Test
        @DisplayName("the same request shape answers 404 for 999999 and 200 for a real empty project")
        void fourOhFourAndEmptyAreDistinguishable() throws Exception {
            Long emptyProjectId = createProject("MET Empty Again", Project.VISIBILITY_PUBLIC, OWNER);

            int missing = mockMvc.perform(get("/api/v1/metrics/actors")
                            .param("projectId", "999999"))
                    .andReturn().getResponse().getStatus();
            int existsButEmpty = mockMvc.perform(get("/api/v1/metrics/actors")
                            .param("projectId", String.valueOf(emptyProjectId)))
                    .andReturn().getResponse().getStatus();

            assertThat(missing).isEqualTo(404);
            assertThat(existsButEmpty).isEqualTo(200);
            assertThat(missing)
                    .as("'no such project' and 'nothing happened in this project' are two facts, "
                            + "and an agent decides on the difference")
                    .isNotEqualTo(existsButEmpty);
        }

        @Test
        @DisplayName("AC: someone else's private project is 404 through the existing visibility rule")
        void foreignPrivateProjectIs404() throws Exception {
            Long privateProjectId = createProject("MET Private", Project.VISIBILITY_PRIVATE, STRANGER);

            // OWNER is authenticated by setUp and is neither the owner nor an ADMIN.
            mockMvc.perform(get("/api/v1/metrics/actors")
                            .param("projectId", String.valueOf(privateProjectId)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("Not Found"));
        }

        @Test
        @DisplayName("a projectId that is not a number is 400 naming projectId")
        void nonNumericProjectIdIs400() throws Exception {
            mockMvc.perform(get("/api/v1/metrics/actors").param("projectId", "not-a-project"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("projectId")));
        }

        @Test
        @DisplayName("AC: a bad from/to is still 400 even when a projectId is present")
        void badWindowIsStill400WithProjectId() throws Exception {
            mockMvc.perform(get("/api/v1/metrics/actors")
                            .param("projectId", String.valueOf(publicProjectId))
                            .param("from", "yesterday"))
                    .andExpect(status().isBadRequest());

            mockMvc.perform(get("/api/v1/metrics/actors")
                            .param("projectId", String.valueOf(publicProjectId))
                            .param("from", base.minusDays(1).toString())
                            .param("to", base.minusDays(2).toString()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("'to' must not be before")));
        }

        @Test
        @DisplayName("AC: scope=all is still 200, and projectId is absent from the answer")
        void scopeAllIsStill200AndUnchanged() throws Exception {
            claimAndAge(createItem("Mine", publicProjectId));
            leaseReaper.releaseExpired();

            mockMvc.perform(get("/api/v1/metrics/actors").param("scope", "all"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.scope").value(ActorMetricsResponseDTO.SCOPE_SELF))
                    .andExpect(jsonPath("$.projectId").doesNotExist());
        }

        @Test
        @DisplayName("scope=project without a projectId is 400, not an instance-wide answer")
        void scopeProjectWithoutProjectIdIs400() throws Exception {
            mockMvc.perform(get("/api/v1/metrics/actors").param("scope", "project"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("projectId")));
        }

        @Test
        @DisplayName("an unknown scope is 400 rather than a silently different scope")
        void unknownScopeIs400() throws Exception {
            mockMvc.perform(get("/api/v1/metrics/actors").param("scope", "projecet"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("scope")));
        }

        @Test
        @DisplayName("AC: without projectId the answer is the WIKI4AI-147 answer, unchanged")
        void noProjectIdMeansNoNarrowing() throws Exception {
            claimAndAge(createItem("Mine", publicProjectId));
            authenticate(STRANGER, strangerActorId);
            claimAndAge(createItem("Theirs", strangerProjectId));
            leaseReaper.releaseExpired();

            authenticate(ADMIN, null);
            // The window is left open on purpose, exactly as in Visibility.adminSeesAll:
            // claimAndAge writes its events at the present moment, and windowQuery() ends
            // two hours ago. A window that excludes the events it is measuring would look
            // like "no actors" and would prove nothing.
            JsonNode response = metricsJson("");

            assertThat(response.get("scope").asText()).isEqualTo(ActorMetricsResponseDTO.SCOPE_ALL);
            assertThat(response.get("projectId").isNull())
                    .as("no narrowing was asked for, so no project is claimed — the field is "
                            + "there and empty, exactly as it was before WIKI4AI-173 existed")
                    .isTrue();
            assertThat(response.get("actors"))
                    .extracting(node -> node.get("actorId").asLong())
                    .containsExactlyInAnyOrder(agentActorId, strangerActorId);
        }

        @Test
        @DisplayName("a narrowed window counts only that project's events")
        void narrowedWindowCountsOnlyThatProject() throws Exception {
            // Two projects, two actors, two identical timelines. Only an ADMIN is
            // entitled to see both actors, so what separates the two answers here is
            // the narrowing and nothing else.
            Long mine = createItemWithSeededTimeline("Mine");
            seedReferenceTimeline(mine, agentActorId);
            // The second project belongs to STRANGER and only STRANGER may write there,
            // so the item is created under that credential before the ADMIN reads.
            authenticate(STRANGER, strangerActorId);
            Long theirs = createItem("Theirs", strangerProjectId);
            jdbcTemplate.update("DELETE FROM work_item_events WHERE work_item_id = ?", theirs);
            seedReferenceTimeline(theirs, strangerActorId);

            authenticate(ADMIN, null);

            JsonNode narrowed = metricsJson(windowQuery() + "&projectId=" + publicProjectId);
            assertThat(narrowed.get("scope").asText()).isEqualTo(ActorMetricsResponseDTO.SCOPE_PROJECT);
            assertThat(narrowed.get("projectId").asLong()).isEqualTo(publicProjectId);
            assertThat(narrowed.get("actors"))
                    .extracting(node -> node.get("actorId").asLong())
                    .as("the other project's actor is not part of this project's numbers")
                    .containsExactly(agentActorId);
            assertThat(narrowed.get("summary").get("actorCount").asLong()).isEqualTo(1);
            assertThat(narrowed.get("summary").get("cycleCount").asLong())
                    .as("one timeline, so one cycle — the second project's cycle is not in this sum")
                    .isEqualTo(1);

            JsonNode whole = metricsJson(windowQuery());
            assertThat(whole.get("actors"))
                    .extracting(node -> node.get("actorId").asLong())
                    .containsExactlyInAnyOrder(agentActorId, strangerActorId);
            assertThat(whole.get("summary").get("cycleCount").asLong())
                    .as("the unnarrowed window still sees both, so the narrowing above removed "
                            + "a project rather than hiding an actor")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("the service still holds no repository but the event one")
        void narrowingAddsNoRepositoryToTheService() {
            // WIKI4AI-147's guard, restated for the new collaborators: the project rule is
            // reached through ProjectService, and the item ids that narrow the trail come
            // from WorkItemService. Neither is a table this class reads numbers from, and
            // the guard's own wording still holds: the only repository in this class is the
            // event one.
            assertThat(java.util.Arrays.stream(ActorMetricsService.class.getDeclaredFields())
                    .map(field -> field.getType().getName())
                    .filter(name -> name.contains("WorkItemRepository")
                            || name.contains("ProjectRepository")
                            || name.contains("ActorRepository")))
                    .as("WIKI4AI-173 must not smuggle a work_items or project table into the "
                            + "class whose whole claim is that it derives everything from events")
                    .isEmpty();
            assertThat(java.util.Arrays.stream(ActorMetricsService.class.getDeclaredFields())
                    .map(Field::getType))
                    .contains(WorkItemEventRepository.class);
        }
    }
}
