package com.wiki4ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wiki4ai.config.JwtAuthenticationFilter.ApiTokenAuthenticationDetails;
import com.wiki4ai.dto.ResourceRequestDTO;
import com.wiki4ai.model.Actor;
import com.wiki4ai.model.Project;
import com.wiki4ai.model.Reservation;
import com.wiki4ai.model.Resource;
import com.wiki4ai.model.Role;
import com.wiki4ai.model.User;
import com.wiki4ai.model.WorkItem;
import com.wiki4ai.repository.ActorRepository;
import com.wiki4ai.repository.ApiTokenRepository;
import com.wiki4ai.repository.ProjectActorRepository;
import com.wiki4ai.repository.ProjectPermissionRepository;
import com.wiki4ai.repository.ProjectRepository;
import com.wiki4ai.repository.ReservationRepository;
import com.wiki4ai.repository.ResourceRepository;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC of WIKI4AI-155 against the real H2 database, the real {@link ResourceService}, the
 * real {@link ResourceController} and the real {@link RequestActorResolver}: CRUD of
 * resources works, reservations can be read, a duplicate key is 409, an unknown key is 404,
 * and the access rules are the ones {@code ResourceService} documents.
 * <p>
 * MockMvc runs with {@code addFilters = false}, so the authentication under test is exactly
 * the one this class installs — the same shape {@code JwtAuthenticationFilter} produces for
 * a {@code w4a_} token ({@link ApiTokenAuthenticationDetails} in {@code getDetails()}) and
 * for a legacy JWT ({@code details == null}).
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
// NOT @DirtiesContext, deliberately (SPEC rule 5b): every Spring context in this suite
// points at the same named in-memory database (jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1) with
// ddl-auto=create-drop, so closing one context DROPS THE SCHEMA the other, still-cached
// contexts are using. Isolation is done at the row level in cleanState(), which is
// order-independent and touches nobody else's schema.
@DisplayName("WIKI4AI-155: resources CRUD and readable reservations")
class ResourceReservationIntegrationTest {

    private static final String ADMIN = "res155_admin";
    private static final String OWNER = "res155_owner";
    private static final String STRANGER = "res155_stranger";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ResourceRepository resourceRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private WorkItemRepository workItemRepository;

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

    private Long agentActorId;
    private Long strangerAgentActorId;
    private Long projectId;
    private Long workItemId;

    // ── Rule 5b: this class writes users, actors, projects, work items, resources and
    //    reservations into the H2 database the whole test JVM shares. Left behind, its
    //    users break every test whose premise is "this instance has no accounts yet"
    //    (AuthFirstRunFlowTest above all). Cleaning BEFORE and AFTER every test, children
    //    first, makes the result independent of surefire's filesystem run order — and a
    //    fresh CI checkout does not list files in the order a local tree created them.

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        cleanState();

        saveUser(ADMIN, Role.ADMIN);
        saveUser(OWNER, Role.USER);
        saveUser(STRANGER, Role.USER);

        agentActorId = saveActor("res155-agent", Actor.KIND_AGENT, OWNER).getId();
        strangerAgentActorId = saveActor("res155-stranger-agent", Actor.KIND_AGENT, STRANGER).getId();

        Project project = Project.builder()
                .name("Res155 Project")
                .slug("res155-project")
                .visibility(Project.VISIBILITY_PUBLIC)
                .owner(userRepository.findByUsername(OWNER).orElseThrow())
                .build();
        projectId = projectRepository.save(project).getId();

        workItemId = workItemRepository.save(WorkItem.builder()
                .projectId(projectId)
                .title("reserve something")
                .status(WorkItem.STATUS_READY)
                .priority(WorkItem.DEFAULT_PRIORITY)
                .build()).getId();

