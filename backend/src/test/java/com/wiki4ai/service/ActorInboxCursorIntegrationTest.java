package com.wiki4ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wiki4ai.config.JwtAuthenticationFilter.ApiTokenAuthenticationDetails;
import com.wiki4ai.dto.ProjectCreateDTO;
import com.wiki4ai.model.Actor;
import com.wiki4ai.model.Project;
import com.wiki4ai.model.ProjectActor;
import com.wiki4ai.model.Role;
import com.wiki4ai.model.User;
import com.wiki4ai.repository.ActorRepository;
import com.wiki4ai.repository.ApiTokenRepository;
import com.wiki4ai.repository.ProjectActorRepository;
import com.wiki4ai.repository.ProjectPermissionRepository;
import com.wiki4ai.repository.ProjectRepository;
import com.wiki4ai.repository.UserRepository;
import com.wiki4ai.repository.WorkItemCommentRepository;
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

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WIKI4AI-152: the cursor. This file exists on its own because the cursor is the decision
 * this story is judged by, and its two most important assertions should fail with a message
 * that says exactly what broke.
 *
 * <h2>The decision, in one paragraph</h2>
 * The inbox is four streams over four tables. Inside a stream the order is the table's own
 * monotone {@code id}, and the keyset pagination of that stream advances over it. Across
 * streams the ids are not comparable — {@code work_items.id = 5} and
 * {@code work_item_comments.id = 5} are two independent sequences counting unrelated things,
 * so merging them by id would state an order that nothing in the database supports — and the
 * display order is therefore {@code created_at} with {@code id} only as a tie-break. Four
 * keyset paginations need four positions, so the cursor carries four positions, and it is
 * opaque because a client that assembled it would be coupling itself to a format that is not
 * part of the contract.
 *
 * <h2>Rule 5b</h2>
 * {@link #cleanState()} in {@code @BeforeEach} AND {@code @AfterEach}, children before
 * parents, comments and events removed with native {@code DELETE} because their repositories
 * refuse {@code deleteAll()}. No {@code @DirtiesContext}.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@DisplayName("WIKI4AI-152: the per-stream cursor")
class ActorInboxCursorIntegrationTest {

    private static final String OWNER = "wi152c_owner";
    private static final String STRANGER = "wi152c_stranger";

    private static final String AGENT_HANDLE = "wi152c-agent";
    private static final String STRANGER_HANDLE = "wi152c-stranger-agent";

    /** The decoded shape, asserted on the cursor the API hands out. */
    private static final Pattern CURSOR_SHAPE = Pattern.compile(
            "^item:(0|[1-9][0-9]*),comment:(0|[1-9][0-9]*),mention:(0|[1-9][0-9]*),released:(0|[1-9][0-9]*)$");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WorkItemRepository workItemRepository;

    @Autowired
    private WorkItemCommentRepository workItemCommentRepository;

    @Autowired
    private WorkItemEventRepository workItemEventRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private ProjectActorRepository projectActorRepository;

    @Autowired
    private ProjectPermissionRepository projectPermissionRepository;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ApiTokenRepository apiTokenRepository;

    @Autowired
    private ActorRepository actorRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long agentActorId;
    private Long strangerActorId;
    private Long publicProjectId;

    // ── Rule 5b ───────────────────────────────────────────────────────────────

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        cleanState();

        saveUser(OWNER, Role.USER);
        saveUser(STRANGER, Role.USER);

        agentActorId = saveActor(AGENT_HANDLE, OWNER).getId();
        strangerActorId = saveActor(STRANGER_HANDLE, STRANGER).getId();

        publicProjectId = createProject("WI152C Public", Project.VISIBILITY_PUBLIC, OWNER);
        addProjectActor(publicProjectId, agentActorId, ProjectActor.ROLE_WORKER);
        addProjectActor(publicProjectId, strangerActorId, ProjectActor.ROLE_WORKER);

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

    private void authenticate(String username, Long actorId) {
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                username, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        authentication.setDetails(new ApiTokenAuthenticationDetails(1L, userIdOf(username), actorId,
                List.of("read", "write")));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private Long createItem(String title) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("projectId", publicProjectId);
        body.put("title", title);
        MvcResult result = mockMvc.perform(post("/api/v1/work-items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    /**
     * Create an item and claim it. Two calls because a created item lands in
     * 'backlog' (WorkItem.DEFAULT_STATUS) and 'claimed' is only reachable
     * from 'ready'.
     */
    private Long createClaimedItem(String title) throws Exception {
        Long id = createItem(title);
        transitionToReady(id);
        claimItem(id);
        return id;
    }

    private void transitionToReady(Long id) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("status", "ready");
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/work-items/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());
    }

    private void claimItem(Long id) throws Exception {
        mockMvc.perform(post("/api/v1/work-items/" + id + "/claim"))
                .andExpect(status().isOk());
    }

    private void postComment(Long itemId, String text) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("kind", "note");
        body.put("body", text);
        mockMvc.perform(post("/api/v1/work-items/" + itemId + "/comments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());
    }

    // ── Reading the inbox ─────────────────────────────────────────────────────

    private JsonNode readInbox(String cursor) throws Exception {
        String query = cursor == null ? "" : "?cursor=" + urlEncode(cursor);
        MvcResult result = mockMvc.perform(get("/api/v1/actors/" + agentActorId + "/inbox" + query))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private List<JsonNode> itemsOf(JsonNode inbox) {
        List<JsonNode> items = new ArrayList<>();
        inbox.get("items").forEach(items::add);
        return items;
    }

    /**
     * Bodies of the items that have one — an entry of the 'item' stream carries a title, and
     * the DTO omits a null body rather than sending one.
     */
    private List<String> bodiesOf(JsonNode inbox) {
        List<String> bodies = new ArrayList<>();
        itemsOf(inbox).forEach(node -> {
            if (node.hasNonNull("body")) {
                bodies.add(node.get("body").asText());
            }
        });
        return bodies;
    }

    /** Titles, because an item of the 'item' stream carries the work item's title, not a body. */
    private List<String> titlesOf(JsonNode inbox) {
        List<String> titles = new ArrayList<>();
        itemsOf(inbox).forEach(node -> titles.add(node.get("title").asText()));
        return titles;
    }

    /**
     * The cursor decoded, for assertions about what it carries.
     * <p>
     * A test is allowed to know the format; a client is not required to. That asymmetry is
     * the point of an opaque cursor, and it is why these assertions read the Base64 while the
     * API contract only promises "send it back".
     */
    private Map<String, Long> positionsOf(String cursor) {
        String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        assertThat(decoded).matches(CURSOR_SHAPE);
        Map<String, Long> positions = new HashMap<>();
        for (String part : decoded.split(",")) {
            String[] pair = part.split(":");
            positions.put(pair[0], Long.parseLong(pair[1]));
        }
        return positions;
    }

    // ── AC 152: the cursor does what a cursor is for ──────────────────────────

    @Nested
    @DisplayName("AC 152: two reads with the same cursor return nothing")
    class EmptyWhenNothingChanged {

        @Test
        @DisplayName("the first read carries items and a cursor; the second read with that"
                + " cursor is empty")
        void secondReadWithSameCursorIsEmpty() throws Exception {
            Long id = createClaimedItem("WI152C cursor item");
            postComment(id, "a comment before the cursor");

            JsonNode first = readInbox(null);
            assertThat(itemsOf(first))
                    .as("the first read has to carry something, or 'empty the second time'"
                            + " would prove nothing")
                    .isNotEmpty();
            String cursor = first.get("nextCursor").asText();
            assertThat(cursor).isNotBlank();

            JsonNode second = readInbox(cursor);

            assertThat(itemsOf(second))
                    .as("nothing changed between the two reads, so the same cursor must return"
                            + " nothing — this is the AC of WIKI4AI-152")
                    .isEmpty();
            assertThat(second.get("totalElements").asInt()).isZero();
        }

        @Test
        @DisplayName("an empty page still hands back a cursor, so a poll can keep going")
        void emptyPageStillCarriesACursor() throws Exception {
            Long id = createClaimedItem("WI152C poll");

            String cursor = readInbox(null).get("nextCursor").asText();

            JsonNode second = readInbox(cursor);
            assertThat(itemsOf(second)).isEmpty();
            assertThat(second.get("nextCursor").asText())
                    .as("a poll that lost its cursor would restart from the beginning and"
                            + " report everything twice")
                    .isNotBlank();
        }

        @Test
        @DisplayName("the cursor of an empty page does not move: a third read is empty too")
        void cursorOfEmptyPageDoesNotMove() throws Exception {
            Long id = createClaimedItem("WI152C still empty");

            String cursor = readInbox(null).get("nextCursor").asText();
            readInbox(cursor);
            JsonNode third = readInbox(cursor);

            assertThat(itemsOf(third)).isEmpty();
        }
    }

    @Nested
    @DisplayName("AC 152: a new item appears exactly once")
    class NewItemAppearsOnce {

        @Test
        @DisplayName("write a comment → the ORIGINAL cursor returns exactly that one item, and"
                + " its cursor returns nothing after it")
        void newCommentAppearsOnceWithTheOriginalCursor() throws Exception {
            Long id = createClaimedItem("WI152C once only");
            postComment(id, "first comment");

            JsonNode first = readInbox(null);
            String originalCursor = first.get("nextCursor").asText();
            assertThat(bodiesOf(first)).contains("first comment");

            postComment(id, "the new one");

            JsonNode second = readInbox(originalCursor);
            assertThat(itemsOf(second))
                    .as("exactly the item that was written after the first read — not the"
                            + " whole inbox again")
                    .hasSize(1);
            assertThat(bodiesOf(second)).containsExactly("the new one");

            JsonNode third = readInbox(second.get("nextCursor").asText());
            assertThat(itemsOf(third))
                    .as("and it is not reported twice")
                    .isEmpty();
        }

        @Test
        @DisplayName("a new item in another stream also arrives once")
        void newClaimedItemArrivesOnce() throws Exception {
            Long firstItem = createClaimedItem("WI152C first item");

            String cursor = readInbox(null).get("nextCursor").asText();

            Long secondItem = createClaimedItem("WI152C second item");

            JsonNode page = readInbox(cursor);
            assertThat(itemsOf(page)).hasSize(1);
            assertThat(page.get("items").get(0).get("stream").asText()).isEqualTo("item");
            assertThat(page.get("items").get(0).get("title").asText()).isEqualTo("WI152C second item");

            assertThat(itemsOf(readInbox(page.get("nextCursor").asText()))).isEmpty();
        }
    }

    @Nested
    @DisplayName("AC 152: the cursor carries one position per stream")
    class PerStreamPositions {

        @Test
        @DisplayName("the cursor carries four positions, and a new comment moves only the"
                + " comment position")
        void positionsAdvancePerStream() throws Exception {
            Long id = createClaimedItem("WI152C per stream");
            postComment(id, "comment one");

            JsonNode first = readInbox(null);
            Map<String, Long> firstPositions = positionsOf(first.get("nextCursor").asText());

            assertThat(firstPositions)
                    .as("one position per stream, nothing else")
                    .containsOnlyKeys("item", "comment", "mention", "released");
            assertThat(firstPositions.get("item")).isEqualTo(id);
            assertThat(firstPositions.get("comment")).isPositive();

            postComment(id, "comment two");

            JsonNode second = readInbox(first.get("nextCursor").asText());
            Map<String, Long> secondPositions = positionsOf(second.get("nextCursor").asText());

            assertThat(secondPositions.get("comment"))
                    .as("the comment stream advanced")
                    .isGreaterThan(firstPositions.get("comment"));
            assertThat(secondPositions.get("item"))
                    .as("the item stream did NOT move: a new comment is not a new item, and a"
                            + " single shared position could not tell those two apart")
                    .isEqualTo(firstPositions.get("item"));
            assertThat(secondPositions.get("released"))
                    .isEqualTo(firstPositions.get("released"));
        }

        @Test
        @DisplayName("a merged single position would skip a stream — the four positions do not")
        void streamsAdvanceIndependently() throws Exception {
            Long mine = createClaimedItem("WI152C mine");

            authenticate(STRANGER, strangerActorId);
            Long theirs = createItem("WI152C theirs");
            Map<String, Object> payload = new HashMap<>();
            payload.put("to_handle", AGENT_HANDLE);
            payload.put("context", "the item is ready for the next step");
            Map<String, Object> body = new HashMap<>();
            body.put("kind", "handoff");
            body.put("body", "handed to me");
            body.put("payload", payload);
            mockMvc.perform(post("/api/v1/work-items/" + theirs + "/comments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isCreated());
            authenticate(OWNER, agentActorId);

            JsonNode first = readInbox(null);
            Map<String, Long> positions = positionsOf(first.get("nextCursor").asText());
            assertThat(positions.get("mention")).isPositive();
            assertThat(positions.get("item")).isEqualTo(mine);

            // Now only the item stream can advance: claim another item. The mention position
            // must stay where it was, or the handoff would be shown again.
            Long another = createClaimedItem("WI152C another");

            JsonNode second = readInbox(first.get("nextCursor").asText());
            Map<String, Long> advanced = positionsOf(second.get("nextCursor").asText());

            assertThat(advanced.get("item")).isEqualTo(another);
            assertThat(advanced.get("mention")).isEqualTo(positions.get("mention"));
            assertThat(titlesOf(second)).containsExactly("WI152C another");
        }
    }

    // ── A bad cursor is a bad request ─────────────────────────────────────────

    @Nested
    @DisplayName("AC 152: a malformed cursor is 400, never 500")
    class InvalidCursorIs400 {

        @Test
        @DisplayName("garbage, foreign, wrong-shape and oversized cursors are all 400 naming"
                + " 'cursor'")
        void malformedCursorsAre400() throws Exception {
            List<String> bad = List.of(
                    "not-a-cursor",
                    "%%%%%%%%",
                    "item:1,comment:2",
                    "SELECT id FROM work_items WHERE id > 5",
                    // Base64URL of something that is not the cursor shape.
                    "aW52YWxpZCBjdXJzb3I=",
                    // Base64URL of a cursor with a negative position.
                    "aXRlbTotMSxjb21tZW50OjAsbWVudGlvbjowLHJlbGVhc2VkOjA",
                    // Base64URL of a position too large for a long.
                    "aXRlbTo5OTk5OTk5OTk5OTk5OTk5OTk5OTk5LGNvbW1lbnQ6MCxtZW50aW9uOjAscmVsZWFzZWQ6MA",
                    "x".repeat(4000));

            for (String cursor : bad) {
                String responseBody = mockMvc.perform(get("/api/v1/actors/" + agentActorId
                                + "/inbox?cursor=" + urlEncode(cursor)))
                        .andExpect(status().isBadRequest())
                        .andReturn().getResponse().getContentAsString();
                assertThat(responseBody)
                        .as("cursor=%s must be answered with a 400 that names the parameter it"
                                + " rejected, not with a 500", cursor)
                        .contains("cursor");
            }
        }

        @Test
        @DisplayName("a cursor from a different endpoint is not accepted")
        void aPageTokenIsNotACursor() throws Exception {
            // The work item timeline paginates by page number; its token is not an inbox
            // cursor and must not be quietly interpreted as one.
            mockMvc.perform(get("/api/v1/actors/" + agentActorId + "/inbox?cursor=" + urlEncode("page=2&size=50")))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("an empty cursor is the first page, not an error — the endpoint's own"
                + " query string is ?cursor=&limit=")
        void emptyCursorIsTheFirstPage() throws Exception {
            Long id = createClaimedItem("WI152C empty cursor");

            mockMvc.perform(get("/api/v1/actors/" + agentActorId + "/inbox?cursor="))
                    .andExpect(status().isOk());

            assertThat(itemsOf(readInbox(""))).isNotEmpty();
        }

        @Test
        @DisplayName("a valid cursor of an actor that is not mine is 403, not 400")
        void accessIsCheckedEvenWithAPerfectCursor() throws Exception {
            Long id = createClaimedItem("WI152C access with cursor");
            String cursor = readInbox(null).get("nextCursor").asText();

            authenticate(STRANGER, strangerActorId);
            mockMvc.perform(get("/api/v1/actors/" + agentActorId + "/inbox?cursor=" + urlEncode(cursor)))
                    .andExpect(status().isForbidden());
        }
    }

    // ── The cursor is opaque ──────────────────────────────────────────────────

    @Nested
    @DisplayName("AC 152: the cursor is opaque")
    class CursorIsOpaque {

        @Test
        @DisplayName("the cursor is not SQL, not a class name, not a serialized object")
        void cursorCarriesNoSqlNoClassNameNoSerializedObject() throws Exception {
            Long id = createClaimedItem("WI152C opaque");
            postComment(id, "opaque cursor proof");

            String cursor = readInbox(null).get("nextCursor").asText();
            String lower = cursor.toLowerCase();

            assertThat(lower)
                    .as("a cursor that is a SQL fragment is an injection surface")
                    .doesNotContain("select")
                    .doesNotContain("from")
                    .doesNotContain("where")
                    .doesNotContain("order by")
                    .doesNotContain("offset")
                    .doesNotContain("'");
            assertThat(lower)
                    .as("'rO0' is the Base64 magic of a Java serialized object; a serialized"
                            + " cursor is a deserialisation surface and a format that cannot"
                            + " survive a refactor")
                    .doesNotContain("r00")
                    .doesNotContain("aced");
            assertThat(cursor)
                    .as("the cursor names no class of this project")
                    .doesNotContain("com.wiki4ai")
                    .doesNotContain("InboxCursor")
                    .doesNotContain("DTO");
        }

        @Test
        @DisplayName("what it actually is: four numbers in a fixed order, Base64URL")
        void cursorIsFourNumbersInAFixedOrder() throws Exception {
            Long id = createClaimedItem("WI152C shape");
            postComment(id, "shape proof");

            String cursor = readInbox(null).get("nextCursor").asText();
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);

            assertThat(decoded).matches(CURSOR_SHAPE);
            assertThat(decoded)
                    .as("plain numbers: no timestamp, no id of an item, no object graph")
                    .isEqualTo("item:" + id + ",comment:" + positionsOf(cursor).get("comment")
                            + ",mention:0,released:0");
        }

        @Test
        @DisplayName("the cursor is not a timestamp either — it is a keyset position")
        void cursorIsNotATimestamp() throws Exception {
            Long id = createClaimedItem("WI152C not a timestamp");

            String cursor = readInbox(null).get("nextCursor").asText();
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);

            assertThat(decoded)
                    .as("a timestamp cannot be a keyset position: two rows written in the same"
                            + " microsecond would be skipped or repeated, and a cursor that"
                            + " carried one would drift with the clock")
                    .doesNotContain("T")
                    .doesNotContain("-")
                    .doesNotContain(".")
                    .matches("^(item|comment|mention|released):\\d+(,(item|comment|mention|released):\\d+){3}$");
            assertThat(positionsOf(cursor).get("item")).isEqualTo(id);
        }
    }

    // ── AC 154: the shape of the reads ────────────────────────────────────────

    @Nested
    @DisplayName("AC 154: no OFFSET, no pattern matching")
    class NoOffset {

        @Test
        @DisplayName("no inbox repository method is offset- or pattern-based")
        void noRepositoryMethodUsesOffsetOrLike() {
            for (Class<?> repository : List.of(WorkItemRepository.class,
                    WorkItemCommentRepository.class, WorkItemEventRepository.class)) {
                for (Method method : repository.getDeclaredMethods()) {
                    String name = method.getName().toLowerCase();
                    assertThat(name)
                            .as("%s of %s: the inbox pages forward by id, and matches whole"
                                    + " values (AC 154)", method.getName(), repository.getSimpleName())
                            .doesNotContain("offset")
                            .doesNotContain("like")
                            .doesNotContain("containing")
                            .doesNotContain("starting")
                            .doesNotContain("payload");
                }
            }
        }

        @Test
        @DisplayName("the JPQL the inbox added carries no OFFSET and no LIKE")
        void addedJpqlIsKeysetShaped() {
            for (Method method : WorkItemRepository.class.getDeclaredMethods()) {
                org.springframework.data.jpa.repository.Query query =
                        method.getAnnotation(org.springframework.data.jpa.repository.Query.class);
                if (query == null || !method.getName().startsWith("findIds")) {
                    continue;
                }
                String jpql = query.value().toLowerCase();
                assertThat(jpql)
                        .as("%s: %s", method.getName(), query.value())
                        .doesNotContain("offset")
                        .doesNotContain("like");
                assertThat(jpql).contains("order by w.id");
            }
        }

        @Test
        @DisplayName("WorkItemCommentRepository and WorkItemEventRepository gained no @Query at all")
        void commentAndEventRepositoriesDeclareNoQuery() {
            for (Class<?> repository : List.of(WorkItemCommentRepository.class,
                    WorkItemEventRepository.class)) {
                List<String> annotated = new ArrayList<>();
                for (Method method : repository.getDeclaredMethods()) {
                    if (method.isAnnotationPresent(org.springframework.data.jpa.repository.Query.class)) {
                        annotated.add(method.getName());
                    }
                }
                assertThat(annotated)
                        .as("%s: a @Query here is where a LIKE on the payload TEXT would appear"
                                + " (WIKI4AI-151's proof, kept intact)", repository.getSimpleName())
                        .isEmpty();
            }
        }
    }
}
