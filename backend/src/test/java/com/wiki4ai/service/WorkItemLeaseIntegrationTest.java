package com.wiki4ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wiki4ai.config.JwtAuthenticationFilter.ApiTokenAuthenticationDetails;
import com.wiki4ai.dto.ProjectCreateDTO;
import com.wiki4ai.dto.ProjectDTO;
import com.wiki4ai.model.Actor;
import com.wiki4ai.model.Project;
import com.wiki4ai.model.Role;
import com.wiki4ai.model.User;
import com.wiki4ai.model.WorkItem;
import com.wiki4ai.repository.ActorRepository;
import com.wiki4ai.repository.ApiTokenRepository;
import com.wiki4ai.repository.ProjectActorRepository;
import com.wiki4ai.repository.ProjectPermissionRepository;
import com.wiki4ai.repository.ProjectRepository;
import com.wiki4ai.repository.UserRepository;
import com.wiki4ai.repository.WorkItemRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the lease lifecycle (WIKI4AI-142), the state-machine
 * guards (WIKI4AI-143) and the list filters (WIKI4AI-144) — plus the regression
 * WIKI4AI-170 was filed for.
 * <p>
 * Everything runs against the real H2 database, the real {@link WorkItemService},
 * the real bulk JPQL of {@link WorkItemRepository} and the real
 * {@link RequestActorResolver}. MockMvc runs without the security filter chain
 * ({@code addFilters = false}), so the authentication under test is exactly the one
 * this class installs — the shape {@code JwtAuthenticationFilter} produces for a
 * {@code w4a_} token.
 * <p>
 * <b>The reaper is driven by hand, on purpose.</b> The scheduled trigger is off in
 * the test profile ({@code wiki4ai.work-items.scheduling-enabled=false} removes
 * {@code @EnableScheduling} from the test contexts — asserted below, not assumed)
 * and the tests call {@link WorkItemLeaseReaper#releaseExpired()}, which is the same
 * method the {@code @Scheduled} wrapper calls. A background thread sweeping a
 * database shared by the whole suite would move rows between assertions: a
 * timing-dependent failure, not a test.
 *
 * <h2>Rule 5b</h2>
 * {@code cleanState()} runs in {@code @BeforeEach} AND {@code @AfterEach}, children
 * before parents. No {@code @DirtiesContext}: every context in this suite points at
 * the same named in-memory H2, so closing one would drop the schema the others are
 * still using.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@DisplayName("Work items: lease TTL + reaper (142), state machine (143), filters (144), WIKI4AI-170")
class WorkItemLeaseIntegrationTest {

    private static final String OWNER = "wi142_owner";
    private static final String STRANGER = "wi142_stranger";

    private static final String PUBLIC_PROJECT = "wi142-public";
    private static final String STRANGER_PROJECT = "wi142-stranger";

    /** Bean name @EnableScheduling registers; its absence proves no scheduler runs in tests. */
    private static final String SCHEDULED_PROCESSOR_BEAN =
            "org.springframework.context.annotation.internalScheduledAnnotationProcessor";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private org.springframework.core.env.Environment environment;

    @Autowired
    private WorkItemRepository workItemRepository;

    @Autowired
    private WorkItemLeaseReaper leaseReaper;

    /** Added with WIKI4AI-150 so this class can clean the comments it now writes. */
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

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
    private Long agentBActorId;
    private Long strangerAgentActorId;
    private Long publicProjectId;
    private Long strangerProjectId;

    // ── Rule 5b: shared H2, cleaned before AND after every test, children first ─

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        cleanState();

        saveUser(OWNER, Role.USER);
        saveUser(STRANGER, Role.USER);

        agentActorId = saveActor("wi142-agent", Actor.KIND_AGENT, OWNER).getId();
        agentBActorId = saveActor("wi142-agent-b", Actor.KIND_AGENT, OWNER).getId();
        strangerAgentActorId = saveActor("wi142-stranger-agent", Actor.KIND_AGENT, STRANGER).getId();

        publicProjectId = createProject("WI142 Public", PUBLIC_PROJECT, Project.VISIBILITY_PUBLIC, OWNER);
        strangerProjectId = createProject("WI142 Stranger", STRANGER_PROJECT, Project.VISIBILITY_PUBLIC, STRANGER);

        authenticate(OWNER, agentActorId);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        cleanState();
    }

    /**
     * work_item_comments → work_items → project_actors → permissions → api_tokens →
     * actors → projects → users.
     * <p>
     * The {@code work_item_comments} line is new with WIKI4AI-150: this class now writes
     * a {@code result} comment to drive items to {@code done}, and rule 5b requires a
     * class that writes a table to clean it. A native DELETE is the only statement
     * available — {@code WorkItemCommentRepository.deleteAll()} is refused by design
     * (the immutability of WIKI4AI-148).
     */
    private void cleanState() {
        jdbcTemplate.execute("DELETE FROM work_item_comments");
        workItemRepository.deleteAll();
        projectActorRepository.deleteAll();
        projectPermissionRepository.deleteAll();
        apiTokenRepository.deleteAll();
        actorRepository.deleteAll();
        projectRepository.deleteAll();
        for (String username : List.of(OWNER, STRANGER)) {
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

    private Actor saveActor(String handle, String kind, String ownerUsername) {
        Actor actor = new Actor();
        actor.setHandle(handle);
        actor.setKind(kind);
        actor.setOwnerUserId(userIdOf(ownerUsername));
        return actorRepository.save(actor);
    }

    private Long createProject(String name, String slug, String visibility, String username) {
        ProjectDTO project = projectService.createProject(
                ProjectCreateDTO.builder().name(name).visibility(visibility).build(), username);
        assertThat(project.getSlug()).isEqualTo(slug);
        return project.getId();
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

    private Long createItem(String title, Long projectId, String status, List<String> labels) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("projectId", projectId);
        body.put("title", title);
        if (status != null) {
            body.put("status", status);
        }
        if (labels != null) {
            body.put("labels", labels);
        }
        MvcResult result = mockMvc.perform(post("/api/v1/work-items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private void moveToReady(Long id) throws Exception {
        putStatus(id, WorkItem.STATUS_READY);
    }

    private void putStatus(Long id, String status) throws Exception {
        mockMvc.perform(put("/api/v1/work-items/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"" + status + "\"}"))
                .andExpect(status().isOk());
    }

    /**
     * Append the {@code result} comment WIKI4AI-150 makes a precondition of {@code done}.
     * Added for that story only: every item this class drives to {@code done} now has to
     * write its result down first, exactly as a real agent does.
     */
    private void postResultComment(Long id) throws Exception {
        mockMvc.perform(post("/api/v1/work-items/" + id + "/comments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"result\",\"body\":\"implemented on main\","
                                + "\"payload\":{\"where\":\"main\",\"next\":\"release\",\"artifacts\":[\"commit abc123\"]}}"))
                .andExpect(status().isCreated());
    }

    /** Claim over HTTP and return the token the response carried. */
    private String claim(Long id) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/work-items/" + id + "/claim"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("claimToken").asText();
    }

    /**
     * Test fixture only: put an item into a state and stamp a claim triple onto it
     * by writing the row directly. That is what a database written before the
     * WIKI4AI-170 fix looks like — the service refuses to produce such a row now.
     */
    private void stampClaimTriple(Long id, String status, LocalDateTime leaseExpiresAt) {
        WorkItem item = reload(id);
        item.setStatus(status);
        item.setAssigneeActorId(agentActorId);
        item.setClaimToken("f".repeat(64));
        item.setLeaseExpiresAt(leaseExpiresAt);
        workItemRepository.save(item);
    }

    private void ageLease(Long id) {
        WorkItem item = reload(id);
        item.setLeaseExpiresAt(LocalDateTime.now().minusMinutes(1));
        workItemRepository.save(item);
    }

    /** GET /api/v1/work-items with the given query parameters, as a JSON array. */
    private JsonNode listItems(String... nameValuePairs) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/v1/work-items");
        for (int i = 0; i + 1 < nameValuePairs.length; i += 2) {
            request = request.param(nameValuePairs[i], nameValuePairs[i + 1]);
        }
        MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static List<Long> idsOf(JsonNode array) {
        List<Long> ids = new ArrayList<>();
        array.forEach(node -> ids.add(node.get("id").asLong()));
        return ids;
    }

    // ── WIKI4AI-142: the TTL and the reaper ───────────────────────────────────

    @Nested
    @DisplayName("AC 142: an expired lease returns the item to the pool")
    class ExpiredLeaseIsReleased {

        @Test
        @DisplayName("reaper releases an expired claimed item: ready, no assignee, no token, no lease")
        void expiredLeaseIsSweptBackToReady() throws Exception {
            Long id = claimAndAge();

            int released = leaseReaper.releaseExpired();

            assertThat(released).as("one row moved").isEqualTo(1);
            WorkItem item = reload(id);
            assertThat(item.getStatus())
                    .as("AC: po vyprsani TTL je uloha opat ready")
                    .isEqualTo(WorkItem.STATUS_READY);
            assertThat(item.getAssigneeActorId()).isNull();
            assertThat(item.getClaimToken()).isNull();
            assertThat(item.getLeaseExpiresAt()).isNull();
        }

        @Test
        @DisplayName("a running item with an expired lease is reaped too — a dead agent must not hold it")
        void runningItemWithExpiredLeaseIsReaped() throws Exception {
            Long id = claimAndAge();
            putStatus(id, WorkItem.STATUS_RUNNING);
            assertThat(reload(id).getAssigneeActorId())
                    .as("'running' is a claim state, so the triple survived the transition")
                    .isEqualTo(agentActorId);
            ageLease(id);

            int released = leaseReaper.releaseExpired();

            assertThat(released).isEqualTo(1);
            WorkItem item = reload(id);
            assertThat(item.getStatus()).isEqualTo(WorkItem.STATUS_READY);
            assertThat(item.getAssigneeActorId()).isNull();
            assertThat(item.getClaimToken()).isNull();
            assertThat(item.getLeaseExpiresAt()).isNull();
        }

        @Test
        @DisplayName("a live lease is NOT reaped")
        void liveLeaseSurvivesTheSweep() throws Exception {
            Long id = claimFresh();

            int released = leaseReaper.releaseExpired();

            assertThat(released).as("nothing is expired, so nothing moves").isZero();
            WorkItem item = reload(id);
            assertThat(item.getStatus()).isEqualTo(WorkItem.STATUS_CLAIMED);
            assertThat(item.getAssigneeActorId()).isEqualTo(agentActorId);
        }

        @Test
        @DisplayName("atomic: a second sweep finds nothing — the row no longer matches its own guard")
        void sweepIsAtomicAndIdempotent() throws Exception {
            claimAndAge();
            claimAndAge();

            int first = leaseReaper.releaseExpired();
            int second = leaseReaper.releaseExpired();

            assertThat(first).as("both expired items were released").isEqualTo(2);
            assertThat(second)
                    .as("a released row is 'ready' with no lease, so it cannot match the WHERE clause again")
                    .isZero();
        }

        @Test
        @DisplayName("the scheduled wrapper releases exactly what releaseExpired() releases")
        void scheduledWrapperDrivesTheSameOperation() throws Exception {
            Long id = claimAndAge();

            // Called on the bean Spring injected — the same reference the scheduler
            // invokes. This is the wiring proof that stands in for a live timer, and
            // it only passes if the bulk UPDATE ran inside a transaction.
            leaseReaper.sweepExpiredLeases();

            WorkItem item = reload(id);
            assertThat(item.getStatus()).isEqualTo(WorkItem.STATUS_READY);
            assertThat(item.getClaimToken()).isNull();
            assertThat(item.getLeaseExpiresAt()).isNull();
        }

        @Test
        @DisplayName("the test profile has no scheduler: no scheduled-annotation processor in the context")
        void schedulingIsOffInTests() {
            assertThat(applicationContext.containsBean(SCHEDULED_PROCESSOR_BEAN))
                    .as("wiki4ai.work-items.scheduling-enabled=false must remove @EnableScheduling from the "
                            + "test context, or a background sweep would race with other tests' assertions")
                    .isFalse();
            assertThat(applicationContext.containsBean("taskScheduler"))
                    .as("no scheduler bean either — TaskSchedulingAutoConfiguration is conditional on the "
                            + "scheduled-annotation processor")
                    .isFalse();
            assertThat(leaseReaper)
                    .as("the reaper bean itself is present and callable — only the timer is off")
                    .isNotNull();
        }

        private Long claimFresh() throws Exception {
            Long id = createItem("Live lease", publicProjectId, null, null);
            moveToReady(id);
            authenticate(OWNER, agentActorId);
            claim(id);
            return id;
        }

        private Long claimAndAge() throws Exception {
            Long id = claimFresh();
            ageLease(id);
            return id;
        }
    }

    // ── WIKI4AI-142: lease extension ──────────────────────────────────────────

    @Nested
    @DisplayName("AC 142: extending a lease")
    class ExtendLease {

        @Test
        @DisplayName("the holder extends its lease: 200 and leaseExpiresAt in the future")
        void holderExtendsItsLease() throws Exception {
            Long id = claimFresh();
            String token = reload(id).getClaimToken();
            LocalDateTime before = reload(id).getLeaseExpiresAt();

            mockMvc.perform(post("/api/v1/work-items/" + id + "/lease")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"claimToken\":\"" + token + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(id))
                    .andExpect(jsonPath("$.status").value(WorkItem.STATUS_CLAIMED))
                    .andExpect(jsonPath("$.assigneeActorId").value(agentActorId))
                    .andExpect(jsonPath("$.claimToken").value(token))
                    .andExpect(jsonPath("$.leaseExpiresAt").exists());

            WorkItem item = reload(id);
            assertThat(item.getLeaseExpiresAt())
                    .as("the lease moved forward, it was not merely left alone")
                    .isAfter(before);
            assertThat(item.getLeaseExpiresAt()).isAfter(LocalDateTime.now());
            assertThat(item.getStatus()).isEqualTo(WorkItem.STATUS_CLAIMED);
            assertThat(item.getAssigneeActorId()).isEqualTo(agentActorId);
            assertThat(item.getClaimToken())
                    .as("extending does not mint a new claim: the same token still authorises it")
                    .isEqualTo(token);
        }

        @Test
        @DisplayName("a body-less request works: identity alone proves the holder")
        void holderExtendsWithoutSendingTheToken() throws Exception {
            Long id = claimFresh();

            mockMvc.perform(post("/api/v1/work-items/" + id + "/lease"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.leaseExpiresAt").exists())
                    .andExpect(jsonPath("$.assigneeActorId").value(agentActorId));
        }

        @Test
        @DisplayName("AC: a foreign actor extending somebody else's lease is 403")
        void foreignActorIsForbidden() throws Exception {
            Long id = claimFresh();
            String token = reload(id).getClaimToken();

            // Same user, different agent: the project write access is unchanged, so
            // the 403 can only come from the holder check.
            authenticate(OWNER, agentBActorId);
            mockMvc.perform(post("/api/v1/work-items/" + id + "/lease")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"claimToken\":\"" + token + "\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("Forbidden"));

            WorkItem item = reload(id);
            assertThat(item.getAssigneeActorId())
                    .as("the attempt changed nothing")
                    .isEqualTo(agentActorId);
        }

        @Test
        @DisplayName("a wrong claim token is 403 even for the right actor")
        void wrongTokenIsForbidden() throws Exception {
            Long id = claimFresh();
            WorkItem before = reload(id);
            LocalDateTime leaseBefore = before.getLeaseExpiresAt();
            int versionBefore = before.getVersion();

            mockMvc.perform(post("/api/v1/work-items/" + id + "/lease")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"claimToken\":\"" + "0".repeat(64) + "\"}"))
                    .andExpect(status().isForbidden());

            WorkItem item = reload(id);
            assertThat(item.getLeaseExpiresAt())
                    .as("a rejected extension left the lease exactly where it was")
                    .isEqualTo(leaseBefore);
            assertThat(item.getVersion())
                    .as("nothing was written, so the version did not move")
                    .isEqualTo(versionBefore);
        }

        @Test
        @DisplayName("AC: extending an item nobody holds is 409")
        void unclaimedItemConflicts() throws Exception {
            Long readyItem = createItem("Ready, unclaimed", publicProjectId, null, null);
            moveToReady(readyItem);
            authenticate(OWNER, agentActorId);

            mockMvc.perform(post("/api/v1/work-items/" + readyItem + "/lease"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("Conflict"));

            Long backlogItem = createItem("Backlog", publicProjectId, null, null);
            mockMvc.perform(post("/api/v1/work-items/" + backlogItem + "/lease"))
                    .andExpect(status().isConflict());

            assertThat(reload(readyItem).getStatus()).isEqualTo(WorkItem.STATUS_READY);
            assertThat(reload(readyItem).getLeaseExpiresAt()).isNull();
            assertThat(reload(readyItem).getClaimToken()).isNull();
        }

        @Test
        @DisplayName("extending an unknown id is 404")
        void unknownItemIsNotFound() throws Exception {
            mockMvc.perform(post("/api/v1/work-items/999999/lease"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("the extended lease is long enough for the next sweep to leave it alone")
        void extendedLeaseOutlivesTheNextSweep() throws Exception {
            Long id = claimAndAge();
            authenticate(OWNER, agentActorId);
            String token = reload(id).getClaimToken();

            mockMvc.perform(post("/api/v1/work-items/" + id + "/lease")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"claimToken\":\"" + token + "\"}"))
                    .andExpect(status().isOk());

            assertThat(leaseReaper.releaseExpired())
                    .as("the renewal moved the lease into the future, so the sweep has no candidate")
                    .isZero();
            assertThat(reload(id).getStatus()).isEqualTo(WorkItem.STATUS_CLAIMED);
        }

        private Long claimFresh() throws Exception {
            Long id = createItem("Lease to extend", publicProjectId, null, null);
            moveToReady(id);
            authenticate(OWNER, agentActorId);
            claim(id);
            return id;
        }

        private Long claimAndAge() throws Exception {
            Long id = claimFresh();
            ageLease(id);
            return id;
        }
    }

    // ── WIKI4AI-170: the claim triple follows the state ────────────────────────

    @Nested
    @DisplayName("WIKI4AI-170: ready / blocked / cancelled never hold a claim")
    class ClaimTripleFollowsState {

        @Test
        @DisplayName("running -> blocked releases assignee, token and lease")
        void runningToBlockedReleasesTheTriple() throws Exception {
            Long id = claimAndRun();

            putStatus(id, WorkItem.STATUS_BLOCKED);

            WorkItem item = reload(id);
            assertThat(item.getStatus()).isEqualTo(WorkItem.STATUS_BLOCKED);
            assertThat(item.getAssigneeActorId())
                    .as("measured on .219 before the fix: assigneeActorId=12 survived running -> blocked")
                    .isNull();
            assertThat(item.getClaimToken()).isNull();
            assertThat(item.getLeaseExpiresAt()).isNull();
        }

        @Test
        @DisplayName("blocked -> ready releases assignee, token and lease")
        void blockedToReadyReleasesTheTriple() throws Exception {
            Long id = claimAndRun();
            putStatus(id, WorkItem.STATUS_BLOCKED);

            putStatus(id, WorkItem.STATUS_READY);

            WorkItem item = reload(id);
            assertThat(item.getStatus()).isEqualTo(WorkItem.STATUS_READY);
            assertThat(item.getAssigneeActorId())
                    .as("measured on .219 before the fix: the item sat in 'ready' still carrying its token")
                    .isNull();
            assertThat(item.getClaimToken()).isNull();
            assertThat(item.getLeaseExpiresAt()).isNull();
        }

        @Test
        @DisplayName("claimed -> cancelled releases the triple")
        void cancelledReleasesTheTriple() throws Exception {
            Long id = claimAndRun();

            putStatus(id, WorkItem.STATUS_CANCELLED);

            WorkItem item = reload(id);
            assertThat(item.getStatus()).isEqualTo(WorkItem.STATUS_CANCELLED);
            assertThat(item.getAssigneeActorId()).isNull();
            assertThat(item.getClaimToken()).isNull();
            assertThat(item.getLeaseExpiresAt()).isNull();
        }

        @Test
        @DisplayName("claimed -> ready releases the triple")
        void claimedToReadyReleasesTheTriple() throws Exception {
            Long id = claimOnly();

            putStatus(id, WorkItem.STATUS_READY);

            WorkItem item = reload(id);
            assertThat(item.getStatus()).isEqualTo(WorkItem.STATUS_READY);
            assertThat(item.getAssigneeActorId()).isNull();
            assertThat(item.getClaimToken()).isNull();
            assertThat(item.getLeaseExpiresAt()).isNull();
        }

        @Test
        @DisplayName("claimed -> running keeps the triple: those are the two claim states")
        void runningKeepsTheClaim() throws Exception {
            WorkItem item = reload(claimAndRun());
            assertThat(item.getStatus()).isEqualTo(WorkItem.STATUS_RUNNING);
            assertThat(item.getAssigneeActorId()).isEqualTo(agentActorId);
            assertThat(item.getClaimToken()).isNotNull();
            assertThat(item.getLeaseExpiresAt()).isAfter(LocalDateTime.now());
        }

        @Test
        @DisplayName("review and done end the lease: no live lease outside claimed/running")
        void reviewAndDoneEndTheLease() throws Exception {
            Long id = claimAndRun();
            putStatus(id, WorkItem.STATUS_REVIEW);

            WorkItem reviewed = reload(id);
            assertThat(reviewed.getStatus()).isEqualTo(WorkItem.STATUS_REVIEW);
            assertThat(reviewed.getClaimToken())
                    .as("nobody may act on a reviewed item under a lease")
                    .isNull();
            assertThat(reviewed.getLeaseExpiresAt()).isNull();
            assertThat(reviewed.getAssigneeActorId())
                    .as("who did the work stays readable — the existing lifecycle test relies on it")
                    .isEqualTo(agentActorId);

            // WIKI4AI-150: 'review' -> 'done' now requires a 'result' comment.
            postResultComment(id);
            putStatus(id, WorkItem.STATUS_DONE);

            WorkItem done = reload(id);
            assertThat(done.getStatus()).isEqualTo(WorkItem.STATUS_DONE);
            assertThat(done.getClaimToken()).isNull();
            assertThat(done.getLeaseExpiresAt()).isNull();
        }

        @Test
        @DisplayName("AC: GET /work-items?status=ready returns no item with an assignee or a lease")
        void readyListingCarriesNoClaimResidue() throws Exception {
            // The exact sequence WIKI4AI-170 was measured with.
            Long walked = claimAndRun();
            putStatus(walked, WorkItem.STATUS_BLOCKED);
            putStatus(walked, WorkItem.STATUS_READY);
            // Plus a row written the old way, which the sweep has to heal.
            Long legacy = createItem("Written before the fix", publicProjectId, WorkItem.STATUS_READY, null);
            stampClaimTriple(legacy, WorkItem.STATUS_READY, LocalDateTime.now().plusMinutes(30));

            assertThat(leaseReaper.releaseExpired())
                    .as("the sweep cleans claim residue left on claim-free states")
                    .isGreaterThanOrEqualTo(1);

            JsonNode body = listItems("status", "ready");
            assertThat(idsOf(body)).containsExactlyInAnyOrder(walked, legacy);
            for (JsonNode item : body) {
                assertThat(item.has("claimToken"))
                        .as("item %s must not expose a claim token", item.get("id").asLong())
                        .isFalse();
                assertThat(item.has("leaseExpiresAt"))
                        .as("item %s must not expose a lease", item.get("id").asLong())
                        .isFalse();
                assertThat(item.get("assigneeActorId").isNull())
                        .as("item %s is 'ready' but still looks occupied", item.get("id").asLong())
                        .isTrue();
            }

            for (WorkItem item : workItemRepository.findByStatusOrderByCreatedAtDesc(WorkItem.STATUS_READY)) {
                assertThat(item.getAssigneeActorId()).isNull();
                assertThat(item.getClaimToken()).isNull();
                assertThat(item.getLeaseExpiresAt()).isNull();
            }
        }

        @Test
        @DisplayName("the sweep heals a stale claim sitting on a blocked item")
        void sweepHealsStaleClaimOnBlockedItem() throws Exception {
            Long id = createItem("Blocked with a stale claim", publicProjectId, null, null);
            stampClaimTriple(id, WorkItem.STATUS_BLOCKED, LocalDateTime.now().plusMinutes(30));

            int cleaned = leaseReaper.releaseExpired();

            assertThat(cleaned).isGreaterThanOrEqualTo(1);
            WorkItem item = reload(id);
            assertThat(item.getStatus()).isEqualTo(WorkItem.STATUS_BLOCKED);
            assertThat(item.getAssigneeActorId()).isNull();
            assertThat(item.getClaimToken()).isNull();
            assertThat(item.getLeaseExpiresAt()).isNull();
        }

        private Long claimOnly() throws Exception {
            Long id = createItem("Claimed, not started", publicProjectId, null, null);
            moveToReady(id);
            authenticate(OWNER, agentActorId);
            claim(id);
            return id;
        }

        private Long claimAndRun() throws Exception {
            Long id = createItem("Work in progress", publicProjectId, null, null);
            moveToReady(id);
            authenticate(OWNER, agentActorId);
            claim(id);
            putStatus(id, WorkItem.STATUS_RUNNING);
            return id;
        }
    }

    // ── WIKI4AI-143: state machine guards ──────────────────────────────────────

    @Nested
    @DisplayName("AC 143: state machine guards")
    class StateMachineGuards {

        @Test
        @DisplayName("AC: claimed -> backlog is rejected (409)")
        void claimedToBacklogConflicts() throws Exception {
            Long id = createItem("Claimed item", publicProjectId, null, null);
            moveToReady(id);
            authenticate(OWNER, agentActorId);
            claim(id);

            mockMvc.perform(put("/api/v1/work-items/" + id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"backlog\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("Conflict"))
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("claimed")));

            WorkItem item = reload(id);
            assertThat(item.getStatus())
                    .as("a rejected transition changed nothing")
                    .isEqualTo(WorkItem.STATUS_CLAIMED);
            assertThat(item.getAssigneeActorId()).isEqualTo(agentActorId);
        }

        @Test
        @DisplayName("AC: a direct transition to 'claimed' is rejected (409) from every state")
        void claimedIsUnreachableByUpdate() throws Exception {
            authenticate(OWNER, agentActorId);
            for (String from : List.of(WorkItem.STATUS_BACKLOG, WorkItem.STATUS_READY,
                    WorkItem.STATUS_BLOCKED, WorkItem.STATUS_REVIEW)) {
                Long id = createItem("From " + from, publicProjectId, from, null);
                mockMvc.perform(put("/api/v1/work-items/" + id)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"status\":\"claimed\"}"))
                        .andExpect(status().isConflict())
                        .andExpect(jsonPath("$.message").value(
                                org.hamcrest.Matchers.containsString("/claim")));
                assertThat(reload(id).getStatus()).isEqualTo(from);
                assertThat(reload(id).getClaimToken()).isNull();
                assertThat(reload(id).getAssigneeActorId()).isNull();
            }
        }

        @Test
        @DisplayName("backlog -> done is illegal (409) and done is terminal")
        void terminalAndIllegalTransitions() throws Exception {
            authenticate(OWNER, agentActorId);
            Long id = createItem("Straight to done", publicProjectId, null, null);
            mockMvc.perform(put("/api/v1/work-items/" + id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"done\"}"))
                    .andExpect(status().isConflict());
            assertThat(reload(id).getStatus()).isEqualTo(WorkItem.STATUS_BACKLOG);

            // WIKI4AI-150: an item can no longer be created straight in 'done' — 'done'
            // requires a comment of kind 'result', and a comment can only be attached to
            // an item that already exists. The fixture reaches 'done' the way the API now
            // requires; the assertion this test makes — 'done' is terminal — is unchanged.
            Long done = createItem("Already done", publicProjectId, WorkItem.STATUS_REVIEW, null);
            postResultComment(done);
            putStatus(done, WorkItem.STATUS_DONE);
            mockMvc.perform(put("/api/v1/work-items/" + done)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"running\"}"))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("the legal path backlog -> ready -> claimed -> running -> review -> done works")
        void legalPathWorks() throws Exception {
            authenticate(OWNER, agentActorId);
            Long id = createItem("Full lifecycle", publicProjectId, null, null);
            moveToReady(id);
            claim(id);
            // WIKI4AI-150: the last step of this path is 'review' -> 'done', which now
            // requires a 'result' comment. The loop and its assertions are unchanged.
            postResultComment(id);
            for (String next : List.of(WorkItem.STATUS_RUNNING, WorkItem.STATUS_REVIEW, WorkItem.STATUS_DONE)) {
                mockMvc.perform(put("/api/v1/work-items/" + id)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"status\":\"" + next + "\"}"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.status").value(next));
            }
            assertThat(reload(id).getStatus()).isEqualTo(WorkItem.STATUS_DONE);
        }
    }

    // ── WIKI4AI-142: how long the lease is ────────────────────────────────────

    @Nested
    @DisplayName("AC 142: the lease TTL is 45 minutes and it is configuration, not a constant")
    class LeaseDuration {

        @Test
        @DisplayName("a fresh claim gets a 45 minute lease")
        void defaultLeaseIs45Minutes() throws Exception {
            // 'now' is taken BEFORE the claim. The lease is measured from the moment
            // the claim happened; measuring from after it shaves milliseconds off a
            // duration that is then truncated to whole minutes.
            LocalDateTime beforeClaim = LocalDateTime.now();

            Long id = claimFresh();

            WorkItem item = reload(id);
            assertThat(item.getLeaseExpiresAt())
                    .as("AC of WIKI4AI-142: \"Lease TTL default 45 min\"")
                    .isBetween(beforeClaim.plusMinutes(44), beforeClaim.plusMinutes(46));

            // The LENGTH of the lease, not the minute of the hour it lands on. What
            // stood here before subtracted two getMinute() values, which is a
            // difference of clock positions rather than a duration: a claim at 23:22
            // whose lease ends at 00:07 produced 7 - 22 = -15, so the test passed or
            // failed depending on the hour of day it happened to run in — green at
            // 22:19, red at 23:44, from the same code. A test of a TTL must not care
            // what time it is.
            Duration leaseLength = Duration.between(beforeClaim, item.getLeaseExpiresAt());
            assertThat(leaseLength.toMinutes())
                    .as("AC of WIKI4AI-142: lease TTL default 45 min, not the 15 the code used to hardcode")
                    .isBetween(44L, 46L);
        }

        @Test
        @DisplayName("the value comes from wiki4ai.work-items.lease-minutes, not from a literal")
        void leaseMinutesAreBoundToAProperty() {
            assertThat(environment.getProperty("wiki4ai.work-items.lease-minutes"))
                    .as("the property is resolved in the running context")
                    .isEqualTo("45");
            assertThat(WorkItemService.DEFAULT_LEASE_MINUTES)
                    .as("the fallback constant matches the documented default")
                    .isEqualTo(45);
        }

        private Long claimFresh() throws Exception {
            Long id = createItem("Lease duration", publicProjectId, null, null);
            moveToReady(id);
            authenticate(OWNER, agentActorId);
            claim(id);
            return id;
        }
    }

    // ── WIKI4AI-144: filters ───────────────────────────────────────────────────

    @Nested
    @DisplayName("AC 144: GET /work-items?status=&label=&assignee=")
    class ListFilters {

        private Long labelledItemId;
        private Long labItemId;
        private Long plainItemId;
        private Long claimedItemId;

        @BeforeEach
        void seedItems() throws Exception {
            authenticate(OWNER, agentActorId);
            // 'label' and 'lab' differ by a substring on purpose: the filter has to
            // tell them apart, and LIKE would not.
            labelledItemId = createItem("Carries the label", publicProjectId, WorkItem.STATUS_READY,
                    List.of("label", "backend"));
            labItemId = createItem("Carries lab", publicProjectId, WorkItem.STATUS_READY, List.of("lab"));
            plainItemId = createItem("No labels", publicProjectId, WorkItem.STATUS_BACKLOG, null);
            claimedItemId = createItem("Claimed by agent A", publicProjectId, WorkItem.STATUS_READY,
                    List.of("label"));
            claim(claimedItemId);
        }

        @Test
        @DisplayName("AC: ?label=lab does NOT match an item labelled 'label'")
        void labelIsMatchedAsWholeToken() throws Exception {
            assertThat(idsOf(listItems("label", "lab")))
                    .as("'lab' is a substring of 'label'; a LIKE would have matched both")
                    .containsExactly(labItemId);
        }

        @Test
        @DisplayName("?label=label matches the items labelled 'label', not the one labelled 'lab'")
        void theLongerLabelMatchesOnlyItself() throws Exception {
            assertThat(idsOf(listItems("label", "label")))
                    .containsExactlyInAnyOrder(labelledItemId, claimedItemId);
        }

        @Test
        @DisplayName("a prefix of a stored label matches nothing")
        void prefixOfALabelMatchesNothing() throws Exception {
            assertThat(idsOf(listItems("label", "backe"))).isEmpty();
        }

        @Test
        @DisplayName("any label of an item matches it")
        void anyLabelOfAnItemMatches() throws Exception {
            assertThat(idsOf(listItems("label", "backend"))).containsExactly(labelledItemId);
        }

        @Test
        @DisplayName("AC: ?status= returns exactly that state")
        void statusFilter() throws Exception {
            assertThat(idsOf(listItems("status", "ready")))
                    .containsExactlyInAnyOrder(labelledItemId, labItemId);
            assertThat(idsOf(listItems("status", "backlog"))).containsExactly(plainItemId);
            assertThat(idsOf(listItems("status", "claimed"))).containsExactly(claimedItemId);
            assertThat(idsOf(listItems("status", "done"))).isEmpty();
        }

        @Test
        @DisplayName("AC: ?assignee= returns exactly the items that actor holds")
        void assigneeFilter() throws Exception {
            assertThat(idsOf(listItems("assignee", String.valueOf(agentActorId))))
                    .containsExactly(claimedItemId);
            assertThat(idsOf(listItems("assignee", String.valueOf(agentBActorId)))).isEmpty();
            assertThat(idsOf(listItems("assignee", String.valueOf(strangerAgentActorId)))).isEmpty();
        }

        @Test
        @DisplayName("AC: filters combine with AND")
        void filtersAreCombined() throws Exception {
            assertThat(idsOf(listItems("projectId", String.valueOf(publicProjectId),
                    "status", "ready", "label", "label")))
                    .containsExactly(labelledItemId);

            // claimed AND label=lab: the only claimed item is labelled 'label'.
            assertThat(idsOf(listItems("status", "claimed", "label", "lab"))).isEmpty();

            // ready AND assignee=agent A: the only item A holds is 'claimed'.
            assertThat(idsOf(listItems("status", "ready", "assignee", String.valueOf(agentActorId)))).isEmpty();

            // claimed AND assignee=agent A: that one item.
            assertThat(idsOf(listItems("status", "claimed", "assignee", String.valueOf(agentActorId))))
                    .containsExactly(claimedItemId);
        }

        @Test
        @DisplayName("AC: an unknown status is 400, not 500 and not an empty 200")
        void unknownStatusIsBadRequest() throws Exception {
            mockMvc.perform(get("/api/v1/work-items").param("status", "in-progress"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("Bad Request"))
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("in-progress")));

            mockMvc.perform(get("/api/v1/work-items")
                            .param("projectId", String.valueOf(publicProjectId))
                            .param("status", "nope"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("a blank filter value is no filter")
        void blankValuesAreIgnored() throws Exception {
            assertThat(idsOf(listItems("status", "", "label", "")))
                    .containsExactlyInAnyOrder(labelledItemId, labItemId, plainItemId, claimedItemId);
        }

        @Test
        @DisplayName("filters still hide items of private projects the caller cannot see")
        void filtersRespectVisibility() throws Exception {
            Long privateItem = createPrivateLabelledItem();

            authenticate(STRANGER, strangerAgentActorId);
            assertThat(idsOf(listItems("label", "secret")))
                    .as("the item is in a private project the caller does not see")
                    .isEmpty();

            authenticate(OWNER, agentActorId);
            assertThat(idsOf(listItems("label", "secret"))).containsExactly(privateItem);
        }

        private Long createPrivateLabelledItem() throws Exception {
            ProjectDTO privateProject = projectService.createProject(
                    ProjectCreateDTO.builder().name("WI142 Private").visibility(Project.VISIBILITY_PRIVATE).build(),
                    OWNER);
            return createItem("Private labelled item", privateProject.getId(), WorkItem.STATUS_READY,
                    List.of("secret", "label"));
        }
    }
}
