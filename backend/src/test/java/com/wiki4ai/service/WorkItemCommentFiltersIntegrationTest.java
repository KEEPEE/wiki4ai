package com.wiki4ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wiki4ai.config.JwtAuthenticationFilter.ApiTokenAuthenticationDetails;
import com.wiki4ai.dto.ProjectCreateDTO;
import com.wiki4ai.dto.WorkItemCommentDTO;
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
import com.wiki4ai.repository.WorkItemCommentRepository;
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
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.Query;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The two reads WIKI4AI-151 adds to the comment endpoint:
 * {@code GET /api/v1/work-items/{id}/comments?kind=decision} and
 * {@code ?needs_human=true|false}.
 *
 * <h2>{@code needs_human} is a state, never a notification</h2>
 * The whole story is a read. wiki4ai records that a blocker needs a human and makes that
 * findable; it never tells a human anything. There is no e-mail, no push, no scheduler
 * and no {@code @Scheduled} task behind this filter, and the last nested class of this
 * file asserts that instead of asserting it in prose: no scheduled-annotation processor
 * in the context, no {@code TaskScheduler} bean, no {@code @Scheduled} method and no
 * collaborator of a notification-shaped type in any class this story touches.
 *
 * <h2>No new column, no new table, no LIKE</h2>
 * {@code needs_human} is a key inside the JSON {@code payload} TEXT of a {@code blocker}
 * comment, guaranteed by the contract of WIKI4AI-149. The filter reads the item's
 * blockers through the indexed {@code (work_item_id, kind)} query of V22, decodes each
 * payload and matches the whole key — the same method WIKI4AI-144 used for {@code labels}
 * after it refused to pattern-match a TEXT column. {@code WorkItemCommentRepository}
 * declares no {@code @Query} at all, which is what "no LIKE" looks like in this codebase;
 * that is asserted below, not promised.
 *
 * <h2>Rule 5b</h2>
 * {@link #cleanState()} runs in {@code @BeforeEach} AND {@code @AfterEach}, children
 * before parents: {@code work_item_comments} → {@code work_item_events} → work items →
 * project_actors → project_permissions → api_tokens → actors → projects → users. No
 * {@code @DirtiesContext}.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@DisplayName("WIKI4AI-151: decision comments and blocker needs_human are queryable")
class WorkItemCommentFiltersIntegrationTest {

    private static final String OWNER = "wi151_owner";
    private static final String STRANGER = "wi151_stranger";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private WorkItemCommentService workItemCommentService;

    @Autowired
    private WorkItemRepository workItemRepository;

    @Autowired
    private WorkItemCommentRepository workItemCommentRepository;

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

    private Long agentActorId;
    private Long strangerActorId;
    private Long publicProjectId;
    private Long strangerProjectId;

    // ── Rule 5b ───────────────────────────────────────────────────────────────

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        cleanState();

        saveUser(OWNER, Role.USER);
        saveUser(STRANGER, Role.USER);

        agentActorId = saveActor("wi151-agent", OWNER).getId();
        strangerActorId = saveActor("wi151-stranger-agent", STRANGER).getId();

        publicProjectId = createProject("WI151 Public", Project.VISIBILITY_PUBLIC, OWNER);
        strangerProjectId = createProject("WI151 Stranger", Project.VISIBILITY_PUBLIC, STRANGER);

        authenticate(OWNER, agentActorId);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        cleanState();
    }

    /** work_item_comments → work_item_events → work_items → project_actors → project_permissions → api_tokens → actors → projects → users. */
    private void cleanState() {
        jdbcTemplate.execute("DELETE FROM work_item_comments");
        jdbcTemplate.execute("DELETE FROM work_item_events");
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

    private Actor saveActor(String handle, String ownerUsername) {
        Actor actor = new Actor();
        actor.setHandle(handle);
        actor.setKind(Actor.KIND_AGENT);
        actor.setStatus(Actor.STATUS_ACTIVE);
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

    private Long createItem() throws Exception {
        return createItem("WI151 item", publicProjectId);
    }

    private Map<String, Object> comment(String kind, String text, Map<String, Object> payload) {
        Map<String, Object> body = new HashMap<>();
        body.put("kind", kind);
        body.put("body", text);
        if (payload != null) {
            body.put("payload", payload);
        }
        return body;
    }

    private void postComment(Long id, Map<String, Object> body) throws Exception {
        mockMvc.perform(post("/api/v1/work-items/" + id + "/comments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());
    }

    private Map<String, Object> decision(String what, String why) {
        return comment("decision", what + " - " + why,
                Map.of("what", what, "why", why, "applies_to", "backend tests"));
    }

    private Map<String, Object> blocker(String text, Boolean needsHuman) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("blocking", text);
        payload.put("needs_human", needsHuman);
        return comment("blocker", text, payload);
    }

    private JsonNode getComments(Long id, String query) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/work-items/" + id + "/comments" + query))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /** The bodies of a page, in the order the page returned them. */
    private List<String> bodiesOf(JsonNode page) {
        List<String> bodies = new ArrayList<>();
        page.get("content").forEach(node -> bodies.add(node.get("body").asText()));
        return bodies;
    }

    private List<String> kindsOf(JsonNode page) {
        List<String> kinds = new ArrayList<>();
        page.get("content").forEach(node -> kinds.add(node.get("kind").asText()));
        return kinds;
    }

    /**
     * Write a comment row directly, past the API. Test fixture only: it is how a
     * {@code blocker} that carries no {@code needs_human} — which the contract of
     * WIKI4AI-149 makes unproducible through the API — is put in front of the filter.
     */
    private void insertCommentRow(Long itemId, String kind, String bodyText, String payloadJson) {
        jdbcTemplate.update("INSERT INTO work_item_comments (work_item_id, author_actor_id, kind, body,"
                        + " payload, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                itemId, agentActorId, kind, bodyText, payloadJson, LocalDateTime.now());
    }

    // ── AC 151: kind=decision ─────────────────────────────────────────────────

    @Nested
    @DisplayName("AC 151: ?kind=decision returns decision comments and nothing else")
    class DecisionFilter {

        @Test
        @DisplayName("only decisions, still chronological")
        void decisionFilterReturnsOnlyDecisions() throws Exception {
            Long id = createItem();
            postComment(id, comment("note", "a note", null));
            postComment(id, decision("we use H2 in tests", "speed"));
            postComment(id, blocker("no access", true));
            postComment(id, comment("status", "in progress", null));
            postComment(id, decision("comments are immutable", "the record must stay readable"));

            JsonNode page = getComments(id, "?kind=decision");

            assertThat(kindsOf(page)).containsExactly("decision", "decision");
            assertThat(bodiesOf(page))
                    .as("created_at ASC, id ASC — the same ordering the unfiltered read uses")
                    .containsExactly("we use H2 in tests - speed",
                            "comments are immutable - the record must stay readable");
            assertThat(page.get("totalElements").asLong()).isEqualTo(2);
        }

        @Test
        @DisplayName("the payload contract of decision is intact, so the comment is readable on its own")
        void decisionCarriesWhatWhyAppliesTo() throws Exception {
            Long id = createItem();
            postComment(id, decision("we use H2 in tests", "speed"));

            JsonNode only = getComments(id, "?kind=decision").get("content").get(0);

            assertThat(only.get("payload").get("what").asText()).isEqualTo("we use H2 in tests");
            assertThat(only.get("payload").get("why").asText()).isEqualTo("speed");
            assertThat(only.get("payload").get("applies_to").asText()).isEqualTo("backend tests");
            assertThat(only.get("authorActorId").asLong()).isEqualTo(agentActorId);
        }

        @Test
        @DisplayName("kind is normalized: ?kind=DECISION is ?kind=decision")
        void kindIsNormalized() throws Exception {
            Long id = createItem();
            postComment(id, decision("upper case", "the API normalizes"));

            assertThat(kindsOf(getComments(id, "?kind=DECISION"))).containsExactly("decision");
        }

        @Test
        @DisplayName("an unknown kind is 400, not an empty page")
        void unknownKindIs400() throws Exception {
            Long id = createItem();

            mockMvc.perform(get("/api/v1/work-items/" + id + "/comments?kind=nonsense"))
                    .andExpect(status().isBadRequest());
        }

        /**
         * "A decision is readable by another agent" — what AC 151 can prove before the
         * inbox of 152–154 exists. Reading comments requires a role in the project
         * ({@code requireCommentAccess} → 403 without one, the rule WIKI4AI-148 already
         * installed), so the other agent is a member of the project: an actor with the
         * {@code worker} role. It reads the decision through the same endpoint, with its
         * own credential, and gets the payload the decision carries.
         */
        @Test
        @DisplayName("a decision is readable by another project member with its own credential")
        void anotherActorReadsTheDecision() throws Exception {
            Long id = createItem();
            postComment(id, decision("we use H2 in tests", "speed"));

            projectActorRepository.save(com.wiki4ai.model.ProjectActor.builder()
                    .projectId(publicProjectId)
                    .actorId(strangerActorId)
                    .role(com.wiki4ai.model.ProjectActor.ROLE_WORKER)
                    .build());

            authenticate(STRANGER, strangerActorId);
            JsonNode page = getComments(id, "?kind=decision");

            assertThat(bodiesOf(page)).containsExactly("we use H2 in tests - speed");
            assertThat(page.get("content").get(0).get("authorActorId").asLong()).isEqualTo(agentActorId);
        }

        @Test
        @DisplayName("an actor with no role in the project is 403 — the read is not open to the world")
        void actorWithoutARoleIs403() throws Exception {
            Long id = createItem();
            postComment(id, decision("we use H2 in tests", "speed"));

            authenticate(STRANGER, strangerActorId);
            mockMvc.perform(get("/api/v1/work-items/" + id + "/comments?kind=decision"))
                    .andExpect(status().isForbidden());
        }
    }

    // ── AC 151: needs_human ───────────────────────────────────────────────────

    @Nested
    @DisplayName("AC 151: ?needs_human filters blocker comments by their payload")
    class NeedsHumanFilter {

        @Test
        @DisplayName("?needs_human=true returns only blockers whose needs_human is true")
        void needsHumanTrueReturnsOnlyTrueBlockers() throws Exception {
            Long id = createItem();
            postComment(id, blocker("needs credentials a human holds", true));
            postComment(id, blocker("waiting on another agent", false));
            postComment(id, comment("note", "not a blocker", null));
            postComment(id, comment("result", "done", Map.of(
                    "where", "main", "next", "release", "artifacts", List.of("x"))));

            JsonNode page = getComments(id, "?needs_human=true");

            assertThat(kindsOf(page))
                    .as("the filter is a blocker filter: no other kind can carry the field")
                    .containsExactly("blocker");
            assertThat(bodiesOf(page)).containsExactly("needs credentials a human holds");
            assertThat(page.get("content").get(0).get("payload").get("needs_human").asBoolean()).isTrue();
            assertThat(page.get("totalElements").asLong()).isEqualTo(1);
        }

        @Test
        @DisplayName("?needs_human=false returns only blockers whose needs_human is false")
        void needsHumanFalseReturnsOnlyFalseBlockers() throws Exception {
            Long id = createItem();
            postComment(id, blocker("needs credentials a human holds", true));
            postComment(id, blocker("waiting on another agent", false));
            postComment(id, blocker("waiting on a build", false));

            JsonNode page = getComments(id, "?needs_human=false");

            assertThat(kindsOf(page)).containsExactly("blocker", "blocker");
            assertThat(bodiesOf(page)).containsExactly("waiting on another agent", "waiting on a build");
            assertThat(page.get("totalElements").asLong()).isEqualTo(2);
        }

        @Test
        @DisplayName("absent or blank needs_human is no filter at all")
        void absentOrBlankIsNoFilter() throws Exception {
            Long id = createItem();
            postComment(id, blocker("needs a human", true));
            postComment(id, blocker("waiting", false));

            assertThat(getComments(id, "").get("totalElements").asLong()).isEqualTo(2);
            assertThat(getComments(id, "?needs_human=").get("totalElements").asLong()).isEqualTo(2);
        }

        @Test
        @DisplayName("an invalid value is 400 naming the parameter the client typed")
        void invalidValueIs400() throws Exception {
            Long id = createItem();
            postComment(id, blocker("needs a human", true));

            MvcResult result = mockMvc.perform(get("/api/v1/work-items/" + id + "/comments?needs_human=xyz"))
                    .andExpect(status().isBadRequest())
                    .andReturn();

            assertThat(objectMapper.readTree(result.getResponse().getContentAsString())
                    .get("message").asText())
                    .contains("needs_human")
                    .contains("true, false");
        }

        @Test
        @DisplayName("a blocker with no needs_human in its payload matches neither filter")
        void blockerWithoutTheFieldMatchesNeitherFilter() throws Exception {
            Long id = createItem();
            // Past the API on purpose: WIKI4AI-149 makes this unproducible through
            // POST, so the only way to put it in front of the filter is to write the row.
            insertCommentRow(id, "blocker", "written before the contract existed",
                    "{\"blocking\":\"legacy row\"}");
            postComment(id, blocker("needs a human", true));
            postComment(id, blocker("waiting", false));

            assertThat(bodiesOf(getComments(id, "?needs_human=true")))
                    .as("absence is not true")
                    .containsExactly("needs a human");
            assertThat(bodiesOf(getComments(id, "?needs_human=false")))
                    .as("absence is not false either")
                    .containsExactly("waiting");
            assertThat(getComments(id, "").get("totalElements").asLong())
                    .as("and it is still visible when nothing is filtered")
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("a needs_human that is not a JSON boolean matches neither filter")
        void nonBooleanValueMatchesNeitherFilter() throws Exception {
            Long id = createItem();
            insertCommentRow(id, "blocker", "needs_human as a string",
                    "{\"blocking\":\"x\",\"needs_human\":\"true\"}");
            insertCommentRow(id, "blocker", "needs_human as a number",
                    "{\"blocking\":\"y\",\"needs_human\":1}");
            insertCommentRow(id, "blocker", "no payload at all", null);
            postComment(id, blocker("a real one", true));

            assertThat(bodiesOf(getComments(id, "?needs_human=true"))).containsExactly("a real one");
            assertThat(getComments(id, "?needs_human=false").get("totalElements").asLong()).isZero();
        }

        @Test
        @DisplayName("the filter paginates: page/size apply to the filtered set, not to the raw one")
        void filterPaginatesOverTheFilteredSet() throws Exception {
            Long id = createItem();
            for (int i = 1; i <= 5; i++) {
                postComment(id, blocker("blocker " + i, true));
            }
            postComment(id, blocker("not for a human", false));

            JsonNode first = getComments(id, "?needs_human=true&page=0&size=2");
            JsonNode second = getComments(id, "?needs_human=true&page=1&size=2");
            JsonNode third = getComments(id, "?needs_human=true&page=2&size=2");

            assertThat(bodiesOf(first)).containsExactly("blocker 1", "blocker 2");
            assertThat(bodiesOf(second)).containsExactly("blocker 3", "blocker 4");
            assertThat(bodiesOf(third)).containsExactly("blocker 5");
            assertThat(first.get("totalElements").asLong())
                    .as("total is the size of the filtered set, so the client can tell when to stop")
                    .isEqualTo(5);
            assertThat(first.get("totalPages").asInt()).isEqualTo(3);
        }

        @Test
        @DisplayName("the service applies the filter too — it is not a controller-only behaviour")
        void serviceAppliesTheFilterDirectly() throws Exception {
            Long id = createItem();
            postComment(id, blocker("needs a human", true));
            postComment(id, blocker("waiting", false));

            Page<WorkItemCommentDTO> only = workItemCommentService.list(id, null, "true", 0, 50, OWNER);

            assertThat(only.getContent())
                    .as("same rules, no MockMvc in this assertion")
                    .extracting(WorkItemCommentDTO::getBody)
                    .containsExactly("needs a human");
            assertThat(workItemCommentService.list(id, null, "false", 0, 50, OWNER).getContent())
                    .extracting(WorkItemCommentDTO::getBody)
                    .containsExactly("waiting");
        }
    }

    // ── AC 151: combining the filters ─────────────────────────────────────────

    @Nested
    @DisplayName("AC 151: kind and needs_human together")
    class CombinedFilters {

        @Test
        @DisplayName("kind=blocker&needs_human=true works as the narrowest blocker read")
        void blockerKindWithNeedsHuman() throws Exception {
            Long id = createItem();
            postComment(id, blocker("needs a human", true));
            postComment(id, blocker("waiting", false));
            postComment(id, comment("note", "not a blocker", null));

            JsonNode page = getComments(id, "?kind=blocker&needs_human=true");

            assertThat(kindsOf(page)).containsExactly("blocker");
            assertThat(bodiesOf(page)).containsExactly("needs a human");
        }

        /**
         * The combination the AC asks to define. {@code needs_human} is a field of the
         * {@code blocker} contract and of no other kind, so no comment can be a decision
         * and carry it. The answer is a defined one — an empty page, 200,
         * {@code totalElements = 0} — not a 400: the request is well formed, it simply
         * asks for the intersection of two filters that never overlap. Making it 400
         * would call a valid question malformed.
         */
        @Test
        @DisplayName("kind=decision&needs_human=true is an empty page, not an error")
        void decisionKindWithNeedsHumanIsEmpty() throws Exception {
            Long id = createItem();
            postComment(id, decision("we use H2 in tests", "speed"));
            postComment(id, blocker("needs a human", true));

            JsonNode page = getComments(id, "?kind=decision&needs_human=true");

            assertThat(page.get("content")).isEmpty();
            assertThat(page.get("totalElements").asLong()).isZero();
            assertThat(page.get("number").asInt()).isZero();
        }

        @Test
        @DisplayName("kind=note&needs_human=false is an empty page for the same reason")
        void otherKindWithNeedsHumanIsEmpty() throws Exception {
            Long id = createItem();
            postComment(id, comment("note", "a note", null));
            postComment(id, blocker("waiting", false));

            assertThat(getComments(id, "?kind=note&needs_human=false").get("totalElements").asLong()).isZero();
        }

        @Test
        @DisplayName("an invalid needs_human is 400 even together with a valid kind")
        void invalidValueWithKindIs400() throws Exception {
            Long id = createItem();

            mockMvc.perform(get("/api/v1/work-items/" + id + "/comments?kind=blocker&needs_human=yes"))
                    .andExpect(status().isBadRequest());
        }
    }

    // ── The proofs the story asked for ────────────────────────────────────────

    @Nested
    @DisplayName("Proofs: no LIKE, no new column, no scheduler, no notification")
    class Proofs {

        /**
         * "The filter does not pattern-match the payload TEXT" is checkable: every read
         * of {@code work_item_comments} in this application is a Spring Data derived
         * query, so the interface carries no {@code @Query} at all — and a derived query
         * on a JSON TEXT payload with {@code LIKE} in it would have to be a {@code @Query}
         * or a {@code ...Like}/{@code ...Containing} method name. Both are asserted absent.
         */
        @Test
        @DisplayName("WorkItemCommentRepository declares no @Query and no LIKE-shaped method")
        void commentRepositoryHasNoPatternMatchingQuery() {
            List<String> queryAnnotated = new ArrayList<>();
            List<String> likeShapedNames = new ArrayList<>();
            for (Method method : WorkItemCommentRepository.class.getDeclaredMethods()) {
                Query query = method.getAnnotation(Query.class);
                if (query != null) {
                    queryAnnotated.add(method.getName() + ": " + query.value());
                }
                String name = method.getName().toLowerCase(java.util.Locale.ROOT);
                if (name.contains("like") || name.contains("containing") || name.contains("starting")
                        || name.contains("payload")) {
                    likeShapedNames.add(method.getName());
                }
            }

            assertThat(queryAnnotated)
                    .as("no hand-written SQL here means no LIKE in hand-written SQL")
                    .isEmpty();
            assertThat(likeShapedNames)
                    .as("no derived query named Like/Containing either")
                    .isEmpty();
        }

        /**
         * The service that implements the filter holds no JDBC or EntityManager handle,
         * so it has no way to issue a LIKE even by accident: the only database access it
         * has is the repository's derived queries.
         */
        @Test
        @DisplayName("WorkItemCommentService holds no JDBC handle to pattern-match with")
        void commentServiceHasNoJdbcHandle() {
            List<String> jdbcFields = new ArrayList<>();
            for (Field field : WorkItemCommentService.class.getDeclaredFields()) {
                String type = field.getType().getName();
                if (type.startsWith("org.springframework.jdbc") || type.startsWith("jakarta.persistence")
                        || type.startsWith("javax.persistence")) {
                    jdbcFields.add(field.getName() + " : " + type);
                }
            }
            assertThat(jdbcFields).isEmpty();
        }

        /**
         * V22 has no {@code needs_human} column and WIKI4AI-151 adds none: the filter
         * reads the payload. Asserted against the live test schema Hibernate built from
         * the entity, so it is the shape the postgres profile will validate too.
         */
        @Test
        @DisplayName("no new column and no new table: the schema has no needs_human column")
        void noNewColumnOrTable() {
            List<String> entityFields = new ArrayList<>();
            for (Field field : com.wiki4ai.model.WorkItemComment.class.getDeclaredFields()) {
                if (field.getName().toLowerCase(java.util.Locale.ROOT).contains("needshuman")) {
                    entityFields.add(field.getName());
                }
            }
            assertThat(entityFields)
                    .as("needs_human lives in the payload JSON, exactly where the contract of 149 put it")
                    .isEmpty();

            List<String> columns = jdbcTemplate.queryForList(
                    "SELECT upper(column_name) FROM information_schema.columns"
                            + " WHERE upper(table_name) = 'WORK_ITEM_COMMENTS'",
                    String.class);

            assertThat(columns)
                    .as("the table V22 created is the table 151 reads — no column was added")
                    .contains("PAYLOAD", "KIND", "BODY", "WORK_ITEM_ID");
            assertThat(columns)
                    .as("no needs_human column: filtering it by column would have needed a migration")
                    .noneMatch(column -> column.contains("NEEDS_HUMAN"));
        }

        /**
         * AC 151: {@code needs_human=true} is a state an agent records, and the only
         * thing wiki4ai does with it is store it and let somebody find it. No timer, no
         * notification, nothing that reaches out to a human.
         */
        @Test
        @DisplayName("no scheduler in the context: nothing runs on its own")
        void noSchedulerRuns() {
            assertThat(applicationContext.containsBean(
                    "org.springframework.context.annotation.internalScheduledAnnotationProcessor"))
                    .as("the bean @EnableScheduling installs — its absence means no @Scheduled task is registered")
                    .isFalse();
            assertThat(applicationContext.containsBean("taskScheduler"))
                    .as("no scheduler bean either")
                    .isFalse();
        }

        @Test
        @DisplayName("no @Scheduled method and no notification-shaped collaborator in the classes of this story")
        void storyClassesScheduleAndNotifyNothing() {
            List<Class<?>> storyClasses = List.of(
                    WorkItemService.class,
                    WorkItemCommentService.class,
                    com.wiki4ai.controller.WorkItemCommentController.class,
                    com.wiki4ai.controller.WorkItemController.class);

            List<String> scheduled = new ArrayList<>();
            List<String> notifying = new ArrayList<>();
            for (Class<?> type : storyClasses) {
                for (Method method : type.getDeclaredMethods()) {
                    if (method.isAnnotationPresent(Scheduled.class)) {
                        scheduled.add(type.getSimpleName() + "." + method.getName());
                    }
                }
                for (Field field : type.getDeclaredFields()) {
                    String name = field.getType().getSimpleName();
                    if (name.contains("Mail") || name.contains("Email") || name.contains("Notifier")
                            || name.contains("Notification") || name.contains("Scheduler")
                            || name.contains("Push") || name.contains("Webhook")) {
                        notifying.add(type.getSimpleName() + "." + field.getName() + " : " + name);
                    }
                }
            }

            assertThat(scheduled).as("no @Scheduled anywhere in WIKI4AI-150 or WIKI4AI-151").isEmpty();
            assertThat(notifying).as("no notification collaborator: wiki4ai never pokes a human").isEmpty();
        }

        @Test
        @DisplayName("the test profile keeps scheduling off, exactly as the lease story established")
        void schedulingIsOffInTests() {
            assertThat(applicationContext.containsBean(
                    "org.springframework.context.annotation.internalScheduledAnnotationProcessor")).isFalse();
            assertThat(applicationContext.getBeanNamesForAnnotation(Scheduled.class)).isEmpty();
        }
    }
}
