package com.wiki4ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wiki4ai.config.JwtAuthenticationFilter.ApiTokenAuthenticationDetails;
import com.wiki4ai.dto.ProjectCreateDTO;
import com.wiki4ai.dto.WorkItemEventDTO;
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
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the append-only audit trail (WIKI4AI-145) and its timeline
 * endpoint (WIKI4AI-146), against the real H2 database, the real
 * {@link WorkItemService}, the real {@link WorkItemEventService}, the real
 * {@link WorkItemEventsController} and the real reaper.
 *
 * <h2>Rule 5b</h2>
 * {@link #cleanState()} runs in {@code @BeforeEach} AND {@code @AfterEach}, children
 * before parents. No {@code @DirtiesContext}: the contexts of this suite are cached
 * and share one named in-memory H2, so the state a test made has to be removed by
 * that test.
 * <p>
 * {@code work_item_events} is emptied with a native statement through
 * {@link JdbcTemplate}, and that is the one deliberate deviation from the literal
 * cleanup recipe. The recipe says {@code workItemRepository.deleteAll()} and lets the
 * schema's {@code ON DELETE CASCADE} take the events with it — which is exactly what
 * happens on PostgreSQL, and is why the recipe is right there. It does not happen
 * here: SPEC rule 11 keeps associations out of the entities, so
 * {@code WorkItemEvent.workItemId} is a plain {@code Long} column and the
 * Hibernate-generated test schema has no foreign key to cascade. Without the native
 * statement the events of every test in this class — and of every other test class
 * that creates work items — would pile up in a table the next test reads.
 * <p>
 * A native {@code DELETE} is not a way around the append-only rule. That rule governs
 * the application's writes, which all go through {@link WorkItemEventRepository} and
 * the entity's JPA lifecycle; the enforcement is asserted in
 * {@link AppendOnlyIsEnforced}, where the JPA paths are the ones being tried. A test
 * harness removing rows directly is the same kind of write the database itself
 * performs through its cascade.
 *
 * <h2>Security</h2>
 * {@code addFilters = false}: the authentication under test is the one this class
 * installs, in the shape {@code JwtAuthenticationFilter} produces for a {@code w4a_}
 * token. Attribution therefore travels from the credential, and no request in this
 * file names an actor in a body or a query string (SPEC rule 10).
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@DisplayName("Work item event trail (WIKI4AI-145) and its timeline (WIKI4AI-146)")
class WorkItemEventIntegrationTest {

    private static final String OWNER = "wie_owner";
    private static final String STRANGER = "wie_stranger";
    private static final String ADMIN = "wie_admin";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WorkItemRepository workItemRepository;

    @Autowired
    private WorkItemEventRepository workItemEventRepository;

    @Autowired
    private WorkItemEventService workItemEventService;

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

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager entityManager;

    private Long agentActorId;
    private Long strangerActorId;
    private Long publicProjectId;
    private Long strangerProjectId;
    private Long privateProjectId;

    // ── Rule 5b: shared H2, cleaned before AND after every test, children first ─

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        cleanState();

        saveUser(OWNER, Role.USER);
        saveUser(STRANGER, Role.USER);
        saveUser(ADMIN, Role.ADMIN);

        agentActorId = saveActor("wie-agent", OWNER).getId();
        strangerActorId = saveActor("wie-stranger-agent", STRANGER).getId();

        publicProjectId = createProject("WIE Public", Project.VISIBILITY_PUBLIC, OWNER);
        strangerProjectId = createProject("WIE Stranger", Project.VISIBILITY_PUBLIC, STRANGER);
        privateProjectId = createProject("WIE Private", Project.VISIBILITY_PRIVATE, STRANGER);

        authenticate(OWNER, agentActorId);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        cleanState();
    }

    /**
     * work_item_comments → work_item_events → work_items → project_actors → permissions
     * → api_tokens → actors → projects → users. Children before parents, so no statement
     * can strand a row that still points at something being deleted.
     * <p>
     * The {@code work_item_comments} line is new with WIKI4AI-150: this class now writes
     * a {@code result} comment to reach {@code done}, and rule 5b requires a class that
     * writes a table to clean it. A native DELETE is the only statement available —
     * {@code WorkItemCommentRepository.deleteAll()} is refused by design (the immutability
     * of WIKI4AI-148), exactly as in {@code WorkItemCommentIntegrationTest}.
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

    private WorkItem reload(Long id) {
        return workItemRepository.findById(id).orElseThrow();
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

    private Long createItem() throws Exception {
        return createItem("WIE item", publicProjectId);
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
     * A comment writes no {@code work_item_events} row — the trail records states, not
     * comments — so the six events this class counts are still exactly six.
     */
    private void postResultComment(Long id) throws Exception {
        mockMvc.perform(post("/api/v1/work-items/" + id + "/comments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"result\",\"body\":\"implemented on main\","
                                + "\"payload\":{\"where\":\"main\",\"next\":\"release\",\"artifacts\":[\"commit abc123\"]}}"))
                .andExpect(status().isCreated());
    }

    /** ready → claimed, then a lease that has already expired: the reaper's input. */
    private Long claimAndAge(Long id) throws Exception {
        putStatus(id, WorkItem.STATUS_READY);
        claim(id);
        ageLease(id);
        return id;
    }

    private void ageLease(Long id) {
        WorkItem item = reload(id);
        item.setLeaseExpiresAt(LocalDateTime.now().minusMinutes(1));
        workItemRepository.save(item);
    }

    /**
     * Test fixture only: an item in a claim-free state that still carries part of a
     * claim triple — the shape WIKI4AI-170 left in databases written before the fix.
     * The service refuses to produce such a row now, so the fixture writes it
     * directly.
     */
    private void stampStaleClaim(Long id, String status, boolean withAssignee) {
        WorkItem item = reload(id);
        item.setStatus(status);
        item.setAssigneeActorId(withAssignee ? agentActorId : null);
        item.setClaimToken("f".repeat(64));
        item.setLeaseExpiresAt(withAssignee ? LocalDateTime.now().minusMinutes(1) : null);
        workItemRepository.save(item);
    }

    private List<WorkItemEventDTO> eventsOf(Long itemId) {
        return workItemEventService.listAllEvents(itemId);
    }

    private static List<String> namesOf(List<WorkItemEventDTO> events) {
        List<String> names = new ArrayList<>();
        events.forEach(event -> names.add(event.getEvent()));
        return names;
    }

    private JsonNode getJson(String path) throws Exception {
        MvcResult result = mockMvc.perform(get(path)).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /**
     * Write a field of a managed event directly. The entity has no setter, so this is
     * how a real bug would have to reach it — through the field, a listener or a bulk
     * merge — and it is the path the {@code @PreUpdate} guard exists to catch.
     */
    private static void tamperEventField(WorkItemEvent entity, String value) {
        try {
            Field field = WorkItemEvent.class.getDeclaredField("event");
            field.setAccessible(true);
            field.set(entity, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("the fixture could not reach the field to tamper with it", e);
        }
    }

    /** Run a block in its own transaction, the way the application would. */
    private void inTransaction(Runnable body) {
        new TransactionTemplate(transactionManager).execute(status -> {
            body.run();
            return null;
        });
    }

    /**
     * Assert that an operation was refused by the append-only guard. The assertion
     * walks to the root cause on purpose: Hibernate is allowed to wrap what the
     * entity's {@code @PreUpdate} threw, and the thing that matters is that the
     * refusal came from the guard and not from a constraint violation or a typo.
     */
    private static void assertRefusedAsAppendOnly(org.assertj.core.api.ThrowableAssert.ThrowingCallable body) {
        Throwable thrown = org.assertj.core.api.ThrowableAssert.catchThrowable(body);
        assertThat(thrown).as("the operation was not attempted at all").isNotNull();
        for (Throwable cursor = thrown; cursor != null; cursor = cursor.getCause()) {
            if (cursor instanceof IllegalStateException && cursor.getMessage() != null
                    && cursor.getMessage().contains("append-only")) {
                return;
            }
        }
        throw new AssertionError("the operation was not refused by the append-only guard: " + thrown, thrown);
    }

    // ── AC 145: every legal change leaves exactly one event ───────────────────

    @Nested
    @DisplayName("AC 145: every legal change leaves exactly one event")
    class EveryLegalChangeIsRecorded {

        @Test
        @DisplayName("create leaves one 'created' event with to_status=backlog and no from_status")
        void createLeavesOneCreatedEvent() throws Exception {
            Long id = createItem();

            List<WorkItemEventDTO> events = eventsOf(id);

            assertThat(events).hasSize(1);
            WorkItemEventDTO created = events.get(0);
            assertThat(created.getEvent()).isEqualTo(WorkItemEvent.EVENT_CREATED);
            assertThat(created.getFromStatus()).as("'created' has no predecessor state").isNull();
            assertThat(created.getToStatus()).isEqualTo(WorkItem.STATUS_BACKLOG);
            assertThat(created.getActorId())
                    .as("attribution came from the credential, not from the request body")
                    .isEqualTo(agentActorId);
            assertThat(created.getCreatedAt()).isNotNull();
        }

        @Test
        @DisplayName("each legal transition of a full lifecycle leaves exactly one event, with the right from/to")
        void everyLegalTransitionLeavesExactlyOneEvent() throws Exception {
            Long id = createItem();

            putStatus(id, WorkItem.STATUS_READY);
            claim(id);
            putStatus(id, WorkItem.STATUS_RUNNING);
            putStatus(id, WorkItem.STATUS_REVIEW);
            // WIKI4AI-150: 'review' -> 'done' now requires a 'result' comment. The
            // assertions of this test are unchanged — it counts the events a legal
            // lifecycle leaves, and a comment leaves no event.
            postResultComment(id);
            putStatus(id, WorkItem.STATUS_DONE);

            List<WorkItemEventDTO> events = eventsOf(id);

            assertThat(namesOf(events))
                    .as("create, backlog→ready, claim, claimed→running, running→review, review→done")
                    .containsExactly(
                            WorkItemEvent.EVENT_CREATED,
                            WorkItemEvent.EVENT_STATUS_CHANGED,
                            WorkItemEvent.EVENT_CLAIMED,
                            WorkItemEvent.EVENT_STATUS_CHANGED,
                            WorkItemEvent.EVENT_STATUS_CHANGED,
                            WorkItemEvent.EVENT_STATUS_CHANGED);

            assertThat(events).allSatisfy(event -> assertThat(event.getActorId()).isEqualTo(agentActorId));

            // A transition recorded with the wrong pair of states is a wrong fact,
            // not a missing one, so the pairs are asserted one by one.
            assertThat(events)
                    .extracting(WorkItemEventDTO::getFromStatus, WorkItemEventDTO::getToStatus)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple(null, WorkItem.STATUS_BACKLOG),
                            org.assertj.core.groups.Tuple.tuple(WorkItem.STATUS_BACKLOG, WorkItem.STATUS_READY),
                            org.assertj.core.groups.Tuple.tuple(WorkItem.STATUS_READY, WorkItem.STATUS_CLAIMED),
                            org.assertj.core.groups.Tuple.tuple(WorkItem.STATUS_CLAIMED, WorkItem.STATUS_RUNNING),
                            org.assertj.core.groups.Tuple.tuple(WorkItem.STATUS_RUNNING, WorkItem.STATUS_REVIEW),
                            org.assertj.core.groups.Tuple.tuple(WorkItem.STATUS_REVIEW, WorkItem.STATUS_DONE));
        }

        @Test
        @DisplayName("a claim leaves one 'claimed' event: ready -> claimed")
        void claimLeavesClaimedEvent() throws Exception {
            Long id = createItem();
            putStatus(id, WorkItem.STATUS_READY);

            claim(id);

            List<WorkItemEventDTO> events = eventsOf(id);
            WorkItemEventDTO claimed = events.get(events.size() - 1);
            assertThat(claimed.getEvent()).isEqualTo(WorkItemEvent.EVENT_CLAIMED);
            assertThat(claimed.getFromStatus()).isEqualTo(WorkItem.STATUS_READY);
            assertThat(claimed.getToStatus()).isEqualTo(WorkItem.STATUS_CLAIMED);
            assertThat(claimed.getActorId()).isEqualTo(agentActorId);
            assertThat(claimed.getPayload())
                    .as("a claim token is never written into the audit trail")
                    .isNull();
        }

        @Test
        @DisplayName("extending a lease leaves 'lease_extended' with the same state on both sides")
        void leaseExtensionIsRecorded() throws Exception {
            Long id = createItem();
            putStatus(id, WorkItem.STATUS_READY);
            String token = claim(id);

            mockMvc.perform(post("/api/v1/work-items/" + id + "/lease")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"claimToken\":\"" + token + "\"}"))
                    .andExpect(status().isOk());

            List<WorkItemEventDTO> events = eventsOf(id);
            WorkItemEventDTO extended = events.get(events.size() - 1);
            assertThat(extended.getEvent()).isEqualTo(WorkItemEvent.EVENT_LEASE_EXTENDED);
            assertThat(extended.getFromStatus()).isEqualTo(WorkItem.STATUS_CLAIMED);
            assertThat(extended.getToStatus()).isEqualTo(WorkItem.STATUS_CLAIMED);
            assertThat(extended.getActorId()).isEqualTo(agentActorId);
            assertThat(extended.getPayload()).contains("leaseExpiresAt");
        }

        @Test
        @DisplayName("entering ready, blocked or cancelled while held leaves 'claim_released'")
        void releasingAClaimIsRecorded() throws Exception {
            Long id = createItem();
            putStatus(id, WorkItem.STATUS_READY);
            claim(id);

            putStatus(id, WorkItem.STATUS_BLOCKED);

            List<WorkItemEventDTO> events = eventsOf(id);
            assertThat(namesOf(events)).containsExactly(
                    WorkItemEvent.EVENT_CREATED,
                    WorkItemEvent.EVENT_STATUS_CHANGED,
                    WorkItemEvent.EVENT_CLAIMED,
                    WorkItemEvent.EVENT_STATUS_CHANGED,
                    WorkItemEvent.EVENT_CLAIM_RELEASED);

            WorkItemEventDTO released = events.get(4);
            assertThat(released.getFromStatus()).isEqualTo(WorkItem.STATUS_CLAIMED);
            assertThat(released.getToStatus()).isEqualTo(WorkItem.STATUS_BLOCKED);
            assertThat(released.getActorId()).isEqualTo(agentActorId);
        }

        @Test
        @DisplayName("a transition back to ready releases the claim too")
        void claimedToReadyReleasesClaim() throws Exception {
            Long id = createItem();
            putStatus(id, WorkItem.STATUS_READY);
            claim(id);

            putStatus(id, WorkItem.STATUS_READY);

            assertThat(namesOf(eventsOf(id))).endsWith(
                    WorkItemEvent.EVENT_STATUS_CHANGED, WorkItemEvent.EVENT_CLAIM_RELEASED);
        }

        @Test
        @DisplayName("a transition that releases nothing records no 'claim_released'")
        void unheldTransitionRecordsNoRelease() throws Exception {
            Long id = createItem();

            putStatus(id, WorkItem.STATUS_BLOCKED);

            assertThat(namesOf(eventsOf(id)))
                    .as("the item was not held, so nothing was released: an audit trail does not "
                            + "record non-events")
                    .containsExactly(WorkItemEvent.EVENT_CREATED, WorkItemEvent.EVENT_STATUS_CHANGED);
        }

        @Test
        @DisplayName("delete leaves 'deleted' with from_status and no to_status")
        void deleteIsRecorded() throws Exception {
            Long id = createItem();

            mockMvc.perform(delete("/api/v1/work-items/" + id)).andExpect(status().isNoContent());

            List<WorkItemEventDTO> events = eventsOf(id);
            assertThat(events).hasSize(2);
            WorkItemEventDTO deleted = events.get(1);
            assertThat(deleted.getEvent()).isEqualTo(WorkItemEvent.EVENT_DELETED);
            assertThat(deleted.getFromStatus()).isEqualTo(WorkItem.STATUS_BACKLOG);
            assertThat(deleted.getToStatus()).as("deletion has no successor state").isNull();
            assertThat(deleted.getActorId()).isEqualTo(agentActorId);
        }

        @Test
        @DisplayName("an update that changes no state records no event")
        void renameRecordsNothing() throws Exception {
            Long id = createItem();

            mockMvc.perform(put("/api/v1/work-items/" + id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"Renamed only\"}"))
                    .andExpect(status().isOk());
            putStatus(id, WorkItem.STATUS_BACKLOG);

            assertThat(namesOf(eventsOf(id)))
                    .as("a rename is not a state change, and asking for the state the item already "
                            + "had changed nothing either")
                    .containsExactly(WorkItemEvent.EVENT_CREATED);
        }
    }

    // ── AC 145: a rejected change leaves nothing ──────────────────────────────

    @Nested
    @DisplayName("AC 145: a rejected change leaves no event")
    class RejectedChangesLeaveNoEvent {

        @Test
        @DisplayName("an illegal transition (409) leaves no event and no state change")
        void illegalTransitionLeavesNoEvent() throws Exception {
            Long id = createItem();

            mockMvc.perform(put("/api/v1/work-items/" + id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"done\"}"))
                    .andExpect(status().isConflict());

            assertThat(namesOf(eventsOf(id)))
                    .as("a 409 must not leave a trace in a table that records only what happened")
                    .containsExactly(WorkItemEvent.EVENT_CREATED);
            assertThat(reload(id).getStatus()).isEqualTo(WorkItem.STATUS_BACKLOG);
        }

        @Test
        @DisplayName("a lost claim race (409) leaves no second 'claimed' event")
        void lostClaimRaceLeavesNoEvent() throws Exception {
            Long id = createItem();
            putStatus(id, WorkItem.STATUS_READY);
            claim(id);

            mockMvc.perform(post("/api/v1/work-items/" + id + "/claim"))
                    .andExpect(status().isConflict());

            assertThat(namesOf(eventsOf(id)))
                    .as("the row stopped qualifying the moment the first claim wrote it, so the "
                            + "loser updated 0 rows and recorded nothing")
                    .containsExactly(WorkItemEvent.EVENT_CREATED,
                            WorkItemEvent.EVENT_STATUS_CHANGED,
                            WorkItemEvent.EVENT_CLAIMED);
        }

        @Test
        @DisplayName("an unknown status (400) leaves no event")
        void unknownStatusLeavesNoEvent() throws Exception {
            Long id = createItem();

            mockMvc.perform(put("/api/v1/work-items/" + id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"not-a-status\"}"))
                    .andExpect(status().isBadRequest());

            assertThat(namesOf(eventsOf(id))).containsExactly(WorkItemEvent.EVENT_CREATED);
        }

        @Test
        @DisplayName("a create that fails on a constraint leaves no event for anybody")
        void failedCreateLeavesNoEvent() throws Exception {
            Long id = createItem("First", publicProjectId);

            Map<String, Object> body = new HashMap<>();
            body.put("projectId", publicProjectId);
            body.put("externalRef", "WIE-REF-1");
            body.put("title", "Second");
            mockMvc.perform(post("/api/v1/work-items")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isCreated());

            body.put("title", "Third, same external_ref");
            mockMvc.perform(post("/api/v1/work-items")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isConflict());

            assertThat(eventsOf(id)).hasSize(1);
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM work_item_events", Integer.class))
                    .as("the rolled-back create left no audit row either: the event and the state "
                            + "change commit together or not at all")
                    .isEqualTo(2);
        }
    }

    // ── AC 145 + 146: the automatic changes are recorded too ──────────────────

    @Nested
    @DisplayName("AC 145 + 146: the reaper and the heal sweep record every row they touched")
    class AutomaticChangesAreRecorded {

        @Test
        @DisplayName("the reaper leaves one lease_expired per item, naming the actor that held the lease")
        void reaperLeavesOneEventPerItemNamingTheHolder() throws Exception {
            Long first = claimAndAge(createItem());
            Long second = claimAndAge(createItem());

            int released = leaseReaper.releaseExpired();

            assertThat(released).isEqualTo(2);

            for (Long id : List.of(first, second)) {
                List<WorkItemEventDTO> events = eventsOf(id);
                WorkItemEventDTO expired = events.get(events.size() - 1);
                assertThat(expired.getEvent()).isEqualTo(WorkItemEvent.EVENT_LEASE_EXPIRED);
                assertThat(expired.getFromStatus()).isEqualTo(WorkItem.STATUS_CLAIMED);
                assertThat(expired.getToStatus()).isEqualTo(WorkItem.STATUS_READY);
                assertThat(expired.getActorId())
                        .as("the event has to say WHOSE lease expired; after the sweep the row "
                                + "itself no longer carries an assignee, so this is the only place "
                                + "that fact survives")
                        .isEqualTo(agentActorId);
                assertThat(expired.getPayload()).contains("expiredLeaseAt");
            }

            assertThat(namesOf(eventsOf(first)))
                    .containsExactly(WorkItemEvent.EVENT_CREATED,
                            WorkItemEvent.EVENT_STATUS_CHANGED,
                            WorkItemEvent.EVENT_CLAIMED,
                            WorkItemEvent.EVENT_LEASE_EXPIRED);
        }

        @Test
        @DisplayName("a running item is reaped too and its event says running -> ready")
        void runningItemReapIsRecorded() throws Exception {
            Long id = claimAndAge(createItem());
            putStatus(id, WorkItem.STATUS_RUNNING);
            ageLease(id);

            assertThat(leaseReaper.releaseExpired()).isEqualTo(1);

            List<WorkItemEventDTO> events = eventsOf(id);
            WorkItemEventDTO expired = events.get(events.size() - 1);
            assertThat(expired.getEvent()).isEqualTo(WorkItemEvent.EVENT_LEASE_EXPIRED);
            assertThat(expired.getFromStatus()).isEqualTo(WorkItem.STATUS_RUNNING);
            assertThat(expired.getToStatus()).isEqualTo(WorkItem.STATUS_READY);
            assertThat(expired.getActorId()).isEqualTo(agentActorId);
        }

        @Test
        @DisplayName("the reaper writes no event for an item whose lease has not expired")
        void liveLeaseIsNotReapedAndNotRecorded() throws Exception {
            Long expired = claimAndAge(createItem());

            Long live = createItem();
            putStatus(live, WorkItem.STATUS_READY);
            claim(live);

            int released = leaseReaper.releaseExpired();

            assertThat(released).isEqualTo(1);
            assertThat(namesOf(eventsOf(live)))
                    .as("no lease expired on this item, so no event may exist for it: an event for "
                            + "a row whose state did not change is a false fact")
                    .containsExactly(WorkItemEvent.EVENT_CREATED,
                            WorkItemEvent.EVENT_STATUS_CHANGED,
                            WorkItemEvent.EVENT_CLAIMED);
            assertThat(namesOf(eventsOf(expired))).contains(WorkItemEvent.EVENT_LEASE_EXPIRED);
        }

        @Test
        @DisplayName("a second sweep releases nothing and records nothing")
        void secondSweepIsSilent() throws Exception {
            Long id = claimAndAge(createItem());

            assertThat(leaseReaper.releaseExpired()).isEqualTo(1);
            int eventsAfterFirstSweep = eventsOf(id).size();

            assertThat(leaseReaper.releaseExpired()).isZero();

            assertThat(eventsOf(id)).hasSize(eventsAfterFirstSweep);
        }

        @Test
        @DisplayName("the heal sweep leaves one stale_claim_cleared naming the actor it cleared")
        void healSweepLeavesEvent() throws Exception {
            Long id = createItem();
            stampStaleClaim(id, WorkItem.STATUS_READY, true);

            int cleaned = leaseReaper.releaseExpired();

            assertThat(cleaned).isGreaterThanOrEqualTo(1);
            List<WorkItemEventDTO> events = eventsOf(id);
            WorkItemEventDTO cleared = events.get(events.size() - 1);
            assertThat(cleared.getEvent()).isEqualTo(WorkItemEvent.EVENT_STALE_CLAIM_CLEARED);
            assertThat(cleared.getFromStatus()).isEqualTo(WorkItem.STATUS_READY);
            assertThat(cleared.getToStatus()).isEqualTo(WorkItem.STATUS_READY);
            assertThat(cleared.getActorId())
                    .as("a heal has no caller, so the only actor it can honestly name is the one "
                            + "whose claim it removed")
                    .isEqualTo(agentActorId);
            assertThat(cleared.getPayload()).contains("clearedAssigneeActorId");
        }

        @Test
        @DisplayName("a heal of a row with no assignee records a null actor instead of inventing one")
        void healWithoutAssigneeRecordsNullActor() throws Exception {
            Long id = createItem();
            stampStaleClaim(id, WorkItem.STATUS_BLOCKED, false);

            assertThat(leaseReaper.releaseExpired()).isGreaterThanOrEqualTo(1);

            List<WorkItemEventDTO> events = eventsOf(id);
            WorkItemEventDTO cleared = events.get(events.size() - 1);
            assertThat(cleared.getEvent()).isEqualTo(WorkItemEvent.EVENT_STALE_CLAIM_CLEARED);
            assertThat(cleared.getActorId()).isNull();
        }

        @Test
        @DisplayName("the heal sweep is silent when there is nothing stale to heal")
        void healIsSilentWhenNothingIsStale() throws Exception {
            Long id = createItem();
            putStatus(id, WorkItem.STATUS_READY);

            assertThat(leaseReaper.releaseExpired()).isZero();

            assertThat(eventsOf(id)).hasSize(2);
        }
    }

    // ── AC 145: append-only is enforced, not declared ─────────────────────────

    @Nested
    @DisplayName("AC 145: append-only is enforced")
    class AppendOnlyIsEnforced {

        /**
         * The proof the task asked for, run on purpose: {@code deleteAll()} on the
         * events repository throws. The exception is the assertion — it is what shows
         * that the repository inherited from {@code JpaRepository} cannot be used to
         * erase the trail, which is the only way an audit table can be trusted.
         */
        @Test
        @DisplayName("every inherited delete throws: the repository cannot erase the trail")
        void repositoryCannotDelete() throws Exception {
            Long id = createItem();
            WorkItemEvent stored = firstEventOf(id);

            assertThatThrownBy(() -> workItemEventRepository.deleteAll())
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("append-only");

            assertThatThrownBy(() -> workItemEventRepository.deleteAll(List.of(stored)))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("append-only");

            assertThatThrownBy(() -> workItemEventRepository.delete(stored))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("append-only");

            assertThatThrownBy(() -> workItemEventRepository.deleteById(stored.getId()))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("append-only");

            assertThatThrownBy(() -> workItemEventRepository.deleteAllById(List.of(stored.getId())))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("append-only");

            // The bulk statements are the dangerous ones: they send DELETE FROM without
            // loading an entity, so @PreRemove never runs for them, and the V21 trigger
            // is a BEFORE UPDATE trigger and does not either. The repository is the only
            // layer left that can refuse them, and it does.
            assertThatThrownBy(() -> workItemEventRepository.deleteAllInBatch())
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("append-only");
            assertThatThrownBy(() -> workItemEventRepository.deleteAllInBatch(List.of(stored)))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("append-only");
            assertThatThrownBy(() -> workItemEventRepository.deleteAllByIdInBatch(List.of(stored.getId())))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("append-only");

            assertThat(workItemEventRepository.findByWorkItemIdOrderByCreatedAtAscIdAsc(id))
                    .as("after eight attempted deletions, including the bulk ones, the audit row is "
                            + "still there")
                    .hasSize(1);
        }

        @Test
        @DisplayName("saveAndFlush is refused too: an existing event cannot be rewritten through the repository")
        void repositoryCannotRewriteAnExistingEvent() throws Exception {
            Long id = createItem();
            WorkItemEvent stored = firstEventOf(id);

            assertThatThrownBy(() -> workItemEventRepository.saveAndFlush(stored))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("append-only");
        }

        @Test
        @DisplayName("no delete method of the repository is inherited from Spring Data unchanged")
        void noDeleteIsInheritedUnchanged() {
            List<String> refused = new ArrayList<>();
            List<String> unguarded = new ArrayList<>();
            for (java.lang.reflect.Method method : WorkItemEventRepository.class.getMethods()) {
                if (!method.getName().startsWith("delete") && !method.getName().startsWith("saveAndFlush")) {
                    continue;
                }
                if (method.isDefault()) {
                    refused.add(method.getName());
                } else {
                    unguarded.add(method.getName());
                }
            }
            assertThat(unguarded)
                    .as("every mutating CRUD method is overridden here with a throwing default")
                    .isEmpty();
            assertThat(refused)
                    .contains("delete", "deleteById", "deleteAllById", "deleteAll", "saveAndFlush",
                            "deleteAllInBatch", "deleteAllByIdInBatch");
        }

        @Test
        @DisplayName("an event written to the database cannot be modified: the original value still holds")
        void eventCannotBeModified() throws Exception {
            Long id = createItem();
            WorkItemEvent stored = firstEventOf(id);
            String originalEvent = stored.getEvent();
            String originalToStatus = stored.getToStatus();

            assertRefusedAsAppendOnly(() -> inTransaction(() -> {
                WorkItemEvent managed = entityManager.find(WorkItemEvent.class, stored.getId());
                tamperEventField(managed, "tampered");
                entityManager.flush();
            }));

            WorkItemEvent reread = firstEventOf(id);
            assertThat(reread.getEvent())
                    .as("the UPDATE was refused, so the stored fact is still the one that happened")
                    .isEqualTo(originalEvent);
            assertThat(reread.getToStatus()).isEqualTo(originalToStatus);
            assertThat(reread.getId()).isEqualTo(stored.getId());
        }

        @Test
        @DisplayName("an event cannot be removed through JPA either")
        void eventCannotBeRemovedThroughJpa() throws Exception {
            Long id = createItem();
            WorkItemEvent stored = firstEventOf(id);

            assertRefusedAsAppendOnly(() -> inTransaction(() -> {
                entityManager.remove(entityManager.find(WorkItemEvent.class, stored.getId()));
                entityManager.flush();
            }));

            assertThat(workItemEventRepository.findByWorkItemIdOrderByCreatedAtAscIdAsc(id))
                    .as("the DELETE was refused; the audit row is still there")
                    .hasSize(1);
        }

        @Test
        @DisplayName("the entity has no setter and carries both lifecycle guards")
        void entityIsImmutableAndGuarded() {
            assertThat(Arrays.stream(WorkItemEvent.class.getDeclaredMethods())
                    .filter(method -> method.getName().startsWith("set")))
                    .as("an event is fixed at construction")
                    .isEmpty();
            assertThat(Arrays.stream(WorkItemEvent.class.getDeclaredMethods())
                    .filter(method -> method.isAnnotationPresent(jakarta.persistence.PreUpdate.class)
                            || method.isAnnotationPresent(jakarta.persistence.PreRemove.class)))
                    .as("the JPA lifecycle guards are the layer that catches a managed entity "
                            + "changed through a field, a listener or a bulk merge")
                    .hasSize(2);
        }

        @Test
        @DisplayName("the entity has no @Version: an append-only row has no value to lock")
        void entityHasNoVersion() {
            assertThat(Arrays.stream(WorkItemEvent.class.getDeclaredFields())
                    .filter(field -> field.isAnnotationPresent(jakarta.persistence.Version.class)))
                    .isEmpty();
        }

        @Test
        @DisplayName("record() appends a second row instead of touching the first")
        void recordAppendsInsteadOfUpdating() throws Exception {
            Long id = createItem();
            Long firstId = firstEventOf(id).getId();

            inTransaction(() -> workItemEventService.record(id, agentActorId,
                    WorkItemEvent.EVENT_STATUS_CHANGED, WorkItem.STATUS_BACKLOG, WorkItem.STATUS_READY,
                    (String) null));

            List<WorkItemEvent> rows = workItemEventRepository.findByWorkItemIdOrderByCreatedAtAscIdAsc(id);
            assertThat(rows).hasSize(2);
            assertThat(rows.get(0).getId()).isEqualTo(firstId);
            assertThat(rows.get(0).getEvent()).isEqualTo(WorkItemEvent.EVENT_CREATED);
            assertThat(rows.get(1).getId()).isNotEqualTo(firstId);
            assertThat(rows.get(1).getEvent()).isEqualTo(WorkItemEvent.EVENT_STATUS_CHANGED);
        }

        @Test
        @DisplayName("record() refuses to run outside a transaction: no audit row can commit alone")
        void recordCannotCommitOnItsOwn() throws Exception {
            Long id = createItem();

            assertThatThrownBy(() -> workItemEventService.record(id, agentActorId,
                    WorkItemEvent.EVENT_STATUS_CHANGED, WorkItem.STATUS_BACKLOG, WorkItem.STATUS_READY,
                    (String) null))
                    .as("Propagation.MANDATORY: the event joins the transaction of the state change "
                            + "it describes, so an audit row and its fact can never disagree")
                    .isInstanceOf(IllegalTransactionStateException.class)
                    .hasMessageContaining("mandatory");

            assertThat(eventsOf(id)).hasSize(1);
        }

        private WorkItemEvent firstEventOf(Long itemId) {
            return workItemEventRepository.findByWorkItemIdOrderByCreatedAtAscIdAsc(itemId).get(0);
        }
    }

    // ── AC 146: the timeline endpoint ─────────────────────────────────────────

    @Nested
    @DisplayName("AC 146: GET /api/v1/work-items/{id}/events")
    class TimelineEndpoint {

        @Test
        @DisplayName("200, chronological, and it contains lease_expired")
        void timelineIsChronologicalAndCarriesLeaseExpired() throws Exception {
            Long id = claimAndAge(createItem());
            leaseReaper.releaseExpired();
            claim(id);

            JsonNode page = getJson("/api/v1/work-items/" + id + "/events");

            List<String> order = new ArrayList<>();
            page.get("content").forEach(node -> order.add(node.get("event").asText()));
            assertThat(order).containsExactly(
                    WorkItemEvent.EVENT_CREATED,
                    WorkItemEvent.EVENT_STATUS_CHANGED,
                    WorkItemEvent.EVENT_CLAIMED,
                    WorkItemEvent.EVENT_LEASE_EXPIRED,
                    WorkItemEvent.EVENT_CLAIMED);

            assertThat(order).as("'timeline obsahuje aj lease_expired'")
                    .contains(WorkItemEvent.EVENT_LEASE_EXPIRED);

            JsonNode content = page.get("content");
            for (int i = 1; i < content.size(); i++) {
                LocalDateTime previous = LocalDateTime.parse(content.get(i - 1).get("createdAt").asText());
                LocalDateTime current = LocalDateTime.parse(content.get(i).get("createdAt").asText());
                assertThat(current).as("event %d is not after event %d", i, i - 1)
                        .isAfterOrEqualTo(previous);
                if (current.isEqual(previous)) {
                    assertThat(content.get(i).get("id").asLong())
                            .as("equal timestamps are broken by id, so the order is fully determined")
                            .isGreaterThan(content.get(i - 1).get("id").asLong());
                }
            }
        }

        @Test
        @DisplayName("pagination: page 0 and page 1 partition the timeline in the same order")
        void paginationPartitionsTheTimeline() throws Exception {
            Long id = createItem();
            for (int i = 0; i < 6; i++) {
                putStatus(id, WorkItem.STATUS_READY);
                putStatus(id, WorkItem.STATUS_BLOCKED);
            }

            JsonNode everything = getJson("/api/v1/work-items/" + id + "/events?page=0&size=100");
            JsonNode first = getJson("/api/v1/work-items/" + id + "/events?page=0&size=3");
            JsonNode second = getJson("/api/v1/work-items/" + id + "/events?page=1&size=3");

            int total = everything.get("content").size();
            assertThat(total).isEqualTo(13);

            assertThat(first.get("content")).hasSize(3);
            assertThat(second.get("content")).hasSize(3);
            assertThat(first.get("totalElements").asLong()).isEqualTo(total);
            assertThat(second.get("totalElements").asLong()).isEqualTo(total);
            assertThat(first.get("totalPages").asInt()).isEqualTo(5);
            assertThat(first.get("number").asInt()).isZero();
            assertThat(first.get("size").asInt()).isEqualTo(3);
            assertThat(second.get("number").asInt()).isEqualTo(1);
            assertThat(first.get("first").asBoolean()).isTrue();
            assertThat(first.get("last").asBoolean()).isFalse();
            assertThat(second.get("last").asBoolean())
                    .as("13 events at 3 per page is 5 pages, so page 1 is in the middle")
                    .isFalse();

            JsonNode tail = getJson("/api/v1/work-items/" + id + "/events?page=4&size=3");
            assertThat(tail.get("content")).hasSize(1);
            assertThat(tail.get("last").asBoolean()).isTrue();

            List<Long> firstIds = idsOf(first.get("content"));
            List<Long> secondIds = idsOf(second.get("content"));
            assertThat(secondIds).doesNotContainAnyElementsOf(firstIds);
            assertThat(concat(firstIds, secondIds))
                    .as("page 1 continues where page 0 stopped, in the same order")
                    .isEqualTo(idsOf(everything.get("content")).subList(0, 6));
        }

        @Test
        @DisplayName("size is clamped to 1..100 and a page past the end is empty, not an error")
        void paginationBoundsAreClamped() throws Exception {
            Long id = createItem();

            mockMvc.perform(get("/api/v1/work-items/" + id + "/events").param("size", "5000"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.size").value(100));

            mockMvc.perform(get("/api/v1/work-items/" + id + "/events").param("size", "-4"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.size").value(1));

            mockMvc.perform(get("/api/v1/work-items/" + id + "/events").param("page", "9"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(0));
        }

        @Test
        @DisplayName("ordering is createdAt ASC then id ASC: ids come out strictly increasing")
        void orderingIsDeterministic() throws Exception {
            Long id = createItem();
            for (int i = 0; i < 8; i++) {
                putStatus(id, WorkItem.STATUS_READY);
                putStatus(id, WorkItem.STATUS_BLOCKED);
            }

            assertThat(idsOf(getJson("/api/v1/work-items/" + id + "/events").get("content")))
                    .hasSize(17)
                    .isSorted()
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("the timeline of someone else's private item is 404")
        void foreignPrivateItemIs404() throws Exception {
            authenticate(STRANGER, strangerActorId);
            Long id = createItem("Private timeline", privateProjectId);
            putStatus(id, WorkItem.STATUS_READY);

            authenticate(OWNER, agentActorId);
            mockMvc.perform(get("/api/v1/work-items/" + id + "/events"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("an item that does not exist is 404")
        void unknownItemIs404() throws Exception {
            mockMvc.perform(get("/api/v1/work-items/999999/events"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("the timeline of a public item in a project the caller has no part in is 403")
        void foreignPublicItemIs403() throws Exception {
            authenticate(STRANGER, strangerActorId);
            Long id = createItem("Public but not mine", strangerProjectId);

            authenticate(OWNER, agentActorId);
            mockMvc.perform(get("/api/v1/work-items/" + id + "/events"))
                    .andExpect(status().isForbidden());

            mockMvc.perform(get("/api/v1/work-items/" + id))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("the payload answers who, when, and from which state to which")
        void payloadAnswersTheTimelineQuestions() throws Exception {
            Long id = createItem();
            putStatus(id, WorkItem.STATUS_READY);
            claim(id);

            mockMvc.perform(get("/api/v1/work-items/" + id + "/events"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].event").value(WorkItemEvent.EVENT_CREATED))
                    .andExpect(jsonPath("$.content[0].actorId").value(agentActorId))
                    .andExpect(jsonPath("$.content[0].createdAt").exists())
                    .andExpect(jsonPath("$.content[0].workItemId").value(id))
                    .andExpect(jsonPath("$.content[2].event").value(WorkItemEvent.EVENT_CLAIMED))
                    .andExpect(jsonPath("$.content[2].fromStatus").value(WorkItem.STATUS_READY))
                    .andExpect(jsonPath("$.content[2].toStatus").value(WorkItem.STATUS_CLAIMED))
                    .andExpect(jsonPath("$.content[2].payload").doesNotExist());
        }
    }

    private static List<Long> idsOf(JsonNode array) {
        List<Long> ids = new ArrayList<>();
        array.forEach(node -> ids.add(node.get("id").asLong()));
        return ids;
    }

    private static List<Long> concat(List<Long> a, List<Long> b) {
        List<Long> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }
}