        // Default caller: an ADMIN user acting through its own agent — allowed to write the
        // ledger and to hold capacity. Tests that need another caller install it explicitly.
        saveActor("res155-admin-agent", Actor.KIND_AGENT, ADMIN);
        authenticate(ADMIN, actorRepository.findByHandle("res155-admin-agent").orElseThrow().getId(),
                List.of("read", "write"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        cleanState();
    }

    /** Children before parents: reservations → comments → events → work items → project rows → tokens → actors → resources → projects → users. */
    private void cleanState() {
        jdbcTemplate.execute("DELETE FROM reservations");
        jdbcTemplate.execute("DELETE FROM work_item_comments");
        jdbcTemplate.execute("DELETE FROM work_item_events");
        workItemRepository.deleteAll();
        projectActorRepository.deleteAll();
        projectPermissionRepository.deleteAll();
        apiTokenRepository.deleteAll();
        actorRepository.deleteAll();
        jdbcTemplate.execute("DELETE FROM resources");
        projectRepository.deleteAll();
        for (String username : List.of(ADMIN, OWNER, STRANGER)) {
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

    private Long userIdOf(String username) {
        return userRepository.findByUsername(username).orElseThrow().getId();
    }

    /** Install an authentication exactly as the JWT filter does for a w4a_ token. */
    private void authenticate(String username, Long actorId, List<String> scopes) {
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                username, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        authentication.setDetails(new ApiTokenAuthenticationDetails(1L, userIdOf(username), actorId, scopes));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private void authenticate(String username, Long actorId) {
        authenticate(username, actorId, List.of("read", "write"));
    }

    /** Legacy JWT: authenticated, details null — the credential names no actor. */
    private void authenticateLegacy(String username) {
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                username, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        authentication.setDetails(null);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    /** Create a resource over the real HTTP endpoint; returns its key. */
    private String createResource(String key, int capacity) throws Exception {
        mockMvc.perform(post("/api/v1/resources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ResourceRequestDTO.builder()
                                .key(key)
                                .name("Resource " + key)
                                .capacity(capacity)
                                .build())))
                .andExpect(status().isCreated());
        return key;
    }

    /** Reserve over HTTP; returns the parsed body. */
    private MvcResult reserve(String key, String slotsBody) throws Exception {
        return mockMvc.perform(post("/api/v1/resources/" + key + "/reserve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(slotsBody))
                .andReturn();
    }

    private int activeSlots(String key) {
        return jdbcTemplate.queryForObject(
                "SELECT active_slots FROM resources WHERE \"key\" = ?", Integer.class, key);
    }

    // ── CRUD (AC: "CRUD resources funguje") ───────────────────────────────────

    @Nested
    @DisplayName("CRUD of resources")
    class Crud {

        @Test
        void createReturnsTheResourceWithItsCounterAtZero() throws Exception {
            mockMvc.perform(post("/api/v1/resources")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(ResourceRequestDTO.builder()
                                    .key("runner-a")
                                    .name("Build runner A")
                                    .kind("runner")
                                    .capacity(3)
                                    .metadata("{\"pool\":\"eu\"}")
                                    .build())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").isNumber())
                    .andExpect(jsonPath("$.key").value("runner-a"))
                    .andExpect(jsonPath("$.name").value("Build runner A"))
                    .andExpect(jsonPath("$.kind").value("runner"))
                    .andExpect(jsonPath("$.capacity").value(3))
                    .andExpect(jsonPath("$.activeSlots").value(0))
                    .andExpect(jsonPath("$.availableSlots").value(3))
                    .andExpect(jsonPath("$.enabled").value(true))
                    .andExpect(jsonPath("$.metadata").value("{\"pool\":\"eu\"}"))
                    .andExpect(jsonPath("$.createdAt").isNotEmpty());
        }

        @Test
        void defaultsApplyWhenTheBodyIsMinimal() throws Exception {
            mockMvc.perform(post("/api/v1/resources")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"key\":\"bare\",\"name\":\"Bare\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.kind").value(Resource.DEFAULT_KIND))
                    .andExpect(jsonPath("$.capacity").value(Resource.DEFAULT_CAPACITY))
                    .andExpect(jsonPath("$.activeSlots").value(0))
                    .andExpect(jsonPath("$.enabled").value(true));
        }

        @Test
        void listAndGetReturnTheResource() throws Exception {
            createResource("listed", 2);
            mockMvc.perform(get("/api/v1/resources/listed"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.key").value("listed"))
                    .andExpect(jsonPath("$.capacity").value(2));
            mockMvc.perform(get("/api/v1/resources"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.key == 'listed')]").exists());
        }

        @Test
        void updatePatchesNameKindMetadataAndRaisesCapacity() throws Exception {
            createResource("patched", 2);
            mockMvc.perform(put("/api/v1/resources/patched")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Renamed\",\"kind\":\"device\",\"capacity\":9}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("Renamed"))
                    .andExpect(jsonPath("$.kind").value("device"))
                    .andExpect(jsonPath("$.capacity").value(9));
        }

        @Test
        void deleteReturns204AndRemovesTheResource() throws Exception {
            createResource("doomed", 1);
            mockMvc.perform(delete("/api/v1/resources/doomed"))
                    .andExpect(status().isNoContent());
            mockMvc.perform(get("/api/v1/resources/doomed")).andExpect(status().isNotFound());
        }

        @Test
        void unknownKeyIs404OnEveryVerb() throws Exception {
            mockMvc.perform(get("/api/v1/resources/nope")).andExpect(status().isNotFound());
            mockMvc.perform(put("/api/v1/resources/nope")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"x\"}"))
                    .andExpect(status().isNotFound());
            mockMvc.perform(delete("/api/v1/resources/nope")).andExpect(status().isNotFound());
            mockMvc.perform(get("/api/v1/resources/nope/reservations")).andExpect(status().isNotFound());
            mockMvc.perform(post("/api/v1/resources/nope/reserve")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"slots\":1}"))
                    .andExpect(status().isNotFound());
        }

        @Test
        void duplicateKeyIs409LikeADuplicateActorHandle() throws Exception {
            createResource("taken", 1);
            mockMvc.perform(post("/api/v1/resources")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"key\":\"taken\",\"name\":\"Second\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("taken")));
        }

        @Test
        void renamingToAnExistingKeyIs409() throws Exception {
            createResource("first", 1);
            createResource("second", 1);
            mockMvc.perform(put("/api/v1/resources/second")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"key\":\"first\"}"))
                    .andExpect(status().isConflict());
        }
    }

    // ── Reservations are readable (AC: "reservations sa daju citat") ──────────

    @Nested
    @DisplayName("Reading reservations")
    class ReadReservations {

        @Test
        void reservationsOfAResourceAreListedByKeyWithTheirState() throws Exception {
            createResource("readable", 4);
            reserve("readable", "{\"slots\":2,\"reason\":\"build\"}")
                    .getResponse();
            mockMvc.perform(get("/api/v1/resources/readable/reservations"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].resourceKey").value("readable"))
                    .andExpect(jsonPath("$[0].slots").value(2))
                    .andExpect(jsonPath("$[0].reason").value("build"))
                    .andExpect(jsonPath("$[0].actorId").isNumber())
                    .andExpect(jsonPath("$[0].expiresAt").isNotEmpty())
                    .andExpect(jsonPath("$[0].releasedAt").doesNotExist());
        }

        @Test
        void anyAuthenticatedCallerMayReadThemIncludingAPlainViewer() throws Exception {
            createResource("open-ledger", 1);
            reserve("open-ledger", "{}").getResponse();

            // A worker-role actor of another user: no admin rights, still allowed to read.
            authenticate(STRANGER, strangerAgentActorId, List.of("read"));
            mockMvc.perform(get("/api/v1/resources/open-ledger/reservations"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].slots").value(1));
            mockMvc.perform(get("/api/v1/resources/open-ledger"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.activeSlots").value(1));
        }

        @Test
        void aReservationRecordsTheActorOfTheCredentialNotAnActorNamedInTheBody() throws Exception {
            createResource("attributed", 2);
            authenticate(OWNER, agentActorId);
            MvcResult result = reserve("attributed", "{\"slots\":1}");
            assertThat(result.getResponse().getStatus()).isEqualTo(201);
            Long recorded = objectMapper.readTree(result.getResponse().getContentAsString())
                    .get("actorId").asLong();
            assertThat(recorded).isEqualTo(agentActorId);
        }
    }

    // ── Access rules (decided once, in ResourceService) ───────────────────────

    @Nested
    @DisplayName("Access rules")
    class Access {

        @Test
        void aNonAdminUserWithoutAdminScopeCannotCreateAResource() throws Exception {
            authenticate(OWNER, agentActorId, List.of("read", "write"));
            mockMvc.perform(post("/api/v1/resources")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"key\":\"by-worker\",\"name\":\"Nope\"}"))
                    .andExpect(status().isForbidden());
            assertThat(resourceRepository.findByKey("by-worker")).isEmpty();
        }

        @Test
        void aNonAdminUserCannotUpdateOrDeleteAResource() throws Exception {
            createResource("guarded", 1);
            authenticate(OWNER, agentActorId, List.of("read", "write"));
            mockMvc.perform(put("/api/v1/resources/guarded")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"capacity\":5}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(delete("/api/v1/resources/guarded")).andExpect(status().isForbidden());
        }

        @Test
        void anAdminScopeTokenMayManageResourcesWithoutTheUserBeingAnAdmin() throws Exception {
            // The existing SCOPE_ADMIN primitive: ApiTokenService refuses it for agent-bound
            // tokens, so this is a human/service token — the ledger stays a human decision.
            authenticate(OWNER, null, List.of("read", "write", "admin"));
            mockMvc.perform(post("/api/v1/resources")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"key\":\"by-scope\",\"name\":\"Scoped\"}"))
                    .andExpect(status().isCreated());
            mockMvc.perform(delete("/api/v1/resources/by-scope")).andExpect(status().isNoContent());
        }

        @Test
        void aCredentialThatNamesNoActorCannotReserveBecauseAReservationIsPerActor() throws Exception {
            createResource("needs-actor", 2);
            // STRANGER has an agent actor but deliberately no kind='human' actor, so a legacy
            // JWT resolves to nobody.
            authenticateLegacy(STRANGER);
            mockMvc.perform(post("/api/v1/resources/needs-actor/reserve")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"slots\":1}"))
                    .andExpect(status().isForbidden());
            assertThat(activeSlots("needs-actor")).isZero();
        }

        @Test
        void anArchivedActorCannotReserve() throws Exception {
            createResource("archived-target", 2);
            Actor archived = actorRepository.findByHandle("res155-stranger-agent").orElseThrow();
            archived.setStatus(Actor.STATUS_ARCHIVED);
            actorRepository.save(archived);

            authenticate(STRANGER, strangerAgentActorId, List.of("read", "write"));
            mockMvc.perform(post("/api/v1/resources/archived-target/reserve")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"slots\":1}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        void reservingForAWorkItemOfAProjectTheActorCannotWriteIs403() throws Exception {
            createResource("for-item", 2);
            authenticate(STRANGER, strangerAgentActorId, List.of("read", "write"));
            mockMvc.perform(post("/api/v1/resources/for-item/reserve")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"slots\":1,\"workItemId\":" + workItemId + "}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        void reservingForAWorkItemOfAProjectTheOwnerWritesIsAccepted() throws Exception {
            createResource("for-item-ok", 2);
            authenticate(OWNER, agentActorId);
            mockMvc.perform(post("/api/v1/resources/for-item-ok/reserve")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"slots\":1,\"workItemId\":" + workItemId + "}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.workItemId").value(workItemId));
        }
    }

    // ── WIKI4AI-176: the wording of the three requireUsableActor refusals ──────

    /**
     * The access RULE of {@code requireUsableActor} was always right; its sentence was
     * not. The caller passed {@code action = "reserve capacity on"} and the shared
     * template appends {@code " capacity as an actor"}, which produced
     * "You can only reserve capacity on capacity as an actor" — the word
     * {@code capacity} twice, in a sentence that says nothing.
     * <p>
     * These tests hold the exact wording of all three branches, because the template is
     * shared by them: fixing one branch's action while leaving another's broken would
     * reproduce the same defect on a path nobody tested. The status is asserted too —
     * this story changes text only, so every one of them must still be 403.
     */
    @Nested
    @DisplayName("WIKI4AI-176: the 403 wording says 'capacity' once")
    class UsableActorMessage {

        @Test
        void aCredentialNamingNoActorIs403WithASentenceThatReadsAsEnglish() throws Exception {
            createResource("wording-no-actor", 2);
            authenticateLegacy(STRANGER);

            String message = reserveAndGetMessage("wording-no-actor", "{\"slots\":1}");

            assertThat(message)
                    .as("the exact sentence the caller of POST /resources/{key}/reserve reads")
                    .isEqualTo("You can only reserve capacity as an actor; your credential does not name one");
            assertNoDoubledWord(message);
            assertThat(activeSlots("wording-no-actor"))
                    .as("fixing the wording must not change the access rule")
                    .isZero();
        }

        @Test
        void anArchivedActorIs403WithASentenceThatReadsAsEnglish() throws Exception {
            createResource("wording-archived", 2);
            Actor archived = actorRepository.findByHandle("res155-stranger-agent").orElseThrow();
            archived.setStatus(Actor.STATUS_ARCHIVED);
            actorRepository.save(archived);

            authenticate(STRANGER, strangerAgentActorId, List.of("read", "write"));

            String message = reserveAndGetMessage("wording-archived", "{\"slots\":1}");

            assertThat(message)
                    .isEqualTo("You can only reserve capacity as an actor; actor 'res155-stranger-agent' is archived");
            assertNoDoubledWord(message);
        }

        @Test
        void anActorThatNoLongerExistsIs403WithASentenceThatReadsAsEnglish() throws Exception {
            createResource("wording-gone", 2);
            // A credential whose token still names an actor id that the registry no
            // longer has — the third branch of requireUsableActor.
            Long goneActorId = saveActor("res155-gone-agent", Actor.KIND_AGENT, STRANGER).getId();
            actorRepository.deleteById(goneActorId);
            actorRepository.flush();

            authenticate(STRANGER, goneActorId, List.of("read", "write"));

            String message = reserveAndGetMessage("wording-gone", "{\"slots\":1}");

            assertThat(message)
                    .isEqualTo("You can only reserve capacity as an actor; your actor no longer exists");
            assertNoDoubledWord(message);
            assertThat(activeSlots("wording-gone")).isZero();
        }

        /**
         * The defect of WIKI4AI-176 is one word appearing twice in a row, so the
         * regression guard is a scan for exactly that, not only the literal string:
         * a future {@code action} value that repeats a word of the template is caught
         * here even if the expected literal was updated along with it.
         */
        private void assertNoDoubledWord(String message) {
            assertThat(message).doesNotContain("capacity capacity");
            String[] words = message.toLowerCase(java.util.Locale.ROOT).split("\\s+");
            for (int i = 1; i < words.length; i++) {
                assertThat(words[i])
                        .as("word '%s' is repeated back-to-back in: %s", words[i], message)
                        .isNotEqualTo(words[i - 1]);
            }
        }

        private String reserveAndGetMessage(String key, String body) throws Exception {
            MvcResult result = reserve(key, body);
            assertThat(result.getResponse().getStatus())
                    .as("WIKI4AI-176 changes the text only; AccessDeniedException still maps to 403")
                    .isEqualTo(403);
            return objectMapper.readTree(result.getResponse().getContentAsString())
                    .get("message").asText();
        }
    }

    // ── Validation and the two different 409s ─────────────────────────────────

    @Nested
    @DisplayName("Validation and 409 wording")
    class Validation {

        @Test
        void slotsZeroIs400NamingSlots() throws Exception {
            createResource("slots-zero", 2);
            mockMvc.perform(post("/api/v1/resources/slots-zero/reserve")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"slots\":0}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("slots")));
        }

        @Test
        void slotsNegativeIs400NamingSlots() throws Exception {
            createResource("slots-negative", 2);
            mockMvc.perform(post("/api/v1/resources/slots-negative/reserve")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"slots\":-3}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("slots")));
        }

        @Test
        void slotsNotANumberIs400NamingSlotsAndNotA500() throws Exception {
            createResource("slots-text", 2);
            // The request DTO carries slots as a String precisely so this stays a 400:
            // GlobalExceptionHandler has no HttpMessageNotReadableException handler, so an
            // Integer field would have made this a 500 from the catch-all.
            mockMvc.perform(post("/api/v1/resources/slots-text/reserve")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"slots\":\"not-a-number\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("slots")));
        }

        @Test
        void capacityBiggerThanTheResourceIs409WithTheCapacityMessage() throws Exception {
            createResource("too-big", 2);
            mockMvc.perform(post("/api/v1/resources/too-big/reserve")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"slots\":5}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("capacity")));
        }

        @Test
        void aDisabledResourceRefusesReservationWithADisabledMessageThatDiffersFromCapacity() throws Exception {
            createResource("switched-off", 4);
            mockMvc.perform(put("/api/v1/resources/switched-off")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"enabled\":false}"))
                    .andExpect(status().isOk());

            String disabledMessage = mockMvc.perform(post("/api/v1/resources/switched-off/reserve")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"slots\":1}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("disabled")))
                    .andReturn().getResponse().getContentAsString();

            createResource("full", 1);
            reserve("full", "{\"slots\":1}").getResponse();
            String capacityMessage = mockMvc.perform(post("/api/v1/resources/full/reserve")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"slots\":1}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("capacity")))
                    .andReturn().getResponse().getContentAsString();

            // The AC is that these two answers are distinguishable, not both "no".
            assertThat(disabledMessage).isNotEqualTo(capacityMessage);
            assertThat(disabledMessage).contains("disabled").doesNotContain("Insufficient capacity");
            assertThat(capacityMessage).contains("Insufficient capacity").doesNotContain("is disabled");
        }

        @Test
        void disablingAResourceLeavesAlreadyHeldSlotsAccountedFor() throws Exception {
            createResource("disable-held", 2);
            reserve("disable-held", "{\"slots\":2}").getResponse();
            mockMvc.perform(put("/api/v1/resources/disable-held")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"enabled\":false}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.activeSlots").value(2));
            assertThat(activeSlots("disable-held")).isEqualTo(2);
        }

        @Test
        void capacityBelowOneIs400() throws Exception {
            mockMvc.perform(post("/api/v1/resources")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"key\":\"zero-cap\",\"name\":\"X\",\"capacity\":0}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void missingKeyOrNameIs400() throws Exception {
            mockMvc.perform(post("/api/v1/resources")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"No key\"}"))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(post("/api/v1/resources")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"key\":\"no-name\"}"))
                    .andExpect(status().isBadRequest());
        }
    }

    // ── DELETE while reservations are live (the documented decision) ──────────

    @Nested
    @DisplayName("Deleting a resource")
    class DeletePolicy {

        @Test
        void deletingAResourceWithActiveReservationsIsRefusedWith409() throws Exception {
            createResource("busy", 2);
            reserve("busy", "{\"slots\":1}").getResponse();

            mockMvc.perform(delete("/api/v1/resources/busy"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("reservation")));

            // Refused means refused: the row and its reservation are still there.
            assertThat(resourceRepository.findByKey("busy")).isPresent();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM reservations WHERE resource_id = (SELECT id FROM resources WHERE \"key\" = ?)",
                    Long.class, "busy")).isEqualTo(1L);
        }

        @Test
        void afterTheReservationIsReleasedTheDeleteSucceeds() throws Exception {
            createResource("busy-then-free", 2);
            MvcResult reservation = reserve("busy-then-free", "{\"slots\":1}");
            long reservationId = objectMapper.readTree(reservation.getResponse().getContentAsString())
                    .get("id").asLong();

            mockMvc.perform(post("/api/v1/reservations/" + reservationId + "/release"))
                    .andExpect(status().isNoContent());
            mockMvc.perform(delete("/api/v1/resources/busy-then-free"))
                    .andExpect(status().isNoContent());
            assertThat(resourceRepository.findByKey("busy-then-free")).isEmpty();
        }

        @Test
        void deletingAResourceWithOnlyReleasedHistoryIsAllowed() throws Exception {
            createResource("history", 1);
            MvcResult reservation = reserve("history", "{\"slots\":1}");
            long reservationId = objectMapper.readTree(reservation.getResponse().getContentAsString())
                    .get("id").asLong();
            jdbcTemplate.update("UPDATE reservations SET released_at = ? WHERE id = ?",
                    LocalDateTime.now(), reservationId);

            mockMvc.perform(delete("/api/v1/resources/history")).andExpect(status().isNoContent());
            // The service removes the released history itself rather than relying on the
            // cascade: the FK of V25 exists in PostgreSQL, but the test schema is built from
            // the entities, which reference each other by plain ids and generate no FK at all.
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reservations", Long.class)).isZero();
        }
    }

    // ── The shape of the API (no client-writable counter) ─────────────────────

    @Nested
    @DisplayName("The counter is not client-writable")
    class CounterShape {

        @Test
        void resourceEntityHasNoSetterForActiveSlots() {
            assertThat(java.util.Arrays.stream(Resource.class.getDeclaredMethods())
                    .map(java.lang.reflect.Method::getName))
                    .as("active_slots is a result of the reservation rows, not a writable field")
                    .doesNotContain("setActiveSlots");
        }

        @Test
        void resourceRequestDtoHasNoActiveSlotsField() {
            assertThat(java.util.Arrays.stream(ResourceRequestDTO.class.getDeclaredFields())
                    .map(java.lang.reflect.Field::getName))
                    .doesNotContain("activeSlots");
        }

        @Test
        void aPutCannotSetActiveSlotsEvenAsAnUnknownProperty() throws Exception {
            createResource("unwritable", 2);
            reserve("unwritable", "{\"slots\":1}").getResponse();
            mockMvc.perform(put("/api/v1/resources/unwritable")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"activeSlots\":0}"))
                    .andExpect(status().isOk());
            assertThat(activeSlots("unwritable")).isEqualTo(1);
        }

        @Test
        void noTimeFieldOfTheNewEntitiesIsAnInstant() {
            for (Class<?> type : List.of(Resource.class, Reservation.class)) {
                for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                    assertThat(field.getType())
                            .as("%s.%s must map a TIMESTAMP column", type.getSimpleName(), field.getName())
                            .isNotEqualTo(java.time.Instant.class);
                }
            }
        }

        @Test
        void aReservationIsRecordedAgainstTheWorkItemItNamedAndTheActorThatNamedIt() throws Exception {
            createResource("with-item", 3);
            authenticate(OWNER, agentActorId);
            MvcResult result = reserve("with-item",
                    "{\"slots\":1,\"workItemId\":" + workItemId + ",\"reason\":\"ci\"}");
            Map<String, Object> body = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
            assertThat(body.get("workItemId")).isEqualTo(workItemId.intValue());
            assertThat(((Number) body.get("actorId")).longValue()).isEqualTo(agentActorId);
            assertThat(reservationRepository.findById(((Number) body.get("id")).longValue()))
                    .get()
                    .hasFieldOrPropertyWithValue("reason", "ci");
        }
    }
}
