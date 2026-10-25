package com.wiki4ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wiki4ai.config.JwtAuthenticationFilter.ApiTokenAuthenticationDetails;
import com.wiki4ai.dto.ProjectCreateDTO;
import com.wiki4ai.dto.ProjectDTO;
import com.wiki4ai.dto.WorkItemClaimDTO;
import com.wiki4ai.dto.WorkItemDTO;
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
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the atomic claim (WIKI4AI-141) and for optimistic locking
 * of work items (WIKI4AI-140), against the real H2 database, the real
 * {@link WorkItemService}, the real {@link WorkItemRepository#claim} JPQL and the
 * real {@link RequestActorResolver}.
 * <p>
 * MockMvc runs without the security filter chain ({@code addFilters = false}), so
 * the authentication under test is exactly the one this class installs — the same
 * shape {@code JwtAuthenticationFilter} produces for a {@code w4a_} token
 * ({@link ApiTokenAuthenticationDetails} in {@code getDetails()}) and for a legacy
 * JWT ({@code details == null}).
 * <p>
 * The claim tests are the AC of WIKI4AI-141: the first claim of a ready item wins,
 * the second claim of the same item is 409, and an item that is not 'ready' cannot
 * be claimed at all. Because the guard is the WHERE clause of a plain JPQL UPDATE
 * (no RETURNING, no ON CONFLICT — H2 supports neither), the 409 here is produced
 * by the database reporting 0 rows updated, not by a pre-check in Java.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
// NOT @DirtiesContext, deliberately (SPEC rule 5b): every Spring context in this
// suite points at the same named in-memory database (jdbc:h2:mem:testdb;
// DB_CLOSE_DELAY=-1) with ddl-auto=create-drop, so closing one context DROPS THE
// SCHEMA the other, still-cached contexts are using — they then fail with
// "Table ... not found (this database is empty)". Isolation is done at the row
// level in cleanState() below, which is order-independent and touches nobody
// else's schema.
class WorkItemClaimIntegrationTest {

    private static final String OWNER = "wi140_owner";
    private static final String STRANGER = "wi140_stranger";

    private static final String PUBLIC_PROJECT = "wi140-public";
    private static final String PRIVATE_PROJECT = "wi140-private";
    private static final String STRANGER_PROJECT = "wi140-stranger";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WorkItemRepository workItemRepository;

    /**
     * Added for WIKI4AI-177: the leak is asserted at the DTO level as well as over
     * HTTP, because "the read DTO does not carry the token" is a statement about the
     * object every read path returns, not only about one response body.
     */
    @Autowired
    private WorkItemService workItemService;

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

    /**
     * Added for WIKI4AI-150: this class now writes {@code work_item_comments}, so rule
     * 5b requires them to be cleaned by this class, children first. A native DELETE is
     * the only available statement — {@code WorkItemCommentRepository.deleteAll()} is
     * refused by design (WIKI4AI-148 immutability), exactly as in
     * {@code WorkItemCommentIntegrationTest}.
     */
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Long agentActorId;
    private Long agentBActorId;
    private Long strangerAgentActorId;
    private Long publicProjectId;
    private Long privateProjectId;
    private Long strangerProjectId;

    // ── Rule 5b: this class writes users, actors, projects and work items into
    //    the H2 database the whole test JVM shares. Left behind, those rows make
    //    their users undeletable by anybody else and break tests whose premise is
    //    "this instance has no accounts yet" (AuthFirstRunFlowTest above all).
    //    Cleaning up BEFORE and AFTER every test, children first, makes the result
    //    independent of surefire's run order — a filesystem order, and a fresh CI
    //    checkout does not list files in the order a local tree created them.

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        cleanState();

        saveUser(OWNER, Role.USER);
        saveUser(STRANGER, Role.USER);

        agentActorId = saveActor("wi140-agent", Actor.KIND_AGENT, OWNER).getId();
        agentBActorId = saveActor("wi140-agent-b", Actor.KIND_AGENT, OWNER).getId();
        // STRANGER gets an agent actor but NO human actor: that is what makes the
        // "claim without an actor" case reachable through a legacy JWT.
        strangerAgentActorId = saveActor("wi140-stranger-agent", Actor.KIND_AGENT, STRANGER).getId();

        publicProjectId = createProject("WI140 Public", PUBLIC_PROJECT, Project.VISIBILITY_PUBLIC, OWNER);
        privateProjectId = createProject("WI140 Private", PRIVATE_PROJECT, Project.VISIBILITY_PRIVATE, OWNER);
        strangerProjectId = createProject("WI140 Stranger", STRANGER_PROJECT, Project.VISIBILITY_PUBLIC, STRANGER);

        // Default caller: the owner acting through its agent. Tests that need a
        // different caller install their own authentication explicitly.
        authenticate(OWNER, agentActorId);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        cleanState();
    }

    /** Children before parents: comments -> work_items -> project_actors -> permissions -> api_tokens -> actors -> projects -> users. */
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

    /** Install an authentication exactly as the JWT filter does for a w4a_ token. */
    private void authenticate(String username, Long actorId) {
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                username, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        authentication.setDetails(new ApiTokenAuthenticationDetails(1L, userIdOf(username), actorId,
                List.of("read", "write")));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    /** Legacy JWT: authenticated, details null — the credential names no actor. */
    private void authenticateLegacy(String username) {
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                username, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        authentication.setDetails(null);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private WorkItem reload(Long id) {
        return workItemRepository.findById(id).orElseThrow();
    }

    /** Create a work item over the real HTTP endpoint; returns its id. */
    private Long createItem(String title, Long projectId, String status) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("projectId", projectId);
        body.put("title", title);
        if (status != null) {
            body.put("status", status);
        }
        MvcResult result = mockMvc.perform(post("/api/v1/work-items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private void moveToReady(Long id) throws Exception {
        mockMvc.perform(put("/api/v1/work-items/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ready\"}"))
                .andExpect(status().isOk());
    }

    /**
     * Append the {@code result} comment that WIKI4AI-150 makes a precondition of
     * {@code done}. Added for that story only — the invariant is "an item is done only
     * when its result is written down", so a test that drives an item to {@code done}
     * has to write the result down, exactly as a real agent must.
     */
    private void postResultComment(Long id) throws Exception {
        mockMvc.perform(post("/api/v1/work-items/" + id + "/comments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"result\",\"body\":\"implemented on main\","
                                + "\"payload\":{\"where\":\"main\",\"next\":\"release\",\"artifacts\":[\"commit abc123\"]}}"))
                .andExpect(status().isCreated());
    }

    // ── The claim ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Claim of a ready work item")
    class ClaimSucceeds {

        @Test
        @DisplayName("the first claim returns 200 with claimToken, leaseExpiresAt and assigneeActorId")
        void firstClaimReturnsTheClaimTriple() throws Exception {
            Long id = createItem("Claimable task", publicProjectId, null);
            moveToReady(id);
            authenticate(OWNER, agentActorId);

            mockMvc.perform(post("/api/v1/work-items/" + id + "/claim"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(id))
                    .andExpect(jsonPath("$.status").value(WorkItem.STATUS_CLAIMED))
                    .andExpect(jsonPath("$.assigneeActorId").value(agentActorId))
                    .andExpect(jsonPath("$.claimToken").exists())
                    .andExpect(jsonPath("$.leaseExpiresAt").exists());

            WorkItem claimed = reload(id);
            assertThat(claimed.getStatus()).isEqualTo(WorkItem.STATUS_CLAIMED);
            assertThat(claimed.getAssigneeActorId()).isEqualTo(agentActorId);
            assertThat(claimed.getClaimToken())
                    .as("claim_token is VARCHAR(64)")
                    .hasSize(64)
                    .matches("[0-9a-f]{64}");
            assertThat(claimed.getLeaseExpiresAt())
                    .as("the lease is a TIMESTAMP column read back as LocalDateTime, and it is in the future")
                    .isAfter(LocalDateTime.now());
            assertThat(claimed.getUpdatedByActorId())
                    .as("the claim is a write like any other, so it is attributed")
                    .isEqualTo(agentActorId);
        }

        @Test
        @DisplayName("the claim bumps the @Version so readers can see the row moved")
        void claimIncrementsVersion() throws Exception {
            Long id = createItem("Versioned claim", publicProjectId, null);
            moveToReady(id);
            int beforeClaim = reload(id).getVersion();
            authenticate(OWNER, agentActorId);

            mockMvc.perform(post("/api/v1/work-items/" + id + "/claim"))
                    .andExpect(status().isOk());

            assertThat(reload(id).getVersion())
                    .as("the claim UPDATE increments version itself (bulk updates bypass JPA @Version)")
                    .isGreaterThan(beforeClaim);
        }
    }

    // ── AC of WIKI4AI-141 ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("AC WIKI4AI-141: a claim is exclusive")
    class ClaimIsExclusive {

        @Test
        @DisplayName("the SECOND claim of the same work item is 409")
        void secondClaimOfSameItemConflicts() throws Exception {
            Long id = createItem("One winner only", publicProjectId, null);
            moveToReady(id);
            authenticate(OWNER, agentActorId);

            mockMvc.perform(post("/api/v1/work-items/" + id + "/claim"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.assigneeActorId").value(agentActorId));

            // A SECOND AGENT OF THE SAME USER tries the very same item. The caller
            // keeps its write access to the project, so the only thing that can
            // reject it is the claim guard itself: the UPDATE matches no row because
            // status is no longer 'ready' -> 0 rows -> 409.
            authenticate(OWNER, agentBActorId);
            mockMvc.perform(post("/api/v1/work-items/" + id + "/claim"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("Conflict"));

            WorkItem item = reload(id);
            assertThat(item.getStatus()).isEqualTo(WorkItem.STATUS_CLAIMED);
            assertThat(item.getAssigneeActorId())
                    .as("the loser did not take the item over")
                    .isEqualTo(agentActorId);
        }

        @Test
        @DisplayName("the same actor claiming twice is also 409 — the lease is not re-issueable by an update")
        void sameActorCannotClaimTwice() throws Exception {
            Long id = createItem("No double lease", publicProjectId, null);
            moveToReady(id);
            authenticate(OWNER, agentActorId);

            mockMvc.perform(post("/api/v1/work-items/" + id + "/claim"))
                    .andExpect(status().isOk());
            mockMvc.perform(post("/api/v1/work-items/" + id + "/claim"))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("an item that is not 'ready' cannot be claimed (409)")
        void claimOfNotReadyItemConflicts() throws Exception {
            Long backlogItem = createItem("Still in backlog", publicProjectId, null);
            authenticate(OWNER, agentActorId);
            mockMvc.perform(post("/api/v1/work-items/" + backlogItem + "/claim"))
                    .andExpect(status().isConflict());

            Long cancelledItem = createItem("Cancelled", publicProjectId, WorkItem.STATUS_CANCELLED);
            mockMvc.perform(post("/api/v1/work-items/" + cancelledItem + "/claim"))
                    .andExpect(status().isConflict());

            assertThat(reload(backlogItem).getStatus())
                    .as("a failed claim changed nothing")
                    .isEqualTo(WorkItem.STATUS_BACKLOG);
            assertThat(reload(backlogItem).getClaimToken()).isNull();
        }

        @Test
        @DisplayName("claiming an unknown id is 404, not 409")
        void claimOfUnknownItemIsNotFound() throws Exception {
            authenticate(OWNER, agentActorId);

            mockMvc.perform(post("/api/v1/work-items/999999/claim"))
                    .andExpect(status().isNotFound());
        }
    }

    // ── Claim needs an actor ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Claim requires an actor identity")
    class ClaimNeedsActor {

        @Test
        @DisplayName("a legacy JWT whose user has no human actor is 403, never 500")
        void legacyJwtWithoutActorIsForbidden() throws Exception {
            // STRANGER owns STRANGER_PROJECT, so project ownership would pass:
            // the 403 can only come from the missing actor identity.
            authenticate(STRANGER, strangerAgentActorId);
            Long id = createItem("Needs an actor", strangerProjectId, null);
            moveToReady(id);
            authenticateLegacy(STRANGER);

            mockMvc.perform(post("/api/v1/work-items/" + id + "/claim"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("Forbidden"));

            WorkItem item = reload(id);
            assertThat(item.getStatus()).isEqualTo(WorkItem.STATUS_READY);
            assertThat(item.getAssigneeActorId()).isNull();
            assertThat(item.getClaimToken()).isNull();
        }

        @Test
        @DisplayName("a human with their own human actor may claim (attribution falls back to it)")
        void humanWithHumanActorMayClaim() throws Exception {
            Long humanActorId = saveActor("wi140-human", Actor.KIND_HUMAN, STRANGER).getId();
            authenticate(STRANGER, strangerAgentActorId);
            Long id = createItem("Human claim", strangerProjectId, null);
            moveToReady(id);
            authenticateLegacy(STRANGER);

            mockMvc.perform(post("/api/v1/work-items/" + id + "/claim"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.assigneeActorId").value(humanActorId));
        }
    }

    // ── Attribution ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Attribution comes from the credential only")
    class Attribution {

        @Test
        @DisplayName("createdByActorId is the token's actor")
        void createdByActorComesFromTheToken() throws Exception {
            authenticate(OWNER, agentActorId);

            Long id = createItem("Agent created", publicProjectId, null);

            WorkItem item = reload(id);
            assertThat(item.getCreatedByActorId()).isEqualTo(agentActorId);
            assertThat(item.getCreatedByUserId()).isEqualTo(userIdOf(OWNER));
        }

        @Test
        @DisplayName("AC: createdByActorId forced through the body is ignored")
        void bodyCannotNameTheActor() throws Exception {
            authenticate(OWNER, agentActorId);

            Map<String, Object> body = new HashMap<>();
            body.put("projectId", publicProjectId);
            body.put("title", "Injected attribution");
            body.put("createdByActorId", strangerAgentActorId);
            body.put("updatedByActorId", strangerAgentActorId);
            body.put("assigneeActorId", strangerAgentActorId);
            body.put("claimToken", "forged-token");
            body.put("version", 42);

            MvcResult result = mockMvc.perform(post("/api/v1/work-items")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isCreated())
                    .andReturn();
            Long id = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();

            WorkItem item = reload(id);
            assertThat(item.getCreatedByActorId())
                    .as("the value came from the verified credential, not from the body")
                    .isEqualTo(agentActorId)
                    .isNotEqualTo(strangerAgentActorId);
            assertThat(item.getClaimToken())
                    .as("a body field cannot mint a claim")
                    .isNull();
            assertThat(item.getVersion()).isEqualTo(0);
        }

        @Test
        @DisplayName("a caller with no actor at all leaves the attribution null")
        void callerWithoutActorLeavesNullAttribution() throws Exception {
            authenticateLegacy(STRANGER);

            Long id = createItem("No actor anywhere", strangerProjectId, null);

            WorkItem item = reload(id);
            assertThat(item.getCreatedByActorId()).isNull();
            assertThat(item.getCreatedByUserId()).isEqualTo(userIdOf(STRANGER));
        }
    }

    // ── State machine over HTTP ───────────────────────────────────────────────

    @Nested
    @DisplayName("State machine over HTTP")
    class StateMachine {

        @Test
        @DisplayName("an illegal transition is 409")
        void illegalTransitionIsConflict() throws Exception {
            authenticate(OWNER, agentActorId);
            Long id = createItem("Straight to done", publicProjectId, null);

            mockMvc.perform(put("/api/v1/work-items/" + id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"done\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("Conflict"));

            assertThat(reload(id).getStatus()).isEqualTo(WorkItem.STATUS_BACKLOG);
        }

        @Test
        @DisplayName("AC: 'claimed' is not reachable through a plain PUT")
        void claimedIsUnreachableByPut() throws Exception {
            authenticate(OWNER, agentActorId);
            Long id = createItem("Cannot fake a claim", publicProjectId, null);

            mockMvc.perform(put("/api/v1/work-items/" + id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"claimed\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("/claim")));

            WorkItem item = reload(id);
            assertThat(item.getStatus()).isEqualTo(WorkItem.STATUS_BACKLOG);
            assertThat(item.getAssigneeActorId()).isNull();
        }

        @Test
        @DisplayName("the full path backlog -> ready -> claimed -> running -> review -> done works")
        void fullLifecycleWorks() throws Exception {
            authenticate(OWNER, agentActorId);
            Long id = createItem("Full lifecycle", publicProjectId, null);

            moveToReady(id);
            mockMvc.perform(post("/api/v1/work-items/" + id + "/claim"))
                    .andExpect(status().isOk());
            mockMvc.perform(put("/api/v1/work-items/" + id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"running\"}"))
                    .andExpect(status().isOk());
            mockMvc.perform(put("/api/v1/work-items/" + id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"review\"}"))
                    .andExpect(status().isOk());
            // WIKI4AI-150: 'review' -> 'done' now requires a 'result' comment. The
            // assertions of this test — the full path works, and the assignee survives
            // the move to 'done' — are unchanged; the test simply satisfies the new
            // precondition instead of being weakened around it.
            postResultComment(id);
            mockMvc.perform(put("/api/v1/work-items/" + id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"done\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("done"));

            WorkItem item = reload(id);
            assertThat(item.getStatus()).isEqualTo(WorkItem.STATUS_DONE);
            assertThat(item.getAssigneeActorId())
                    .as("claimed -> running keeps the lease holder")
                    .isEqualTo(agentActorId);
        }

        @Test
        @DisplayName("leaving 'claimed' for 'blocked' releases the lease")
        void blockedReleasesLease() throws Exception {
            authenticate(OWNER, agentActorId);
            Long id = createItem("Blocked while claimed", publicProjectId, null);
            moveToReady(id);
            mockMvc.perform(post("/api/v1/work-items/" + id + "/claim"))
                    .andExpect(status().isOk());

            mockMvc.perform(put("/api/v1/work-items/" + id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"blocked\"}"))
                    .andExpect(status().isOk());

            WorkItem item = reload(id);
            assertThat(item.getStatus()).isEqualTo(WorkItem.STATUS_BLOCKED);
            assertThat(item.getAssigneeActorId()).isNull();
            assertThat(item.getClaimToken()).isNull();
            assertThat(item.getLeaseExpiresAt()).isNull();
        }
    }

    // ── Ownership ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Ownership follows the project")
    class Ownership {

        @Test
        @DisplayName("an item of a foreign private project is 404")
        void foreignPrivateItemIsNotFound() throws Exception {
            authenticate(OWNER, agentActorId);
            Long id = createItem("Private project item", privateProjectId, null);

            authenticateLegacy(STRANGER);
            mockMvc.perform(get("/api/v1/work-items/" + id))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("writing to a foreign public project is 403")
        void foreignPublicProjectIsForbidden() throws Exception {
            authenticate(OWNER, agentActorId);
            Long id = createItem("Owner's item", publicProjectId, null);

            authenticate(STRANGER, strangerAgentActorId);
            mockMvc.perform(put("/api/v1/work-items/" + id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"Not yours\"}"))
                    .andExpect(status().isForbidden());

            assertThat(reload(id).getTitle()).isEqualTo("Owner's item");
        }

        @Test
        @DisplayName("listing a foreign private project is 404")
        void listOfForeignPrivateProjectIsNotFound() throws Exception {
            authenticateLegacy(STRANGER);

            mockMvc.perform(get("/api/v1/work-items").param("projectId", String.valueOf(privateProjectId)))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("a listing without projectId hides items of private projects")
        void listingHidesPrivateProjectItems() throws Exception {
            authenticate(OWNER, agentActorId);
            createItem("Visible to all", publicProjectId, null);
            createItem("Hidden", privateProjectId, null);

            authenticateLegacy(STRANGER);
            mockMvc.perform(get("/api/v1/work-items"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)))
                    .andExpect(jsonPath("$[0].title").value("Visible to all"));
        }
    }

    // ── Optimistic locking (@Version) ─────────────────────────────────────────

    @Nested
    @DisplayName("Optimistic locking (@Version, AC of WIKI4AI-140)")
    class OptimisticLocking {

        private TransactionDefinition newTransaction() {
            DefaultTransactionDefinition def = new DefaultTransactionDefinition();
            def.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            return def;
        }

        private <T> T runInNewTx(Supplier<T> work) {
            TransactionStatus tx = transactionManager.getTransaction(newTransaction());
            try {
                T result = work.get();
                transactionManager.commit(tx);
                return result;
            } catch (RuntimeException e) {
                transactionManager.rollback(tx);
                throw e;
            }
        }

        @Test
        @DisplayName("two concurrent updates: the second one fails instead of silently overwriting")
        void secondConcurrentUpdateFails() {
            Long itemId = runInNewTx(() -> {
                WorkItem created = workItemRepository.save(WorkItem.builder()
                        .projectId(publicProjectId)
                        .title("Race item")
                        .status(WorkItem.STATUS_BACKLOG)
                        .priority("medium")
                        .build());
                return created.getId();
            });
            assertThat(reload(itemId).getVersion()).isEqualTo(0);

            // Writer B opens a transaction, reads the version-0 state and stays open
            // while writer A commits in between (nested REQUIRES_NEW suspension —
            // deterministic, no thread timing involved).
            TransactionStatus txB = transactionManager.getTransaction(newTransaction());
            WorkItem b = workItemRepository.findById(itemId).orElseThrow();
            assertThat(b.getVersion()).isEqualTo(0);
            b.setTitle("writer B content");
            workItemRepository.save(b);

            runInNewTx(() -> {
                WorkItem a = workItemRepository.findById(itemId).orElseThrow();
                assertThat(a.getVersion()).isEqualTo(0);
                a.setTitle("writer A content");
                workItemRepository.save(a);
                return null;
            });

            // B now flushes: its UPDATE ... WHERE id = ? AND version = 0 matches no
            // row, because A already moved the row to version 1.
            assertThatThrownBy(() -> workItemRepository.flush())
                    .isInstanceOf(ObjectOptimisticLockingFailureException.class);
            transactionManager.rollback(txB);

            WorkItem persisted = runInNewTx(() -> workItemRepository.findById(itemId).orElseThrow());
            assertThat(persisted.getTitle())
                    .as("the database holds A's write; B's stale write was rejected, not applied")
                    .isEqualTo("writer A content");
            assertThat(persisted.getVersion()).isEqualTo(1);
        }

        @Test
        @DisplayName("sequential updates keep working and each one bumps the version")
        void sequentialUpdatesWork() {
            Long itemId = runInNewTx(() -> workItemRepository.save(WorkItem.builder()
                    .projectId(publicProjectId)
                    .title("Sequential")
                    .status(WorkItem.STATUS_BACKLOG)
                    .priority("medium")
                    .build()).getId());

            runInNewTx(() -> {
                WorkItem item = workItemRepository.findById(itemId).orElseThrow();
                item.setTitle("v1");
                workItemRepository.save(item);
                return null;
            });
            runInNewTx(() -> {
                WorkItem item = workItemRepository.findById(itemId).orElseThrow();
                item.setTitle("v2");
                workItemRepository.save(item);
                return null;
            });

            WorkItem persisted = runInNewTx(() -> workItemRepository.findById(itemId).orElseThrow());
            assertThat(persisted.getTitle()).isEqualTo("v2");
            assertThat(persisted.getVersion()).isEqualTo(2);
        }
    }

    // ── WIKI4AI-177: the claim token leaves the application through one door only ──

    /**
     * Measured on dev at commit {@code 16c3ddd}: the 64-character claim token appeared
     * in three responses — the claim (intended), {@code GET /work-items/{id}} and
     * {@code GET /work-items?status=claimed} — the last one under a <b>legacy web
     * JWT</b>, the path the human UI actually uses. One list call therefore handed the
     * caller the lease proofs of every claimed item of every project it could read.
     * <p>
     * The token is not power on its own — {@code extendLease} also requires an actor
     * credential and that the caller <i>is</i> the assignee — which is exactly why this
     * is Medium and not High. It is the second half of a pair, and it was being sent to
     * readers who need none of it: the board renders {@code leaseExpiresAt}, never the
     * token.
     * <p>
     * The fix is a fill rule, not a field removal: {@code convertToDTO} (every read
     * path) stopped filling it; {@code convertToClaimDTO} (claim and lease extension)
     * kept it. These tests hold both halves, so a "fix" that removed the token from the
     * claim response — breaking every agent that holds a lease — is caught by the same
     * class that catches the leak.
     */
    @Nested
    @DisplayName("WIKI4AI-177: claimToken on the claim response only, never on a GET")
    class ClaimTokenStaysWithTheClaim {

        @Test
        @DisplayName("AC: POST /claim still returns the 64-character claimToken — agents depend on it")
        void claimStillReturnsTheToken() throws Exception {
            Long id = createItem("Claim keeps its token", publicProjectId, null);
            moveToReady(id);
            authenticate(OWNER, agentActorId);

            mockMvc.perform(post("/api/v1/work-items/" + id + "/claim"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.claimToken").exists())
                    .andExpect(jsonPath("$.claimToken").value(
                            org.hamcrest.Matchers.matchesPattern("[0-9a-f]{64}")))
                    .andExpect(jsonPath("$.leaseExpiresAt").exists())
                    .andExpect(jsonPath("$.assigneeActorId").value(agentActorId));
        }

        @Test
        @DisplayName("AC: GET /work-items/{id} on a claimed item carries no claimToken, and keeps leaseExpiresAt")
        void detailCarriesNoTokenAndKeepsTheLeaseExpiry() throws Exception {
            Long id = claimOne("Detail without a token");

            mockMvc.perform(get("/api/v1/work-items/" + id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(id))
                    .andExpect(jsonPath("$.status").value(WorkItem.STATUS_CLAIMED))
                    .andExpect(jsonPath("$.claimToken").doesNotExist())
                    .andExpect(jsonPath("$.assigneeActorId").value(agentActorId))
                    .andExpect(jsonPath("$.leaseExpiresAt").exists());

            assertThat(reload(id).getClaimToken())
                    .as("the token is still on the row — this is about what a READ reports, "
                            + "not about what the lease holds")
                    .hasSize(64);
        }

        @Test
        @DisplayName("AC: GET /work-items?status=claimed returns no item with a claimToken")
        void listCarriesNoToken() throws Exception {
            Long first = claimOne("Listed claimed one");
            Long second = claimOne("Listed claimed two");

            JsonNode body = objectMapper.readTree(mockMvc.perform(
                            get("/api/v1/work-items")
                                    .param("projectId", String.valueOf(publicProjectId))
                                    .param("status", WorkItem.STATUS_CLAIMED))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString());

            assertThat(body).hasSize(2);
            for (JsonNode item : body) {
                assertThat(item.has("claimToken"))
                        .as("item %s came back with a lease proof it never asked for",
                                item.get("id").asLong())
                        .isFalse();
                assertThat(item.has("leaseExpiresAt"))
                        .as("the countdown the board renders is public operational data and stays")
                        .isTrue();
            }
            assertThat(body.get(0).get("id").asLong()).isIn(java.util.List.of(first, second));
        }

        @Test
        @DisplayName("AC: the measured leak — a legacy web JWT reading the claimed list gets no token either")
        void legacyJwtListCarriesNoToken() throws Exception {
            claimOne("Claimed by an agent, read by a human UI");

            // The exact call the measurement made: the human UI's credential, reading the
            // claimed list of the project.
            authenticateLegacy(OWNER);
            JsonNode body = objectMapper.readTree(mockMvc.perform(
                            get("/api/v1/work-items")
                                    .param("projectId", String.valueOf(publicProjectId))
                                    .param("status", WorkItem.STATUS_CLAIMED))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString());

            assertThat(body).isNotEmpty();
            assertThat(body.findValues("claimToken"))
                    .as("measured before the fix: 1 item, 1 with claimToken, length 64")
                    .isEmpty();
        }

        @Test
        @DisplayName("AC: extendLease still works with the right token, and still returns one")
        void extendLeaseWithTheRightTokenIs200() throws Exception {
            Long id = createItem("Lease extended with the real token", publicProjectId, null);
            moveToReady(id);
            authenticate(OWNER, agentActorId);

            String token = objectMapper.readTree(mockMvc.perform(
                            post("/api/v1/work-items/" + id + "/claim"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString()).get("claimToken").asText();

            mockMvc.perform(post("/api/v1/work-items/" + id + "/lease")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"claimToken\":\"" + token + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.claimToken").value(token))
                    .andExpect(jsonPath("$.leaseExpiresAt").exists())
                    .andExpect(jsonPath("$.assigneeActorId").value(agentActorId));

            assertThat(reload(id).getStatus()).isEqualTo(WorkItem.STATUS_CLAIMED);
        }

        @Test
        @DisplayName("AC: extendLease with a wrong token is refused — the token check is functional, not decoration")
        void extendLeaseWithAWrongTokenIs403() throws Exception {
            // The item has to be held before its lease can be forged: an unclaimed item
            // answers 409 "there is no lease to extend", which is a different rule and
            // would hide this one.
            Long id = claimOne("Lease extended with a forged token");
            authenticate(OWNER, agentActorId);

            mockMvc.perform(post("/api/v1/work-items/" + id + "/lease")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"claimToken\":\"" + "0".repeat(64) + "\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("claim token does not match")));

            assertThat(reload(id).getStatus()).isEqualTo(WorkItem.STATUS_CLAIMED);
        }

        @Test
        @DisplayName("AC: WorkItemDTO carries no claimToken on any read path; WorkItemClaimDTO does")
        void readDtoCarriesNoTokenClaimDtoDoes() throws Exception {
            Long id = claimOne("DTO level proof");
            String storedToken = reload(id).getClaimToken();
            assertThat(storedToken).hasSize(64);

            WorkItemDTO detail = workItemService.getWorkItem(id, OWNER);
            assertThat(detail.getClaimToken())
                    .as("the object GET /work-items/{id} answers with")
                    .isNull();
            assertThat(detail.getLeaseExpiresAt())
                    .as("and it still says when the lease ends")
                    .isNotNull();
            assertThat(detail.getAssigneeActorId()).isEqualTo(agentActorId);

            for (WorkItemDTO listed : workItemService.listWorkItems(
                    publicProjectId, WorkItem.STATUS_CLAIMED, OWNER)) {
                assertThat(listed.getClaimToken())
                        .as("the object GET /work-items answers with, for every item it returns")
                        .isNull();
                assertThat(listed.getLeaseExpiresAt()).isNotNull();
            }

            WorkItemClaimDTO claim = workItemService.claimWorkItem(
                    createItem("Another claim", publicProjectId, WorkItem.STATUS_READY), OWNER);
            assertThat(claim.getClaimToken())
                    .as("WorkItemClaimDTO is the DTO that carries the token, and it still does")
                    .isNotNull()
                    .hasSize(64)
                    .isNotEqualTo(storedToken);
        }

        @Test
        @DisplayName("no read path leaks it either: create and update responses carry none")
        void writeResponsesCarryNoTokenEither() throws Exception {
            Long id = createItem("Created without a token in the answer", publicProjectId, null);

            JsonNode created = objectMapper.readTree(mockMvc.perform(
                            get("/api/v1/work-items/" + id))
                    .andReturn().getResponse().getContentAsString());
            assertThat(created.has("claimToken")).isFalse();

            moveToReady(id);
            authenticate(OWNER, agentActorId);
            mockMvc.perform(put("/api/v1/work-items/" + id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"Renamed while ready\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.claimToken").doesNotExist());
        }

        /** Create a ready item, claim it as the owner's agent, and return its id. */
        private Long claimOne(String title) throws Exception {
            Long id = createItem(title, publicProjectId, null);
            moveToReady(id);
            authenticate(OWNER, agentActorId);
            mockMvc.perform(post("/api/v1/work-items/" + id + "/claim"))
                    .andExpect(status().isOk());
            return id;
        }
    }
}
