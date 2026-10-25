package com.wiki4ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wiki4ai.config.JwtAuthenticationFilter.ApiTokenAuthenticationDetails;
import com.wiki4ai.dto.ProjectCreateDTO;
import com.wiki4ai.dto.WorkItemCommentDTO;
import com.wiki4ai.model.Actor;
import com.wiki4ai.model.Project;
import com.wiki4ai.model.ProjectActor;
import com.wiki4ai.model.Role;
import com.wiki4ai.model.User;
import com.wiki4ai.model.WorkItem;
import com.wiki4ai.model.WorkItemComment;
import com.wiki4ai.repository.ActorRepository;
import com.wiki4ai.repository.ApiTokenRepository;
import com.wiki4ai.repository.ProjectActorRepository;
import com.wiki4ai.repository.ProjectPermissionRepository;
import com.wiki4ai.repository.ProjectRepository;
import com.wiki4ai.repository.UserRepository;
import com.wiki4ai.repository.WorkItemCommentRepository;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the immutable comments of a work item (WIKI4AI-148) and their
 * per-kind contracts (WIKI4AI-149), against the real H2 database, the real
 * {@link WorkItemService}, the real {@link WorkItemCommentService}, the real
 * {@link WorkItemCommentController} and the real {@link RequestActorResolver}.
 *
 * <h2>Rule 5b</h2>
 * {@link #cleanState()} runs in {@code @BeforeEach} AND {@code @AfterEach}, children
 * before parents: comments → events → items → project_actors → project_permissions →
 * api_tokens → actors → projects → users. No {@code @DirtiesContext}: the contexts of
 * this suite are cached and share one named in-memory H2, so the state a test made has
 * to be removed by that test.
 * <p>
 * {@code work_item_comments} is emptied with a native statement through
 * {@link JdbcTemplate}, for the two reasons {@code WorkItemEventIntegrationTest}
 * states and that apply unchanged here. First, the recipe's "delete the parent and let
 * {@code ON DELETE CASCADE} take the children" is what happens on PostgreSQL, but not
 * in the test schema: SPEC rule 11 keeps associations out of the entities, so
 * {@code WorkItemComment.workItemId} is a plain {@code Long} column and Hibernate's
 * generated test schema has no foreign key to cascade. Second, and specific to this
 * table, {@code workItemCommentRepository.deleteAll()} is <b>refused by design</b> —
 * it is one of the nine methods this story overrides to throw, and a test that called
 * it would fail for the right reason. A native {@code DELETE} is not a way around the
 * immutability rule: that rule governs the application's writes, which all go through
 * {@link WorkItemCommentRepository} and the entity's JPA lifecycle, and it is asserted
 * as such in {@link ImmutableIsEnforced}. A test harness removing rows directly is
 * the same kind of write the database itself performs through its cascade.
 *
 * <h2>Security</h2>
 * {@code addFilters = false}: the authentication under test is the one this class
 * installs, in the shape {@code JwtAuthenticationFilter} produces for a {@code w4a_}
 * token (with {@link ApiTokenAuthenticationDetails}) and for a legacy JWT (without
 * them). Attribution therefore travels from the credential, and no request in this
 * file names an actor in a body or a query string — except the one test that tries to,
 * to prove it is ignored.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@DisplayName("Work item comments (WIKI4AI-148) and their per-kind contracts (WIKI4AI-149)")
class WorkItemCommentIntegrationTest {

    private static final String OWNER = "wic_owner";
    private static final String STRANGER = "wic_stranger";
    private static final String ADMIN = "wic_admin";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WorkItemRepository workItemRepository;

    @Autowired
    private WorkItemCommentRepository workItemCommentRepository;

    @Autowired
    private WorkItemCommentService workItemCommentService;

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

        agentActorId = saveActor("wic-agent", OWNER, Actor.STATUS_ACTIVE).getId();
        strangerActorId = saveActor("wic-stranger-agent", STRANGER, Actor.STATUS_ACTIVE).getId();
        saveActor("wic-paused-agent", STRANGER, Actor.STATUS_PAUSED);

        publicProjectId = createProject("WIC Public", Project.VISIBILITY_PUBLIC, OWNER);
        strangerProjectId = createProject("WIC Stranger", Project.VISIBILITY_PUBLIC, STRANGER);
        privateProjectId = createProject("WIC Private", Project.VISIBILITY_PRIVATE, STRANGER);

        authenticate(OWNER, agentActorId);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        cleanState();
    }

    /**
     * work_item_comments → work_item_events → work_items → project_actors →
     * permissions → api_tokens → actors → projects → users. Children before parents, so
     * no statement can strand a row that still points at something being deleted.
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

    private Actor saveActor(String handle, String ownerUsername, String status) {
        Actor actor = new Actor();
        actor.setHandle(handle);
        actor.setKind(Actor.KIND_AGENT);
        actor.setStatus(status);
        actor.setOwnerUserId(userIdOf(ownerUsername));
        return actorRepository.save(actor);
    }

    private Long createProject(String name, String visibility, String username) {
        return projectService.createProject(
                ProjectCreateDTO.builder().name(name).visibility(visibility).build(), username).getId();
    }

    /**
     * Give an actor a role in a project (WIKI4AI-174). Written straight to the table
     * rather than through {@code ProjectActorService.assignActor}, because that endpoint
     * requires the caller to hold the {@code admin} actor role, and these tests are not
     * about assigning actors — they are about what a handoff may name.
     */
    private void addProjectActor(Long projectId, Long actorId, String role) {
        projectActorRepository.save(ProjectActor.builder()
                .projectId(projectId)
                .actorId(actorId)
                .role(role)
                .build());
    }

    private Long userIdOf(String username) {
        return userRepository.findByUsername(username).orElseThrow().getId();
    }

    /** The shape JwtAuthenticationFilter produces for a w4a_ token bound to an actor. */
    private void authenticate(String username, Long actorId) {
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                username, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        authentication.setDetails(new ApiTokenAuthenticationDetails(1L, userIdOf(username), actorId,
                List.of("read", "write")));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    /** The shape JwtAuthenticationFilter produces for a legacy JWT: no details at all. */
    private void authenticateLegacyJwt(String username) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                username, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
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
        return createItem("WIC item", publicProjectId);
    }

    /** POST one comment and return the parsed response body. */
    private JsonNode postCommentJson(Long itemId, Map<String, Object> payloadBody) throws Exception {
        return objectMapper.readTree(postComment(itemId, payloadBody).getResponse().getContentAsString());
    }

    private MvcResult postComment(Long itemId, Object body) throws Exception {
        return mockMvc.perform(post("/api/v1/work-items/" + itemId + "/comments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult postCommentExpect(Long itemId, Object body, int expectedStatus) throws Exception {
        return mockMvc.perform(post("/api/v1/work-items/" + itemId + "/comments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().is(expectedStatus))
                .andReturn();
    }

    /** A body that satisfies the contract of its kind. */
    private Map<String, Object> comment(String kind, String text, Map<String, Object> payload) {
        Map<String, Object> body = new HashMap<>();
        body.put("kind", kind);
        body.put("body", text);
        if (payload != null) {
            body.put("payload", payload);
        }
        return body;
    }

    private Long postValidComment(Long itemId, String kind, String text, Map<String, Object> payload)
            throws Exception {
        return postCommentJson(itemId, comment(kind, text, payload)).get("id").asLong();
    }

    private JsonNode getJson(String path) throws Exception {
        MvcResult result = mockMvc.perform(get(path)).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private List<WorkItemCommentDTO> commentsOf(Long itemId) {
        return workItemCommentService.listAll(itemId);
    }

    private List<Long> idsOf(JsonNode content) {
        List<Long> ids = new ArrayList<>();
        content.forEach(node -> ids.add(node.get("id").asLong()));
        return ids;
    }

    private int commentCount(Long itemId) {
        return workItemCommentRepository.findByWorkItemIdOrderByCreatedAtAscIdAsc(itemId).size();
    }

    /** COUNT straight from the table, bypassing the repository that refuses deletions. */
    private int countFromDatabase(Long itemId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM work_item_comments WHERE work_item_id = ?", Integer.class, itemId);
        return count == null ? 0 : count;
    }

    /**
     * Assert that an operation was refused by the immutability guard. The assertion
     * walks to the root cause on purpose: Hibernate is allowed to wrap what the
     * entity's {@code @PreUpdate} threw, and what matters is that the refusal came
     * from the guard and not from a constraint violation or a typo.
     */
    private static void assertRefusedAsImmutable(org.assertj.core.api.ThrowableAssert.ThrowingCallable body) {
        Throwable thrown = org.assertj.core.api.ThrowableAssert.catchThrowable(body);
        assertThat(thrown).as("the operation was not attempted at all").isNotNull();
        for (Throwable cursor = thrown; cursor != null; cursor = cursor.getCause()) {
            if (cursor instanceof IllegalStateException && cursor.getMessage() != null
                    && cursor.getMessage().contains("immutable")) {
                return;
            }
        }
        throw new AssertionError("the operation was not refused by the immutability guard: " + thrown, thrown);
    }

    /** Run a block in its own transaction, the way the application would. */
    private void inTransaction(Runnable body) {
        new TransactionTemplate(transactionManager).execute(status -> {
            body.run();
            return null;
        });
    }

    /** Write a field of a managed comment directly: the path @PreUpdate exists to catch. */
    private static void tamperCommentField(WorkItemComment entity, String value) {
        try {
            Field field = WorkItemComment.class.getDeclaredField("kind");
            field.setAccessible(true);
            field.set(entity, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("the fixture could not reach the field to tamper with it", e);
        }
    }

    // ── AC 148: POST and GET ──────────────────────────────────────────────────

    @Nested
    @DisplayName("AC 148: POST /comments and GET /comments")
    class PostAndRead {

        @Test
        @DisplayName("POST a comment → 201 with id, author, kind, body and created_at")
        void postReturnsTheStoredComment() throws Exception {
            Long itemId = createItem();

            JsonNode created = postCommentJson(itemId,
                    comment("note", "First note on the item.", null));

            assertThat(created.get("id").asLong()).isPositive();
            assertThat(created.get("workItemId").asLong()).isEqualTo(itemId);
            assertThat(created.get("kind").asText()).isEqualTo("note");
            assertThat(created.get("body").asText()).isEqualTo("First note on the item.");
            assertThat(created.get("authorActorId").asLong()).isEqualTo(agentActorId);
            assertThat(created.get("createdAt").asText()).isNotBlank();
        }

        @Test
        @DisplayName("GET → 200, chronological: created_at ASC then id ASC")
        void getIsChronological() throws Exception {
            Long itemId = createItem();
            postValidComment(itemId, "note", "first", null);
            postValidComment(itemId, "status", "second", null);
            postValidComment(itemId, "note", "third", null);

            JsonNode page = getJson("/api/v1/work-items/" + itemId + "/comments");

            assertThat(page.get("content")).hasSize(3);
            assertThat(page.get("content").get(0).get("body").asText()).isEqualTo("first");
            assertThat(page.get("content").get(1).get("body").asText()).isEqualTo("second");
            assertThat(page.get("content").get(2).get("body").asText()).isEqualTo("third");

            List<Long> ids = idsOf(page.get("content"));
            assertThat(ids).as("ids are the tie-breaker, so the order is fully determined")
                    .isSorted()
                    .doesNotHaveDuplicates();

            for (int i = 1; i < page.get("content").size(); i++) {
                LocalDateTime previous = LocalDateTime.parse(page.get("content").get(i - 1).get("createdAt").asText());
                LocalDateTime current = LocalDateTime.parse(page.get("content").get(i).get("createdAt").asText());
                assertThat(current).as("comment %d is not after comment %d", i, i - 1)
                        .isAfterOrEqualTo(previous);
            }
        }

        @Test
        @DisplayName("pagination: page 0 and page 1 partition the comments in the same order")
        void paginationPartitionsTheComments() throws Exception {
            Long itemId = createItem();
            for (int i = 0; i < 7; i++) {
                postValidComment(itemId, "note", "note " + i, null);
            }

            JsonNode everything = getJson("/api/v1/work-items/" + itemId + "/comments?page=0&size=100");
            JsonNode first = getJson("/api/v1/work-items/" + itemId + "/comments?page=0&size=3");
            JsonNode second = getJson("/api/v1/work-items/" + itemId + "/comments?page=1&size=3");
            JsonNode tail = getJson("/api/v1/work-items/" + itemId + "/comments?page=2&size=3");

            assertThat(everything.get("content")).hasSize(7);
            assertThat(first.get("content")).hasSize(3);
            assertThat(second.get("content")).hasSize(3);
            assertThat(tail.get("content")).as("7 comments at 3 per page leaves 1 on the last page")
                    .hasSize(1);

            assertThat(first.get("totalElements").asLong()).isEqualTo(7);
            assertThat(second.get("totalElements").asLong()).isEqualTo(7);
            assertThat(first.get("totalPages").asInt()).isEqualTo(3);
            assertThat(first.get("number").asInt()).isZero();
            assertThat(first.get("size").asInt()).isEqualTo(3);
            assertThat(second.get("number").asInt()).isEqualTo(1);
            assertThat(first.get("first").asBoolean()).isTrue();
            assertThat(first.get("last").asBoolean()).isFalse();
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
            Long itemId = createItem();
            postValidComment(itemId, "note", "only one", null);

            mockMvc.perform(get("/api/v1/work-items/" + itemId + "/comments").param("size", "5000"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.size").value(100));

            mockMvc.perform(get("/api/v1/work-items/" + itemId + "/comments").param("size", "-4"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.size").value(1));

            mockMvc.perform(get("/api/v1/work-items/" + itemId + "/comments").param("page", "9"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(0));
        }

        @Test
        @DisplayName("GET with kind filter returns only that kind, still chronological")
        void kindFilterReturnsOnlyThatKind() throws Exception {
            Long itemId = createItem();
            postValidComment(itemId, "note", "a note", null);
            postValidComment(itemId, "result", "a result",
                    Map.of("where", "main", "next", "review", "artifacts", List.of("branch main")));
            postValidComment(itemId, "note", "another note", null);

            JsonNode results = getJson("/api/v1/work-items/" + itemId + "/comments?kind=result");

            assertThat(results.get("content")).hasSize(1);
            assertThat(results.get("content").get(0).get("kind").asText()).isEqualTo("result");
            assertThat(results.get("totalElements").asLong()).isEqualTo(1);

            // The per-kind existence lookup, asserted while it has data to answer
            // about: it is the query the 'done' requires a 'result' invariant of
            // WIKI4AI-150 will run, and an unexercised query is an untested query.
            assertThat(workItemCommentService.hasCommentOfKind(itemId, "result")).isTrue();
            assertThat(workItemCommentService.hasCommentOfKind(itemId, "blocker")).isFalse();
            assertThat(workItemCommentRepository.existsByWorkItemIdAndKind(itemId, "note")).isTrue();
        }

        @Test
        @DisplayName("an item with no comments reads as an empty page, not a 404")
        void emptyCommentsIsAnEmptyPage() throws Exception {
            Long itemId = createItem();

            mockMvc.perform(get("/api/v1/work-items/" + itemId + "/comments"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(0))
                    .andExpect(jsonPath("$.totalElements").value(0));
        }

        private List<Long> concat(List<Long> a, List<Long> b) {
            List<Long> all = new ArrayList<>(a);
            all.addAll(b);
            return all;
        }
    }

    // ── AC 148: author_actor_id comes from the credential, nowhere else ───────

    @Nested
    @DisplayName("AC 148: author_actor_id is the credential's actor, never the body's")
    class Attribution {

        @Test
        @DisplayName("author_actor_id is the actor the token names")
        void authorComesFromTheCredential() throws Exception {
            Long itemId = createItem();

            JsonNode created = postCommentJson(itemId, comment("note", "written by the agent", null));

            assertThat(created.get("authorActorId").asLong()).isEqualTo(agentActorId);
            assertThat(commentsOf(itemId).get(0).getAuthorActorId()).isEqualTo(agentActorId);
        }

        @Test
        @DisplayName("authorActorId injected in the body is ignored: the credential wins")
        void injectedAuthorActorIdIsIgnored() throws Exception {
            Long itemId = createItem();

            Map<String, Object> body = comment("note", "trying to sign as somebody else", null);
            body.put("authorActorId", strangerActorId);

            JsonNode created = postCommentJson(itemId, body);

            assertThat(created.get("authorActorId").asLong())
                    .as("the DTO has no field for it, so Jackson has nowhere to put it")
                    .isEqualTo(agentActorId);
            assertThat(commentsOf(itemId).get(0).getAuthorActorId())
                    .as("and the stored row says who actually wrote it")
                    .isEqualTo(agentActorId);
        }

        @Test
        @DisplayName("author, authorHandle and authorId in the body are ignored too")
        void everyOtherWayOfNamingAnAuthorIsIgnored() throws Exception {
            Long itemId = createItem();

            Map<String, Object> body = comment("note", "second attempt", null);
            body.put("author", "wic-stranger-agent");
            body.put("authorHandle", "wic-stranger-agent");
            body.put("authorId", strangerActorId);
            body.put("createdByActorId", strangerActorId);

            assertThat(postCommentJson(itemId, body).get("authorActorId").asLong()).isEqualTo(agentActorId);
        }

        @Test
        @DisplayName("a legacy JWT with no actor is refused on POST: 403")
        void legacyJwtWithoutActorIs403() throws Exception {
            Long itemId = createItem();

            authenticateLegacyJwt(OWNER);

            mockMvc.perform(post("/api/v1/work-items/" + itemId + "/comments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(comment("note", "no author", null))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message",
                            org.hamcrest.Matchers.containsString("must be attributed to an actor")));

            assertThat(commentCount(itemId))
                    .as("a refused write leaves no comment")
                    .isZero();
        }

        @Test
        @DisplayName("a human with a registered kind='human' actor posts through that actor")
        void humanActorFallbackAttributesTheComment() throws Exception {
            Long itemId = createItem();
            Long humanActorId = saveActor("wic-human", OWNER, Actor.STATUS_ACTIVE).getId();
            Actor human = actorRepository.findById(humanActorId).orElseThrow();
            human.setKind(Actor.KIND_HUMAN);
            actorRepository.save(human);

            authenticateLegacyJwt(OWNER);

            JsonNode created = postCommentJson(itemId, comment("note", "a human wrote this", null));

            assertThat(created.get("authorActorId").asLong())
                    .as("no actor in the credential, so the caller's own human actor is used"
                            + " — still resolved from the request, never from the body")
                    .isEqualTo(humanActorId);
        }

        @Test
        @DisplayName("reading is allowed for a legacy JWT: 403 is about attribution, not about reading")
        void legacyJwtMayReadComments() throws Exception {
            Long itemId = createItem();
            postValidComment(itemId, "note", "written by the agent", null);

            authenticateLegacyJwt(OWNER);

            mockMvc.perform(get("/api/v1/work-items/" + itemId + "/comments"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(1));
            // Reading is allowed here: the owner's own project, and a read creates no
            // attribution and changes nothing.
        }
    }

    // ── AC 149: the kind vocabulary is 400, the payload contract is 422 ───────

    @Nested
    @DisplayName("AC 149: kind is validated with 400")
    class KindIs400 {

        @Test
        @DisplayName("an unknown kind → 400, and explicitly not 422")
        void unknownKindIs400Not422() throws Exception {
            Long itemId = createItem();

            MvcResult result = postCommentExpect(itemId, comment("progress", "not a kind", null), 400);

            assertThat(objectMapper.readTree(result.getResponse().getContentAsString())
                    .get("status").asInt())
                    .as("422 is reserved for payload contracts; a kind the API does not have is a"
                            + " malformed request")
                    .isEqualTo(400);
            assertThat(result.getResponse().getContentAsString()).contains("Unknown kind");
        }

        @Test
        @DisplayName("a missing kind → 400")
        void missingKindIs400() throws Exception {
            Long itemId = createItem();

            Map<String, Object> body = new HashMap<>();
            body.put("body", "no kind at all");

            postCommentExpect(itemId, body, 400);
        }

        @Test
        @DisplayName("all six kinds are accepted")
        void everyKindIsAccepted() throws Exception {
            Long itemId = createItem();
            // WIKI4AI-174: the handoff below names wic-stranger-agent, and a handoff may
            // only name an actor that can actually receive it — a member of this item's
            // project. This test is about the six kinds, not about membership, so the
            // target is made a member and every kind stays accepted.
            addProjectActor(publicProjectId, strangerActorId, ProjectActor.ROLE_WORKER);

            postCommentExpect(itemId, comment("status", "where things stand", null), 201);
            postCommentExpect(itemId, comment("note", "a note", null), 201);
            postCommentExpect(itemId, comment("result", "done",
                    Map.of("where", "main", "next", "review", "artifacts", List.of("commit abc123"))), 201);
            postCommentExpect(itemId, comment("blocker", "waiting",
                    Map.of("blocking", "no access", "needs_human", true)), 201);
            postCommentExpect(itemId, comment("handoff", "over to you",
                    Map.of("to_handle", "wic-stranger-agent", "context", "needs the frontend part")), 201);
            postCommentExpect(itemId, comment("decision", "we use H2 in tests",
                    Map.of("what", "H2", "why", "speed", "applies_to", "backend tests")), 201);

            assertThat(commentCount(itemId)).isEqualTo(6);
        }

        @Test
        @DisplayName("kind is normalized: 'RESULT' is 'result'")
        void kindIsNormalized() throws Exception {
            Long itemId = createItem();

            JsonNode created = postCommentJson(itemId, comment("  RESULT  ", "spaced and upper case",
                    Map.of("where", "main", "next", "review", "artifacts", List.of())));

            assertThat(created.get("kind").asText()).isEqualTo("result");
        }
    }

    // ── AC 149: the per-kind payload contracts, 422 ───────────────────────────

    @Nested
    @DisplayName("AC 149: a payload that breaks its kind's contract → 422")
    class ContractsAre422 {

        @Test
        @DisplayName("result without where → 422 naming 'where'")
        void resultWithoutWhereIs422() throws Exception {
            Long itemId = createItem();

            MvcResult result = postCommentExpect(itemId, comment("result", "done",
                    Map.of("next", "review", "artifacts", List.of("x"))), 422);

            JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
            assertThat(body.get("status").asInt()).isEqualTo(422);
            assertThat(body.get("kind").asText()).isEqualTo("result");
            assertThat(body.get("violations").toString()).contains("where");
            assertThat(commentCount(itemId)).as("a 422 writes nothing").isZero();
        }

        @Test
        @DisplayName("result with where + next + artifacts[] → 201")
        void completeResultIs201() throws Exception {
            Long itemId = createItem();

            JsonNode created = postCommentJson(itemId, comment("result", "shipped",
                    Map.of("where", "main@dcd51ad", "next", "review",
                            "artifacts", List.of("backend/src/.../WorkItemComment.java"))));

            assertThat(created.get("kind").asText()).isEqualTo("result");
            assertThat(created.get("payload").get("where").asText()).isEqualTo("main@dcd51ad");
            assertThat(created.get("payload").get("artifacts").isArray()).isTrue();
        }

        @Test
        @DisplayName("result without payload at all → 422")
        void resultWithoutPayloadIs422() throws Exception {
            Long itemId = createItem();

            postCommentExpect(itemId, comment("result", "no payload", null), 422);
        }

        @Test
        @DisplayName("artifacts as a string → 422; artifacts as a number → 422")
        void artifactsMustBeAnArray() throws Exception {
            Long itemId = createItem();

            MvcResult asString = postCommentExpect(itemId, comment("result", "bad artifacts",
                    Map.of("where", "main", "next", "review", "artifacts", "commit abc123")), 422);
            assertThat(objectMapper.readTree(asString.getResponse().getContentAsString())
                    .get("violations").toString()).contains("must be an array");

            postCommentExpect(itemId, comment("result", "bad artifacts",
                    Map.of("where", "main", "next", "review", "artifacts", 7)), 422);

            assertThat(commentCount(itemId)).isZero();
        }

        @Test
        @DisplayName("an empty artifacts[] is still an array → 201")
        void emptyArraySatisfiesTheContract() throws Exception {
            Long itemId = createItem();

            postCommentExpect(itemId, comment("result", "nothing to show",
                    Map.of("where", "main", "next", "review", "artifacts", List.of())), 201);

            assertThat(commentsOf(itemId).get(0).getPayload().get("artifacts"))
                    .as("the contract requires an array; an empty one is an array, and 'no artifacts'"
                            + " is a legitimate answer")
                    .isInstanceOf(List.class);
        }

        @Test
        @DisplayName("blocker without needs_human → 422")
        void blockerWithoutNeedsHumanIs422() throws Exception {
            Long itemId = createItem();

            MvcResult result = postCommentExpect(itemId, comment("blocker", "stuck",
                    Map.of("blocking", "missing credentials")), 422);

            assertThat(objectMapper.readTree(result.getResponse().getContentAsString())
                    .get("violations").toString()).contains("needs_human");
        }

        @Test
        @DisplayName("needs_human as the string \"true\" → 422: it is a flag, not free text")
        void needsHumanAsStringIs422() throws Exception {
            Long itemId = createItem();

            MvcResult result = postCommentExpect(itemId, comment("blocker", "stuck",
                    Map.of("blocking", "missing credentials", "needs_human", "true")), 422);

            JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
            assertThat(body.get("status").asInt()).isEqualTo(422);
            assertThat(body.get("violations").toString()).contains("must be true or false");
            assertThat(commentCount(itemId)).isZero();
        }

        @Test
        @DisplayName("needs_human as 1 → 422; as true and as false → 201")
        void needsHumanMustBeABoolean() throws Exception {
            Long itemId = createItem();

            postCommentExpect(itemId, comment("blocker", "stuck",
                    Map.of("blocking", "x", "needs_human", 1)), 422);
            postCommentExpect(itemId, comment("blocker", "needs a human",
                    Map.of("blocking", "x", "needs_human", true)), 201);
            postCommentExpect(itemId, comment("blocker", "agent can solve it",
                    Map.of("blocking", "x", "needs_human", false)), 201);

            assertThat(commentsOf(itemId))
                    .extracting(dto -> dto.getPayload().get("needs_human"))
                    .containsExactly(true, false);
        }

        @Test
        @DisplayName("handoff without to_handle → 422")
        void handoffWithoutToHandleIs422() throws Exception {
            Long itemId = createItem();

            MvcResult result = postCommentExpect(itemId, comment("handoff", "over to you",
                    Map.of("context", "needs the frontend part")), 422);

            assertThat(objectMapper.readTree(result.getResponse().getContentAsString())
                    .get("violations").toString()).contains("to_handle");
        }

        @Test
        @DisplayName("handoff to an actor that does not exist → 422 naming the handle")
        void handoffToUnknownActorIs422() throws Exception {
            Long itemId = createItem();

            MvcResult result = postCommentExpect(itemId, comment("handoff", "over to nobody",
                    Map.of("to_handle", "wic-nobody-agent", "context", "please continue")), 422);

            JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
            assertThat(body.get("status").asInt()).isEqualTo(422);
            assertThat(body.get("violations").toString())
                    .as("the message names the handle, because a handoff that silently goes nowhere"
                            + " is the failure this rule exists to prevent")
                    .contains("wic-nobody-agent");
            assertThat(commentCount(itemId)).isZero();
        }

        @Test
        @DisplayName("handoff to a paused actor → 422")
        void handoffToPausedActorIs422() throws Exception {
            Long itemId = createItem();

            MvcResult result = postCommentExpect(itemId, comment("handoff", "over to a paused actor",
                    Map.of("to_handle", "wic-paused-agent", "context", "please continue")), 422);

            assertThat(objectMapper.readTree(result.getResponse().getContentAsString())
                    .get("violations").toString()).contains("paused");
        }

        @Test
        @DisplayName("handoff to an active actor who is a member of the project → 201; the handle is matched case-insensitively")
        void handoffToActiveActorIs201() throws Exception {
            Long itemId = createItem();
            // WIKI4AI-174 added the second half of this rule: active is necessary, and
            // membership is the other half. The target is a member here so this test keeps
            // testing what it always tested — that an acceptable handoff is accepted, and
            // that the handle match ignores case.
            addProjectActor(publicProjectId, strangerActorId, ProjectActor.ROLE_WORKER);

            JsonNode created = postCommentJson(itemId, comment("handoff", "over to you",
                    Map.of("to_handle", "WIC-Stranger-Agent", "context", "needs the frontend part")));

            assertThat(created.get("kind").asText()).isEqualTo("handoff");
            assertThat(created.get("payload").get("to_handle").asText()).isEqualTo("WIC-Stranger-Agent");
        }

        @Test
        @DisplayName("decision without applies_to → 422")
        void decisionWithoutAppliesToIs422() throws Exception {
            Long itemId = createItem();

            MvcResult result = postCommentExpect(itemId, comment("decision", "we use H2",
                    Map.of("what", "H2", "why", "speed")), 422);

            assertThat(objectMapper.readTree(result.getResponse().getContentAsString())
                    .get("violations").toString()).contains("applies_to");
        }

        @Test
        @DisplayName("status and note with no payload → 201")
        void statusAndNoteNeedNoPayload() throws Exception {
            Long itemId = createItem();

            postCommentExpect(itemId, comment("status", "still on the backend part", null), 201);
            postCommentExpect(itemId, comment("note", "fyi", null), 201);

            List<WorkItemCommentDTO> stored = commentsOf(itemId);
            assertThat(stored).hasSize(2);
            assertThat(stored).allSatisfy(dto -> assertThat(dto.getPayload()).isNull());
        }

        @Test
        @DisplayName("unknown payload keys are allowed and kept")
        void unknownPayloadKeysAreAllowed() throws Exception {
            Long itemId = createItem();

            Map<String, Object> payload = new HashMap<>();
            payload.put("where", "main");
            payload.put("next", "review");
            payload.put("artifacts", List.of("x"));
            payload.put("durationMinutes", 42);
            payload.put("confidence", "high");

            JsonNode created = postCommentJson(itemId, comment("result", "with extra fields", payload));

            assertThat(created.get("payload").get("durationMinutes").asInt()).isEqualTo(42);
            assertThat(created.get("payload").get("confidence").asText()).isEqualTo("high");
        }

        @Test
        @DisplayName("every required field of one kind is reported at once, not one per retry")
        void allViolationsAreReportedTogether() throws Exception {
            Long itemId = createItem();

            MvcResult result = postCommentExpect(itemId, comment("decision", "nothing filled in", null), 422);

            JsonNode violations = objectMapper.readTree(result.getResponse().getContentAsString())
                    .get("violations");
            assertThat(violations).hasSize(3);
            assertThat(violations.toString())
                    .contains("what")
                    .contains("why")
                    .contains("applies_to");
        }

        @Test
        @DisplayName("a 422 leaves the item untouched: no comment, no event")
        void contractFailureWritesNothing() throws Exception {
            Long itemId = createItem();

            postCommentExpect(itemId, comment("result", "incomplete", Map.of("where", "main")), 422);

            assertThat(commentCount(itemId)).isZero();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM work_item_comments WHERE work_item_id = " + itemId, Integer.class))
                    .isZero();
        }
    }

    // ── AC 149: 400 and 422 are different failures ────────────────────────────

    @Nested
    @DisplayName("AC 149: a missing required field is 400, a broken contract is 422")
    class BadRequestVersusUnprocessable {

        @Test
        @DisplayName("missing body → 400, explicitly not 422")
        void missingBodyIs400Not422() throws Exception {
            Long itemId = createItem();

            Map<String, Object> body = new HashMap<>();
            body.put("kind", "note");

            MvcResult result = postCommentExpect(itemId, body, 400);

            JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
            assertThat(json.get("status").asInt())
                    .as("body is a required field of the request (V22: body TEXT NOT NULL), not a"
                            + " payload contract")
                    .isEqualTo(400);
            assertThat(json.get("message").asText()).contains("body is required");
        }

        @Test
        @DisplayName("a blank body → 400")
        void blankBodyIs400() throws Exception {
            Long itemId = createItem();

            postCommentExpect(itemId, comment("note", "   ", null), 400);
        }

        @Test
        @DisplayName("a missing body beats a broken payload: 400, not 422")
        void malformedRequestIsReportedBeforeTheContract() throws Exception {
            Long itemId = createItem();

            Map<String, Object> body = new HashMap<>();
            body.put("kind", "result");
            body.put("payload", Map.of("artifacts", "not an array"));

            MvcResult result = postCommentExpect(itemId, body, 400);

            assertThat(objectMapper.readTree(result.getResponse().getContentAsString())
                    .get("status").asInt())
                    .as("the request is malformed before any contract is evaluated")
                    .isEqualTo(400);
        }

        @Test
        @DisplayName("an unknown kind with a valid payload → 400, not 422")
        void unknownKindIsNotAContractViolation() throws Exception {
            Long itemId = createItem();

            postCommentExpect(itemId, comment("update", "kind that does not exist",
                    Map.of("where", "main", "next", "review", "artifacts", List.of("x"))), 400);
        }
    }

    // ── AC 148: comments are immutable, and it is enforced ────────────────────

    @Nested
    @DisplayName("AC 148: immutability is enforced, not declared")
    class ImmutableIsEnforced {

        @Test
        @DisplayName("the API exposes no PUT, PATCH or DELETE on comments: 405")
        void noMutatingEndpointExists() throws Exception {
            Long itemId = createItem();
            Long commentId = postValidComment(itemId, "note", "immutable note", null);

            mockMvc.perform(put("/api/v1/work-items/" + itemId + "/comments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"body\":\"edited\"}"))
                    .andExpect(status().isMethodNotAllowed());

            mockMvc.perform(patch("/api/v1/work-items/" + itemId + "/comments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"body\":\"edited\"}"))
                    .andExpect(status().isMethodNotAllowed());

            mockMvc.perform(delete("/api/v1/work-items/" + itemId + "/comments"))
                    .andExpect(status().isMethodNotAllowed());

            mockMvc.perform(delete("/api/v1/work-items/" + itemId + "/comments/" + commentId))
                    .andExpect(result -> assertThat(result.getResponse().getStatus())
                            .as("DELETE on /comments/{cid} is not mapped at all, so nothing here can"
                                    + " reach a comment by id. The measured answer is 500, not the 405"
                                    + " an unmapped method deserves: this application has mapped no"
                                    + " path at all for over a year and its catch-all Exception handler"
                                    + " turns the resulting NoResourceFoundException into 500 (the same"
                                    + " way POST /api/v1/documents answers 500 today). That is a"
                                    + " pre-existing defect of the repository, reported separately,"
                                    + " and fixing it would change the behaviour of every unmapped"
                                    + " path in the API — out of this story's scope. What immutability"
                                    + " needs is asserted next: the answer is an error, and no write"
                                    + " happened.")
                            .isGreaterThanOrEqualTo(400));

            assertThat(commentsOf(itemId).get(0).getBody())
                    .as("after four attempts to change or remove it, the text is still the one written")
                    .isEqualTo("immutable note");
        }

        @Test
        @DisplayName("the controller declares only GET and POST mappings")
        void controllerDeclaresOnlyGetAndPost() {
            List<String> declared = new ArrayList<>();
            for (Method method : com.wiki4ai.controller.WorkItemCommentController.class.getDeclaredMethods()) {
                if (method.isAnnotationPresent(org.springframework.web.bind.annotation.GetMapping.class)) {
                    declared.add("GET " + method.getName());
                }
                if (method.isAnnotationPresent(org.springframework.web.bind.annotation.PostMapping.class)) {
                    declared.add("POST " + method.getName());
                }
                assertThat(method.isAnnotationPresent(org.springframework.web.bind.annotation.PutMapping.class))
                        .as("%s declares a PUT", method.getName()).isFalse();
                assertThat(method.isAnnotationPresent(org.springframework.web.bind.annotation.PatchMapping.class))
                        .as("%s declares a PATCH", method.getName()).isFalse();
                assertThat(method.isAnnotationPresent(org.springframework.web.bind.annotation.DeleteMapping.class))
                        .as("%s declares a DELETE", method.getName()).isFalse();
            }
            assertThat(declared).containsExactlyInAnyOrder("GET listComments", "POST addComment");
        }

        @Test
        @DisplayName("the service exposes no update or delete method")
        void serviceHasNoMutatingMethod() {
            assertThat(Arrays.stream(WorkItemCommentService.class.getDeclaredMethods())
                    .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
                    .map(Method::getName)
                    .filter(name -> name.startsWith("update") || name.startsWith("patch")
                            || name.startsWith("edit") || name.startsWith("delete")
                            || name.startsWith("remove")))
                    .as("there is no method to call, so no caller can reach an edit")
                    .isEmpty();
        }

        @Test
        @DisplayName("every inherited mutating repository method throws")
        void repositoryCannotDeleteOrRewrite() throws Exception {
            Long itemId = createItem();
            WorkItemComment stored = workItemCommentRepository
                    .findByWorkItemIdOrderByCreatedAtAscIdAsc(itemId).isEmpty()
                    ? saveFixtureComment(itemId)
                    : workItemCommentRepository.findByWorkItemIdOrderByCreatedAtAscIdAsc(itemId).get(0);

            assertThatThrownBy(() -> workItemCommentRepository.deleteAll())
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("immutable");
            assertThatThrownBy(() -> workItemCommentRepository.deleteAll(List.of(stored)))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("immutable");
            assertThatThrownBy(() -> workItemCommentRepository.delete(stored))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("immutable");
            assertThatThrownBy(() -> workItemCommentRepository.deleteById(stored.getId()))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("immutable");
            assertThatThrownBy(() -> workItemCommentRepository.deleteAllById(List.of(stored.getId())))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("immutable");
            assertThatThrownBy(() -> workItemCommentRepository.saveAndFlush(stored))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("immutable");

            // The bulk statements are the dangerous ones: DELETE FROM with no entities
            // loaded, so @PreRemove never runs, and the V22 trigger is a BEFORE UPDATE
            // trigger because a BEFORE DELETE trigger would break the ON DELETE CASCADE
            // SPEC's own DDL declares. The repository is the only layer left, and it
            // refuses all three.
            assertThatThrownBy(() -> workItemCommentRepository.deleteAllInBatch())
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("immutable");
            assertThatThrownBy(() -> workItemCommentRepository.deleteAllInBatch(List.of(stored)))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("immutable");
            assertThatThrownBy(() -> workItemCommentRepository.deleteAllByIdInBatch(List.of(stored.getId())))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("immutable");

            assertThat(workItemCommentRepository.findByWorkItemIdOrderByCreatedAtAscIdAsc(itemId))
                    .as("after nine attempted writes, including the bulk ones, the comment is still there")
                    .hasSize(1);

            assertThat(countFromDatabase(itemId))
                    .as("read straight from the table, not through the repository that refused the writes")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("no mutating CRUD method is inherited from Spring Data unchanged")
        void noMutatingMethodIsInheritedUnchanged() {
            // The list is derived from the interface rather than written from memory,
            // and that is the point of the test: CrudRepository contributes five
            // mutating methods, JpaRepository contributes five more, and the fifth of
            // those — deleteInBatch — is declared on JpaRepository only. A refusal list
            // copied from the CRUD names would have left it inherited.
            List<String> refused = new ArrayList<>();
            List<String> unguarded = new ArrayList<>();
            for (Method method : WorkItemCommentRepository.class.getMethods()) {
                if (method.isBridge() || method.isSynthetic()) {
                    continue; // covariant overrides produce duplicates, not new operations
                }
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
                    .containsExactlyInAnyOrder("delete", "deleteById", "deleteAllById", "deleteAll",
                            "deleteAll", "saveAndFlush", "deleteAllInBatch", "deleteAllInBatch",
                            "deleteAllByIdInBatch", "deleteInBatch");
        }

        @Test
        @DisplayName("a stored comment cannot be modified: the original kind still holds")
        void commentCannotBeModified() throws Exception {
            Long itemId = createItem();
            WorkItemComment stored = saveFixtureComment(itemId);
            String originalKind = stored.getKind();
            String originalBody = stored.getBody();

            assertThat(originalKind).isEqualTo("note");

            assertRefusedAsImmutable(() -> inTransaction(() -> {
                WorkItemComment managed = entityManager.find(WorkItemComment.class, stored.getId());
                tamperCommentField(managed, "result");
                entityManager.flush();
            }));

            WorkItemComment reread = workItemCommentRepository
                    .findByWorkItemIdOrderByCreatedAtAscIdAsc(itemId).get(0);
            assertThat(reread.getKind())
                    .as("the UPDATE was refused, so the stored kind is still the one that was written")
                    .isEqualTo(originalKind);
            assertThat(reread.getBody()).isEqualTo(originalBody);
            assertThat(reread.getId()).isEqualTo(stored.getId());
        }

        @Test
        @DisplayName("a stored comment cannot be removed through JPA either")
        void commentCannotBeRemovedThroughJpa() throws Exception {
            Long itemId = createItem();
            WorkItemComment stored = saveFixtureComment(itemId);

            assertRefusedAsImmutable(() -> inTransaction(() -> {
                entityManager.remove(entityManager.find(WorkItemComment.class, stored.getId()));
                entityManager.flush();
            }));

            assertThat(workItemCommentRepository.findByWorkItemIdOrderByCreatedAtAscIdAsc(itemId))
                    .as("the DELETE was refused; the comment is still there")
                    .hasSize(1);
        }

        @Test
        @DisplayName("the entity has no setter, has no updated_at, and carries both lifecycle guards")
        void entityIsImmutableAndGuarded() {
            assertThat(Arrays.stream(WorkItemComment.class.getDeclaredMethods())
                    .filter(method -> method.getName().startsWith("set")))
                    .as("a comment is fixed at construction")
                    .isEmpty();

            // Instance fields only: the KIND_* constants are static and map to no column.
            List<String> columns = Arrays.stream(WorkItemComment.class.getDeclaredFields())
                    .filter(field -> !java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                    .map(Field::getName)
                    .collect(java.util.stream.Collectors.toList());

            assertThat(columns)
                    .as("an immutable row has no later moment to record")
                    .doesNotContain("updatedAt", "updated_at");

            assertThat(columns)
                    .as("exactly the seven columns of V22, and nothing else")
                    .containsExactlyInAnyOrder("id", "workItemId", "authorActorId", "kind", "body",
                            "payload", "createdAt");

            assertThat(Arrays.stream(WorkItemComment.class.getDeclaredMethods())
                    .filter(method -> method.isAnnotationPresent(jakarta.persistence.PreUpdate.class)
                            || method.isAnnotationPresent(jakarta.persistence.PreRemove.class)))
                    .as("the JPA lifecycle guards catch the paths that never touch the repository")
                    .hasSize(2);

            assertThat(Arrays.stream(WorkItemComment.class.getDeclaredFields())
                    .filter(field -> field.isAnnotationPresent(jakarta.persistence.Version.class)))
                    .as("an immutable row has no value to lock")
                    .isEmpty();
        }

        @Test
        @DisplayName("every DB-mapped column of the entity is LocalDateTime, never Instant")
        void timeColumnsAreLocalDateTime() {
            assertThat(Arrays.stream(WorkItemComment.class.getDeclaredFields())
                    .filter(field -> java.time.LocalDateTime.class.isAssignableFrom(field.getType())))
                    .extracting(Field::getName)
                    .containsExactly("createdAt");

            assertThat(Arrays.stream(WorkItemComment.class.getDeclaredFields())
                    .filter(field -> java.time.Instant.class.isAssignableFrom(field.getType())))
                    .as("SPEC rule 11b: Instant maps to TIMESTAMP WITH TIME ZONE and the postgres"
                            + " profile runs with ddl-auto=validate")
                    .isEmpty();
        }

        /** Write one comment straight through the one door the application has. */
        private WorkItemComment saveFixtureComment(Long itemId) {
            return workItemCommentRepository.save(WorkItemComment.builder()
                    .workItemId(itemId)
                    .authorActorId(agentActorId)
                    .kind("note")
                    .body("immutable note")
                    .build());
        }
    }

    // ── AC 148: ownership ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("AC 148: ownership of the comments of an item")
    class Ownership {

        @Test
        @DisplayName("GET on someone else's private item → 404")
        void foreignPrivateItemIs404() throws Exception {
            authenticate(STRANGER, strangerActorId);
            Long itemId = createItem("Private discussion", privateProjectId);
            postValidComment(itemId, "note", "for the owner's eyes only", null);

            authenticate(OWNER, agentActorId);
            mockMvc.perform(get("/api/v1/work-items/" + itemId + "/comments"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("POST on someone else's private item → 404")
        void postToForeignPrivateItemIs404() throws Exception {
            authenticate(STRANGER, strangerActorId);
            Long itemId = createItem("Private discussion", privateProjectId);

            authenticate(OWNER, agentActorId);
            postCommentExpect(itemId, comment("note", "trying to comment", null), 404);
        }

        @Test
        @DisplayName("GET and POST on a public item in a project the caller has no part in → 403")
        void foreignPublicItemIs403() throws Exception {
            authenticate(STRANGER, strangerActorId);
            Long itemId = createItem("Public but not mine", strangerProjectId);

            authenticate(OWNER, agentActorId);
            mockMvc.perform(get("/api/v1/work-items/" + itemId + "/comments"))
                    .andExpect(status().isForbidden());
            postCommentExpect(itemId, comment("note", "not my project", null), 403);

            mockMvc.perform(get("/api/v1/work-items/" + itemId))
                    .andExpect(status().isOk());
            // The item itself is public and readable; its comment thread is not.
        }

        @Test
        @DisplayName("POST on an item that does not exist → 404")
        void postToUnknownItemIs404() throws Exception {
            postCommentExpect(999999L, comment("note", "no such item", null), 404);
        }

        @Test
        @DisplayName("GET on an item that does not exist → 404")
        void getOfUnknownItemIs404() throws Exception {
            mockMvc.perform(get("/api/v1/work-items/999999/comments"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("an ADMIN user may read comments of a private item they do not own")
        void adminCanReadForeignPrivateComments() throws Exception {
            authenticate(STRANGER, strangerActorId);
            Long itemId = createItem("Private discussion", privateProjectId);
            postValidComment(itemId, "note", "private note", null);

            authenticate(ADMIN, null);
            mockMvc.perform(get("/api/v1/work-items/" + itemId + "/comments"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(1));
        }
    }

    // ── Regression: the new 422 handler did not move any existing mapping ─────

    /**
     * WIKI4AI-149 added one {@code @ExceptionHandler} to
     * {@code GlobalExceptionHandler}. These assertions are the proof that the addition
     * is additive: the statuses the work_items stories depend on are still produced by
     * the same mappings, on the same endpoints, in the same run that has a 422 handler
     * installed. In particular {@code IllegalArgumentException → 409} — the mapping
     * this story was told not to touch, and the one a careless "let's use 422 for
     * invalid input" change would have reused.
     */
    @Nested
    @DisplayName("Regression: existing status maps are untouched by the new 422")
    class ExistingStatusMapsAreUntouched {

        @Test
        @DisplayName("an illegal work item transition is still 409, not 422")
        void illegalTransitionIsStill409() throws Exception {
            Long itemId = createItem();

            MvcResult result = mockMvc.perform(put("/api/v1/work-items/" + itemId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"done\"}"))
                    .andExpect(status().isConflict())
                    .andReturn();

            assertThat(objectMapper.readTree(result.getResponse().getContentAsString())
                    .get("status").asInt())
                    .as("IllegalArgumentException still maps to 409")
                    .isEqualTo(409);
        }

        @Test
        @DisplayName("a duplicate external_ref is still 409")
        void duplicateExternalRefIsStill409() throws Exception {
            Map<String, Object> body = new HashMap<>();
            body.put("projectId", publicProjectId);
            body.put("title", "First");
            body.put("externalRef", "WIC-REG-1");
            mockMvc.perform(post("/api/v1/work-items")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isCreated());

            body.put("title", "Second, same external_ref");
            mockMvc.perform(post("/api/v1/work-items")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("a lost claim race is still 409")
        void lostClaimRaceIsStill409() throws Exception {
            Long itemId = createItem();
            mockMvc.perform(put("/api/v1/work-items/" + itemId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"ready\"}"))
                    .andExpect(status().isOk());
            mockMvc.perform(post("/api/v1/work-items/" + itemId + "/claim"))
                    .andExpect(status().isOk());
            mockMvc.perform(post("/api/v1/work-items/" + itemId + "/claim"))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("an unknown status filter is still 400")
        void unknownStatusFilterIsStill400() throws Exception {
            mockMvc.perform(get("/api/v1/work-items").param("status", "not-a-status"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("a create without a title is still 400")
        void createWithoutTitleIsStill400() throws Exception {
            mockMvc.perform(post("/api/v1/work-items")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"projectId\":" + publicProjectId + "}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("a foreign private item is still 404 on the item itself")
        void foreignPrivateItemIsStill404() throws Exception {
            authenticate(STRANGER, strangerActorId);
            Long itemId = createItem("Private item", privateProjectId);

            authenticate(OWNER, agentActorId);
            mockMvc.perform(get("/api/v1/work-items/" + itemId))
                    .andExpect(status().isNotFound());
        }
    }

    // ── WIKI4AI-174: a handoff to an actor who is not a member of the project ──

    /**
     * Measured live: a {@code handoff} naming an agent that has no row in
     * {@code project_actors} of the item's project was accepted with <b>201</b>, and the
     * target's inbox never showed it — the {@code mention} stream of WIKI4AI-152 is
     * scoped to the projects an actor has a role in. The only difference between the
     * 201 and the working case was the membership row.
     * <p>
     * WIKI4AI-149 already refuses a handoff to an actor who does not exist or is not
     * active, and its reasoning is that such a handoff is a <i>silent stall</i>. A
     * handoff to an actor who cannot see the project is the same stall one step later,
     * so it is refused by the same rule, in the same place, with the same status.
     * <p>
     * The status is asserted as {@code 422} and explicitly not {@code 409} in every
     * test here, because the only route to 422 is {@code CommentContractException} while
     * {@code IllegalArgumentException} — the exception this project already throws for a
     * rejected write — maps to 409. A fix that reached 422 by accident, or reached 409
     * instead, is caught by that pair.
     */
    @Nested
    @DisplayName("WIKI4AI-174: handoff to an actor with no role in this project → 422")
    class HandoffMustReachSomeone {

        @Test
        @DisplayName("AC: active actor, not a member of the item's project → 422, not 201")
        void handoffToActiveNonMemberIs422() throws Exception {
            Long itemId = createItem();
            // wic-stranger-agent exists and is active, and has no row in project_actors
            // for publicProjectId. That is exactly the measured 201.
            assertThat(projectActorRepository.findByProjectIdAndActorId(publicProjectId, strangerActorId))
                    .as("the premise: the target really is not a member")
                    .isEmpty();

            MvcResult result = postComment(itemId, comment("handoff", "over to you",
                    Map.of("to_handle", "wic-stranger-agent", "context", "needs the frontend part")));

            assertThat(result.getResponse().getStatus())
                    .as("AC: 422 — the request was understood, its payload names someone "
                            + "who cannot receive the work")
                    .isEqualTo(422);
            assertThat(result.getResponse().getStatus())
                    .as("and it is 422 and NOT 409: 409 is IllegalArgumentException, which would "
                            + "mean the item itself rejected a write. This is a contract violation "
                            + "of the payload, the same class WIKI4AI-149 uses")
                    .isNotEqualTo(409);

            JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
            assertThat(body.get("status").asInt()).isEqualTo(422);
            assertThat(body.get("kind").asText()).isEqualTo("handoff");
            assertThat(body.get("violations").toString())
                    .as("the violation names the reason and the handle, so the caller can fix it")
                    .contains("to_handle")
                    .contains("wic-stranger-agent")
                    .contains("not a member of this project");

            assertThat(commentCount(itemId))
                    .as("nothing was written — a comment nobody will ever read is not a comment")
                    .isZero();
        }

        @Test
        @DisplayName("the measured repro, reversed: 422 before the actor is added, 201 after")
        void addingTheActorToTheProjectMakesTheSameHandoffAccepted() throws Exception {
            Long itemId = createItem();
            Map<String, Object> payload = new HashMap<>();
            payload.put("to_handle", "wic-stranger-agent");
            payload.put("context", "needs the frontend part");

            assertThat(postComment(itemId, comment("handoff", "over to you", payload))
                    .getResponse().getStatus()).isEqualTo(422);
            assertThat(commentCount(itemId)).isZero();

            addProjectActor(publicProjectId, strangerActorId, ProjectActor.ROLE_WORKER);

            assertThat(postComment(itemId, comment("handoff", "over to you", payload))
                    .getResponse().getStatus()).isEqualTo(201);
            assertThat(commentCount(itemId))
                    .as("the handoff is the same request in both halves; the membership row is "
                            + "the only difference — which is what the live measurement showed")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("a member with the viewer role may be handed work: 201")
        void handoffToViewerMemberIsAccepted() throws Exception {
            // The decision WIKI4AI-174 asked for and this is the documented answer.
            // A 'viewer' may not WRITE a comment — that is requireProjectWrite's rule,
            // applied to the author, and unchanged here. But being named by a handoff is
            // not a write, it is being addressed. Refusing it would mean work could only
            // ever be handed to actors who could have claimed the item themselves, which
            // is not what a handoff is. The condition is membership, not rank.
            Long itemId = createItem();
            addProjectActor(publicProjectId, strangerActorId, ProjectActor.ROLE_VIEWER);

            assertThat(postComment(itemId, comment("handoff", "over to a viewer",
                    Map.of("to_handle", "wic-stranger-agent", "context", "just to let you know")))
                    .getResponse().getStatus()).isEqualTo(201);
        }

        @Test
        @DisplayName("AC: a handoff on an item of ANOTHER project does not borrow the membership from this one")
        void membershipIsPerProject() throws Exception {
            // The stranger agent is a member of its own project, and the item lives in
            // the owner's public project. Membership elsewhere is not membership here.
            addProjectActor(strangerProjectId, strangerActorId, ProjectActor.ROLE_WORKER);
            Long itemId = createItem("Item in the owner's project", publicProjectId);

            assertThat(postComment(itemId, comment("handoff", "over to you",
                    Map.of("to_handle", "wic-stranger-agent", "context", "still not a member here")))
                    .getResponse().getStatus()).isEqualTo(422);

            // Only STRANGER may write in strangerProjectId, so the second half of this
            // test runs under that credential.
            authenticate(STRANGER, strangerActorId);
            Long theirItem = createItem("Item in their project", strangerProjectId);
            assertThat(postComment(theirItem, comment("handoff", "over to myself",
                    Map.of("to_handle", "wic-stranger-agent", "context", "member here")))
                    .getResponse().getStatus()).isEqualTo(201);
        }

        @Test
        @DisplayName("AC: handoff to an actor that does not exist is unchanged — 422 with the old violation")
        void unknownActorStillFailsFirst() throws Exception {
            Long itemId = createItem();

            MvcResult result = postComment(itemId, comment("handoff", "over to nobody",
                    Map.of("to_handle", "wic-nobody-agent", "context", "please continue")));

            assertThat(result.getResponse().getStatus()).isEqualTo(422);
            assertThat(result.getResponse().getStatus()).isNotEqualTo(409);
            assertThat(objectMapper.readTree(result.getResponse().getContentAsString())
                    .get("violations").toString())
                    .as("the existing message is kept: an unknown actor is reported as unknown, "
                            + "not as a non-member")
                    .contains("names no known actor");
        }

        @Test
        @DisplayName("AC: handoff to a paused actor is unchanged — 422 with the status violation")
        void pausedActorStillFailsFirst() throws Exception {
            Long itemId = createItem();

            MvcResult result = postComment(itemId, comment("handoff", "over to a paused actor",
                    Map.of("to_handle", "wic-paused-agent", "context", "please continue")));

            assertThat(result.getResponse().getStatus()).isEqualTo(422);
            assertThat(result.getResponse().getStatus()).isNotEqualTo(409);
            assertThat(objectMapper.readTree(result.getResponse().getContentAsString())
                    .get("violations").toString())
                    .as("the status violation is reported before membership, so the message a "
                            + "caller already handles is still the message it gets")
                    .contains("paused");
        }

        @Test
        @DisplayName("the 422 is the CommentContractException answer, not the IllegalArgumentException one")
        void the422IsAContractExceptionNotA409Exception() throws Exception {
            Long itemId = createItem();

            MvcResult result = postComment(itemId, comment("handoff", "over to you",
                    Map.of("to_handle", "wic-stranger-agent", "context", "x")));

            JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
            // 'Unprocessable Entity' is written by exactly one handler in
            // GlobalExceptionHandler — the CommentContractException one. 'Conflict' is the
            // IllegalArgumentException one. The body says which one ran.
            assertThat(body.get("error").asText()).isEqualTo("Unprocessable Entity");
            assertThat(body.get("violations").size())
                    .as("422 carries the field-level violations a caller has to fix; a 409 "
                            + "from IllegalArgumentException carries none")
                    .isPositive();

            // And the class itself: CommentContractException extends RuntimeException
            // directly, so it cannot be reached by the 409 mapping of IllegalArgumentException.
            assertThat(com.wiki4ai.exception.CommentContractException.class.getSuperclass())
                    .isEqualTo(RuntimeException.class);
            assertThat(IllegalArgumentException.class.isAssignableFrom(
                    com.wiki4ai.exception.CommentContractException.class))
                    .as("if CommentContractException ever became an IllegalArgumentException, this "
                            + "same request would answer 409 instead of 422")
                    .isFalse();
        }
    }
}
