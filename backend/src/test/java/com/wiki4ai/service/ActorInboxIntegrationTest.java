package com.wiki4ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wiki4ai.config.JwtAuthenticationFilter.ApiTokenAuthenticationDetails;
import com.wiki4ai.dto.ProjectCreateDTO;
import com.wiki4ai.dto.UnseenEventsDTO;
import com.wiki4ai.exception.BadRequestException;
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
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WIKI4AI-152 and WIKI4AI-153: the four streams of an actor's inbox, who may read it, the
 * limit of a page, and the marker that says what the actor has seen.
 *
 * <h2>What this class is not</h2>
 * It is not the cursor test. The cursor is the decision of WIKI4AI-152 and it lives in
 * {@code ActorInboxCursorIntegrationTest}, so that "two reads with the same cursor return
 * nothing" fails with a message that says exactly that.
 *
 * <h2>Rule 5b</h2>
 * {@link #cleanState()} runs in {@code @BeforeEach} AND {@code @AfterEach}, children before
 * parents: {@code work_item_comments → work_item_events → work_items → project_actors →
 * project_permissions → api_tokens → actors → projects → users}. Comments and events are
 * removed with a native {@code DELETE} because both repositories refuse {@code deleteAll()}
 * — {@code WorkItemCommentRepository} since WIKI4AI-148, {@code WorkItemEventRepository}
 * since WIKI4AI-172 — and a test fixture is allowed to do what the domain forbids, as long
 * as it says so in the SQL it runs. No {@code @DirtiesContext} anywhere: it is what broke
 * 59 tests once already.
 *
 * <h2>Why Hibernate statistics are on for this class</h2>
 * AC 154 asks for a proof that an inbox read does not fan out into one query per item, and
 * the only honest counter is Hibernate's own. It is enabled as a property of this class,
 * which gives this class its own cached context; nothing else is touched and nothing is
 * dirtied.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@DisplayName("WIKI4AI-152+153: inbox streams, access, limit, ordering, and the unseen marker")
class ActorInboxIntegrationTest {

    private static final String OWNER = "wi152_owner";
    private static final String STRANGER = "wi152_stranger";
    private static final String ADMIN = "wi152_admin";

    private static final String AGENT_HANDLE = "wi152-agent";
    private static final String STRANGER_HANDLE = "wi152-stranger-agent";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ActorInboxService actorInboxService;

    @Autowired
    private WorkItemLeaseReaper workItemLeaseReaper;

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

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private Long agentActorId;
    private Long strangerActorId;
    private Long publicProjectId;
    private Long strangerProjectId;

    private Statistics statistics;

    // ── Rule 5b ───────────────────────────────────────────────────────────────

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        cleanState();

        saveUser(OWNER, Role.USER);
        saveUser(STRANGER, Role.USER);
        saveUser(ADMIN, Role.ADMIN);

        agentActorId = saveActor(AGENT_HANDLE, OWNER).getId();
        strangerActorId = saveActor(STRANGER_HANDLE, STRANGER).getId();

        publicProjectId = createProject("WI152 Public", Project.VISIBILITY_PUBLIC, OWNER);
        strangerProjectId = createProject("WI152 Stranger", Project.VISIBILITY_PRIVATE, STRANGER);

        // Both agents are members of the public project. That is not incidental: the mention
        // stream is scoped to the projects its actor belongs to, because a comment in a
        // project the actor has no role in is a comment the actor may not read (403 on
        // GET /work-items/{id}/comments), and the inbox must not show what the item refuses.
        addProjectActor(publicProjectId, agentActorId, ProjectActor.ROLE_WORKER);
        addProjectActor(publicProjectId, strangerActorId, ProjectActor.ROLE_WORKER);

        authenticate(OWNER, agentActorId);

        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
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

    /**
     * Create an item and claim it. The claim is two calls because a created item lands in
     * 'backlog' ({@link com.wiki4ai.model.WorkItem#DEFAULT_STATUS}) and 'claimed' is only
     * reachable from 'ready' — which is also why the claim is not a status a create may set.
     */
    private Long createClaimedItem(String title, Long projectId) throws Exception {
        Long id = createItem(title, projectId);
        transitionItem(id, "ready");
        claimItem(id);
        return id;
    }

    private void claimItem(Long id) throws Exception {
        mockMvc.perform(post("/api/v1/work-items/" + id + "/claim"))
                .andExpect(status().isOk());
    }

    private void transitionItem(Long id, String statusValue) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("status", statusValue);
        mockMvc.perform(put("/api/v1/work-items/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());
    }

    private void postComment(Long itemId, String kind, String text, Map<String, Object> payload)
            throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("kind", kind);
        body.put("body", text);
        if (payload != null) {
            body.put("payload", payload);
        }
        mockMvc.perform(post("/api/v1/work-items/" + itemId + "/comments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());
    }

    private JsonNode readInbox(String query) throws Exception {
        return readInbox(agentActorId, query);
    }

    private JsonNode readInbox(Long actorId, String query) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/actors/" + actorId + "/inbox" + query))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /** The items of one stream, in the order the response carried them. */
    private List<JsonNode> streamOf(JsonNode inbox, String stream) {
        List<JsonNode> items = new ArrayList<>();
        inbox.get("items").forEach(node -> {
            if (stream.equals(node.get("stream").asText())) {
                items.add(node);
            }
        });
        return items;
    }

    private List<String> bodiesOf(List<JsonNode> items) {
        List<String> bodies = new ArrayList<>();
        items.forEach(node -> bodies.add(node.get("body").asText()));
        return bodies;
    }

    private List<Long> idsOf(List<JsonNode> items) {
        List<Long> ids = new ArrayList<>();
        items.forEach(node -> ids.add(node.get("id").asLong()));
        return ids;
    }

    // ── AC 152: stream 1 ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("AC 152: items assigned to me")
    class AssignedItems {

        @Test
        @DisplayName("an item I claimed is in the 'item' stream")
        void claimedItemIsInMyInbox() throws Exception {
            Long id = createClaimedItem("WI152 claimed", publicProjectId);

            List<JsonNode> items = streamOf(readInbox(""), "item");

            assertThat(idsOf(items)).containsExactly(id);
            assertThat(items.get(0).get("workItemId").asLong()).isEqualTo(id);
            assertThat(items.get(0).get("title").asText()).isEqualTo("WI152 claimed");
            assertThat(items.get(0).get("kind").asText()).isEqualTo("claimed");
        }

        @Test
        @DisplayName("an item assigned to another actor is not in my inbox")
        void anotherActorsItemIsAbsent() throws Exception {
            authenticate(STRANGER, strangerActorId);
            Long theirs = createClaimedItem("WI152 theirs", publicProjectId);
            authenticate(OWNER, agentActorId);

            assertThat(streamOf(readInbox(""), "item")).isEmpty();
        }

        @Test
        @DisplayName("an item in a project I am not a member of is not in my inbox")
        void foreignProjectItemIsAbsent() throws Exception {
            authenticate(STRANGER, strangerActorId);
            Long foreign = createClaimedItem("WI152 foreign project", strangerProjectId);
            authenticate(OWNER, agentActorId);

            assertThat(streamOf(readInbox(""), "item")).isEmpty();
        }
    }

    // ── AC 152: stream 2 ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("AC 152: comments on the items I created or hold")
    class CommentsOnMyItems {

        @Test
        @DisplayName("a comment on my item is in the 'comment' stream, whoever wrote it")
        void commentOnMyItemIsInMyInbox() throws Exception {
            Long id = createItem("WI152 commented", publicProjectId);
            postComment(id, "note", "a note from me", null);

            authenticate(STRANGER, strangerActorId);
            postComment(id, "blocker", "blocked by something",
                    Map.of("blocking", "something", "needs_human", true));
            authenticate(OWNER, agentActorId);

            List<JsonNode> comments = streamOf(readInbox(""), "comment");

            assertThat(bodiesOf(comments))
                    .as("mine and somebody else's, both on my item, oldest first")
                    .containsExactly("a note from me", "blocked by something");
            assertThat(comments).allSatisfy(node ->
                    assertThat(node.get("workItemId").asLong()).isEqualTo(id));
        }

        @Test
        @DisplayName("a comment on an item I neither created nor hold is not in my inbox")
        void commentOnForeignItemIsAbsent() throws Exception {
            authenticate(STRANGER, strangerActorId);
            Long theirs = createItem("WI152 theirs", publicProjectId);
            postComment(theirs, "note", "not addressed to anybody", null);
            authenticate(OWNER, agentActorId);

            assertThat(streamOf(readInbox(""), "comment")).isEmpty();
        }

        @Test
        @DisplayName("the payload of a comment comes back decoded, not as TEXT")
        void payloadIsDecoded() throws Exception {
            Long id = createItem("WI152 payload", publicProjectId);
            postComment(id, "decision", "we keep the cursor per stream",
                    Map.of("what", "per-stream cursor", "why", "four id sequences",
                            "applies_to", "inbox"));

            JsonNode comment = streamOf(readInbox(""), "comment").get(0);

            assertThat(comment.get("payload").get("what").asText()).isEqualTo("per-stream cursor");
            assertThat(comment.get("payload").get("applies_to").asText()).isEqualTo("inbox");
        }
    }

    // ── AC 152: stream 3 ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("AC 152: mentions are handoffs addressed to my handle")
    class Mentions {

        @Test
        @DisplayName("a handoff to my handle is a mention — no new feature was invented for it")
        void handoffToMyHandleIsAMention() throws Exception {
            // Written by the other agent, on the other agent's item, in a project both belong
            // to. That is what a handoff is: work passed on. The row is a
            // work_item_comments row of kind 'handoff' — the same row WIKI4AI-148 stores.
            authenticate(STRANGER, strangerActorId);
            Long id = createItem("WI152 handed to me", publicProjectId);
            postComment(id, "handoff", "please take this one", Map.of("to_handle", AGENT_HANDLE, "context", "the item is ready for the next step"));
            authenticate(OWNER, agentActorId);

            List<JsonNode> mentions = streamOf(readInbox(""), "mention");

            assertThat(bodiesOf(mentions)).containsExactly("please take this one");
            assertThat(mentions.get(0).get("kind").asText()).isEqualTo("handoff");
            assertThat(mentions.get(0).get("payload").get("to_handle").asText()).isEqualTo(AGENT_HANDLE);
            assertThat(mentions.get(0).get("workItemId").asLong()).isEqualTo(id);
        }

        @Test
        @DisplayName("a handoff to another actor is not my mention")
        void handoffToAnotherHandleIsAbsent() throws Exception {
            authenticate(STRANGER, strangerActorId);
            Long id = createItem("WI152 handed elsewhere", publicProjectId);
            postComment(id, "handoff", "not for you", Map.of("to_handle", STRANGER_HANDLE, "context", "the item is ready for the next step"));
            authenticate(OWNER, agentActorId);

            JsonNode inbox = readInbox("");
            assertThat(streamOf(inbox, "mention")).isEmpty();
            // And it is not smuggled in through the comment stream: the item is not mine.
            assertThat(streamOf(inbox, "comment")).isEmpty();
        }

        @Test
        @DisplayName("to_handle written in upper case still matches: handles are normalized")
        void handoffHandleIsNormalized() throws Exception {
            authenticate(STRANGER, strangerActorId);
            Long id = createItem("WI152 shouted handoff", publicProjectId);
            // The write path validates the handle case-insensitively but stores the payload
            // as it was given, so the stored value here is upper case on purpose.
            postComment(id, "handoff", "shouted at me", Map.of("to_handle", "WI152-AGENT", "context", "the item is ready for the next step"));
            authenticate(OWNER, agentActorId);

            List<JsonNode> mentions = streamOf(readInbox(""), "mention");

            assertThat(bodiesOf(mentions))
                    .as("'WI152-AGENT' and 'wi152-agent' are the same actor: ActorService"
                            + " normalizes the handle, and the read normalizes the payload")
                    .containsExactly("shouted at me");
            assertThat(mentions.get(0).get("payload").get("to_handle").asText())
                    .isEqualTo("WI152-AGENT");
        }

        @Test
        @DisplayName("a handoff on MY item is reported once, in the comment stream")
        void handoffOnMyOwnItemIsNotDuplicated() throws Exception {
            Long id = createItem("WI152 my own handoff", publicProjectId);
            postComment(id, "handoff", "handing my own item on", Map.of("to_handle", STRANGER_HANDLE, "context", "the item is ready for the next step"));

            JsonNode inbox = readInbox("");

            assertThat(bodiesOf(streamOf(inbox, "comment"))).containsExactly("handing my own item on");
            assertThat(streamOf(inbox, "mention")).isEmpty();
        }
    }

    // ── AC 152: stream 4 ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("AC 152: items that slipped away")
    class ReleasedItems {

        @Test
        @DisplayName("an item whose lease expired is in the 'released' stream of its old holder")
        void expiredLeaseIsInReleasedStream() throws Exception {
            Long id = createClaimedItem("WI152 slipping away", publicProjectId);
            // The lease is moved into the past so that the reaper's own sweep produces the
            // event; no event row is written by hand here.
            jdbcTemplate.update("UPDATE work_items SET lease_expires_at = ? WHERE id = ?",
                    LocalDateTime.now().minusHours(2), id);

            assertThat(workItemLeaseReaper.releaseExpired()).isGreaterThanOrEqualTo(1);

            List<JsonNode> released = streamOf(readInbox(""), "released");

            assertThat(released).hasSize(1);
            assertThat(released.get(0).get("kind").asText()).isEqualTo("lease_expired");
            assertThat(released.get(0).get("workItemId").asLong()).isEqualTo(id);
            assertThat(released.get(0).get("title").asText()).isEqualTo("WI152 slipping away");
        }

        @Test
        @DisplayName("the item is gone from the 'item' stream once the reaper took it back")
        void releasedItemLeavesTheItemStream() throws Exception {
            Long id = createClaimedItem("WI152 taken back", publicProjectId);
            assertThat(idsOf(streamOf(readInbox(""), "item"))).containsExactly(id);

            jdbcTemplate.update("UPDATE work_items SET lease_expires_at = ? WHERE id = ?",
                    LocalDateTime.now().minusHours(2), id);
            workItemLeaseReaper.releaseExpired();

            assertThat(idsOf(streamOf(readInbox(""), "item"))).isEmpty();
            assertThat(idsOf(streamOf(readInbox(""), "released"))).hasSize(1);
        }

        @Test
        @DisplayName("'claim_released' is NOT a released item: its actor is the one who let go")
        void claimReleasedIsNotAReleasedItem() throws Exception {
            Long id = createClaimedItem("WI152 handed back", publicProjectId);
            transitionItem(id, "ready");

            // The event exists and it names this actor — as the actor that ACTED.
            assertThat(workItemEventRepository.findByActorIdAndIdGreaterThanOrderByIdAsc(
                            agentActorId, 0L, PageRequest.of(0, 50)))
                    .anySatisfy(event -> assertThat(event.getEvent()).isEqualTo("claim_released"));

            assertThat(streamOf(readInbox(""), "released"))
                    .as("a handoff I chose is not an item that slipped away from me")
                    .isEmpty();
        }
    }

    // ── Access: the rule, once, in the service ────────────────────────────────

    @Nested
    @DisplayName("Access: whose inbox this is")
    class Access {

        @Test
        @DisplayName("my own actor's inbox is mine")
        void ownActorInboxIsReadable() throws Exception {
            mockMvc.perform(get("/api/v1/actors/" + agentActorId + "/inbox"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("an ADMIN user reads any actor's inbox")
        void adminReadsAnyInbox() throws Exception {
            authenticate(ADMIN, null);
            mockMvc.perform(get("/api/v1/actors/" + agentActorId + "/inbox"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("another actor's inbox is 403")
        void foreignActorInboxIs403() throws Exception {
            authenticate(STRANGER, strangerActorId);
            mockMvc.perform(get("/api/v1/actors/" + agentActorId + "/inbox"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("a credential that names no actor gets 403, not an empty 200")
        void credentialWithoutActorIs403() throws Exception {
            // A legacy JWT: authenticated, but its details name no actor.
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    OWNER, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));

            mockMvc.perform(get("/api/v1/actors/" + agentActorId + "/inbox"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("an actor that does not exist is 404")
        void missingActorIs404() throws Exception {
            mockMvc.perform(get("/api/v1/actors/99999999/inbox"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("unseen and mark-seen follow the same rule")
        void unseenAndMarkSeenFollowTheSameRule() throws Exception {
            authenticate(STRANGER, strangerActorId);
            mockMvc.perform(get("/api/v1/actors/" + agentActorId + "/inbox/unseen"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/api/v1/actors/" + agentActorId + "/inbox/mark-seen")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isForbidden());
        }
    }

    // ── AC 152: limit ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("AC 152: limit — default 50, ceiling 50")
    class Limit {

        @Test
        @DisplayName("no limit means 50")
        void defaultLimitIs50() throws Exception {
            assertThat(readInbox("").get("limit").asInt()).isEqualTo(50);
        }

        @Test
        @DisplayName("limit=51, limit=0, limit=-1 and limit=abc are 400 naming 'limit'")
        void invalidLimitsAre400() throws Exception {
            for (String value : List.of("51", "0", "-1", "abc")) {
                String body = mockMvc.perform(get("/api/v1/actors/" + agentActorId + "/inbox?limit=" + value))
                        .andExpect(status().isBadRequest())
                        .andReturn().getResponse().getContentAsString();
                assertThat(body)
                        .as("limit=%s must name the parameter it rejected", value)
                        .contains("limit");
            }
        }

        @Test
        @DisplayName("limit=10000 is never a page size: it is refused, and the ceiling the"
                + " service can build is 50")
        void hugeLimitNeverBecomesAPageSize() throws Exception {
            for (int i = 0; i < 5; i++) {
                createClaimedItem("WI152 cap " + i, publicProjectId);
            }

            statistics.clear();
            mockMvc.perform(get("/api/v1/actors/" + agentActorId + "/inbox?limit=10000"))
                    .andExpect(status().isBadRequest());

            assertThat(statistics.getPrepareStatementCount())
                    .as("10000 never reaches a query: the request is refused before any"
                            + " statement is prepared")
                    .isZero();

            assertThat(ActorInboxService.class.getDeclaredField("MAX_LIMIT").getInt(null))
                    .as("the ceiling is a constant of the service, not only a controller check")
                    .isEqualTo(50);

            assertThatThrownBy(() -> actorInboxService.getInbox(agentActorId, null, 10000, OWNER))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("limit");
        }

        @Test
        @DisplayName("limit=50 is accepted, and a page of 60 assigned items returns 50")
        void pageIsCappedAt50Rows() throws Exception {
            mockMvc.perform(get("/api/v1/actors/" + agentActorId + "/inbox?limit=50"))
                    .andExpect(status().isOk());

            for (int i = 0; i < 60; i++) {
                createClaimedItem("WI152 bulk " + i, publicProjectId);
            }

            JsonNode inbox = readInbox("?limit=50");

            assertThat(streamOf(inbox, "item")).hasSize(50);
            assertThat(inbox.get("limit").asInt()).isEqualTo(50);
            assertThat(inbox.get("totalElements").asInt()).isEqualTo(50);
        }
    }

    // ── AC 152: ordering ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("AC 152: ordering — id inside a stream, created_at across streams")
    class Ordering {

        @Test
        @DisplayName("inside a stream the items are ordered by their monotone id")
        void eachStreamIsOrderedById() throws Exception {
            Long id = createItem("WI152 ordering", publicProjectId);
            for (int i = 0; i < 4; i++) {
                postComment(id, "note", "note " + i, null);
            }

            assertThat(idsOf(streamOf(readInbox(""), "comment"))).isSorted();
            assertThat(idsOf(streamOf(readInbox(""), "comment"))).hasSize(4);
        }

        @Test
        @DisplayName("across streams the order is created_at, not id — ids of different tables"
                + " are not the same moment")
        void mergedOrderIsChronologicalAndNotById() throws Exception {
            Long mine = createClaimedItem("WI152 mine", publicProjectId);

            // A comment row with a SMALL id and a LATE timestamp, written past the API. This
            // is exactly the case a merged-by-id order gets wrong.
            jdbcTemplate.update("INSERT INTO work_item_comments (work_item_id, author_actor_id,"
                            + " kind, body, created_at) VALUES (?, ?, ?, ?, ?)",
                    mine, agentActorId, "note", "written tomorrow", LocalDateTime.now().plusDays(1));

            Long later = createClaimedItem("WI152 later", publicProjectId);

            JsonNode inbox = readInbox("");
            List<JsonNode> all = new ArrayList<>();
            inbox.get("items").forEach(all::add);

            assertThat(all).extracting(node -> node.get("stream").asText())
                    .as("the future-dated comment is LAST even though its id is the smallest of"
                            + " the three rows")
                    .containsExactly("item", "item", "comment");
            assertThat(bodiesOf(streamOf(inbox, "comment"))).containsExactly("written tomorrow");

            long commentId = streamOf(inbox, "comment").get(0).get("id").asLong();
            long laterItemId = streamOf(inbox, "item").get(1).get("id").asLong();
            assertThat(commentId)
                    .as("ordering by id would put the comment before an item created a day"
                            + " earlier — which is why the merge is chronological")
                    .isLessThan(laterItemId);
        }

        @Test
        @DisplayName("the response carries created_at for every item of every stream")
        void everyItemCarriesCreatedAt() throws Exception {
            Long id = createClaimedItem("WI152 timestamps", publicProjectId);
            postComment(id, "note", "a note", null);

            JsonNode inbox = readInbox("");
            List<JsonNode> all = new ArrayList<>();
            inbox.get("items").forEach(all::add);

            assertThat(all).isNotEmpty();
            assertThat(all).allSatisfy(node -> assertThat(node.get("createdAt").asText()).isNotBlank());
        }
    }

    // ── AC 153: the marker ────────────────────────────────────────────────────

    @Nested
    @DisplayName("AC 153: unseen events and the last_seen_event_id marker")
    class Marker {

        @Test
        @DisplayName("the marker column is on actors, nullable, with no default")
        void markerColumnIsOnActorsAndNullable() {
            List<Map<String, Object>> columns = jdbcTemplate.queryForList(
                    "SELECT table_name, column_name, is_nullable, column_default, data_type"
                            + " FROM information_schema.columns"
                            + " WHERE upper(column_name) = 'LAST_SEEN_EVENT_ID'");

            assertThat(columns)
                    .as("the deviation SPEC records: the marker belongs to the actor whose inbox"
                            + " it marks, not to the user behind it")
                    .hasSize(1);
            assertThat(columns.get(0).get("TABLE_NAME")).isEqualTo("ACTORS");
            assertThat(columns.get(0).get("IS_NULLABLE")).isEqualTo("YES");
            assertThat(columns.get(0).get("DATA_TYPE").toString().toUpperCase()).isEqualTo("BIGINT");
            assertThat(columns.get(0).get("COLUMN_DEFAULT"))
                    .as("no default: NULL has to mean 'never read', and a default would invent a"
                            + " position in the event sequence")
                    .isNull();

            assertThat(jdbcTemplate.queryForList(
                    "SELECT column_name FROM information_schema.columns"
                            + " WHERE upper(table_name) = 'USERS'"
                            + " AND upper(column_name) = 'LAST_SEEN_EVENT_ID'"))
                    .as("and nothing was added to users")
                    .isEmpty();
        }

        @Test
        @DisplayName("an actor that never read its inbox has a NULL marker, not 0")
        void freshActorHasNullMarker() {
            UnseenEventsDTO unseen = actorInboxService.getUnseen(agentActorId, OWNER);

            assertThat(unseen.getLastSeenEventId()).isNull();
            assertThat(marker()).isNull();
        }

        @Test
        @DisplayName("unseen reports the count and the ids of this actor's events")
        void unseenReportsCountAndIds() throws Exception {
            createItem("WI152 unseen one", publicProjectId);
            createItem("WI152 unseen two", publicProjectId);

            UnseenEventsDTO unseen = actorInboxService.getUnseen(agentActorId, OWNER);

            assertThat(unseen.getCount()).isEqualTo(2);
            assertThat(unseen.getEventIds()).hasSize(2);
            assertThat(unseen.getEventIds()).isSorted();
            assertThat(unseen.getNewestEventId()).isEqualTo(unseen.getEventIds().get(1));
            assertThat(unseen.getLastSeenEventId()).isNull();
        }

        @Test
        @DisplayName("mark-seen, then unseen → 0")
        void markSeenEmptiesTheUnseenCount() throws Exception {
            createItem("WI152 mark one", publicProjectId);
            createItem("WI152 mark two", publicProjectId);

            assertThat(actorInboxService.getUnseen(agentActorId, OWNER).getCount()).isPositive();

            mockMvc.perform(post("/api/v1/actors/" + agentActorId + "/inbox/mark-seen"))
                    .andExpect(status().isNoContent());

            UnseenEventsDTO after = actorInboxService.getUnseen(agentActorId, OWNER);
            assertThat(after.getCount()).isZero();
            assertThat(after.getEventIds()).isEmpty();
            assertThat(after.getLastSeenEventId())
                    .as("the first mark advances to the highest id, not to 0")
                    .isEqualTo(after.getNewestEventId())
                    .isPositive();
        }

        @Test
        @DisplayName("mark-seen is idempotent: a second call changes nothing and is not an error")
        void markSeenIsIdempotent() throws Exception {
            createItem("WI152 idempotent", publicProjectId);

            mockMvc.perform(post("/api/v1/actors/" + agentActorId + "/inbox/mark-seen"))
                    .andExpect(status().isNoContent());
            Long markerAfterFirst = marker();

            mockMvc.perform(post("/api/v1/actors/" + agentActorId + "/inbox/mark-seen"))
                    .andExpect(status().isNoContent());

            assertThat(marker()).isEqualTo(markerAfterFirst);
            assertThat(actorInboxService.getUnseen(agentActorId, OWNER).getCount()).isZero();
        }

        @Test
        @DisplayName("eventId=999999999 cannot push the marker into the future")
        void markerCannotBeAdvancedBeyondTheHighestExistingEvent() throws Exception {
            createItem("WI152 clamp", publicProjectId);
            Long newest = actorInboxService.getUnseen(agentActorId, OWNER).getNewestEventId();

            mockMvc.perform(post("/api/v1/actors/" + agentActorId + "/inbox/mark-seen")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"eventId\": 999999999}"))
                    .andExpect(status().isNoContent());

            assertThat(marker())
                    .as("the server decides the marker: nobody may acknowledge events that do"
                            + " not exist yet")
                    .isEqualTo(newest)
                    .isLessThan(999999999L);
        }

        @Test
        @DisplayName("the marker never moves backwards")
        void markerNeverMovesBackwards() throws Exception {
            createItem("WI152 backwards", publicProjectId);
            mockMvc.perform(post("/api/v1/actors/" + agentActorId + "/inbox/mark-seen"))
                    .andExpect(status().isNoContent());
            Long marker = marker();

            mockMvc.perform(post("/api/v1/actors/" + agentActorId + "/inbox/mark-seen")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"eventId\": 1}"))
                    .andExpect(status().isNoContent());

            assertThat(marker()).isEqualTo(marker);
        }

        @Test
        @DisplayName("an actor with no events keeps a NULL marker instead of inventing 0")
        void noEventsMeansNoMarker() throws Exception {
            mockMvc.perform(post("/api/v1/actors/" + agentActorId + "/inbox/mark-seen"))
                    .andExpect(status().isNoContent());

            assertThat(marker()).isNull();
            UnseenEventsDTO unseen = actorInboxService.getUnseen(agentActorId, OWNER);
            assertThat(unseen.getCount()).isZero();
            assertThat(unseen.getNewestEventId()).isNull();
        }

        @Test
        @DisplayName("reading the inbox does not acknowledge anything")
        void readingTheInboxDoesNotMoveTheMarker() throws Exception {
            createItem("WI152 read only", publicProjectId);
            long unseenBefore = actorInboxService.getUnseen(agentActorId, OWNER).getCount();

            readInbox("");
            readInbox("");

            assertThat(marker()).isNull();
            assertThat(actorInboxService.getUnseen(agentActorId, OWNER).getCount()).isEqualTo(unseenBefore);
        }

        private Long marker() {
            return actorRepository.findById(agentActorId).orElseThrow().getLastSeenEventId();
        }
    }

    // ── AC 154: constant statement count ──────────────────────────────────────

    @Nested
    @DisplayName("AC 154: one inbox read, a constant number of statements")
    class NoNPlusOne {

        @Test
        @DisplayName("N=1 and N=20 items execute the same number of statements")
        void statementCountDoesNotGrowWithTheNumberOfItems() throws Exception {
            // The same shape in both measurements: one handoff from the other agent, and N of
            // my items, each with a comment of its own. The comment stream is ONE query over
            // N item ids, and the titles are ONE query over the same ids.
            authenticate(STRANGER, strangerActorId);
            Long handedOver = createItem("WI152 handed over", publicProjectId);
            postComment(handedOver, "handoff", "take this", Map.of("to_handle", AGENT_HANDLE, "context", "the item is ready for the next step"));
            authenticate(OWNER, agentActorId);

            Long first = createClaimedItem("WI152 n+1 0", publicProjectId);
            postComment(first, "note", "comment on item 0", null);
            long singleItem = measureInboxRead(1);

            for (int i = 1; i < 20; i++) {
                Long id = createClaimedItem("WI152 n+1 " + i, publicProjectId);
                postComment(id, "note", "comment on item " + i, null);
            }
            long twentyItems = measureInboxRead(20);

            // Printed so the two numbers the AC asks for are in the build output and can be
            // quoted, not just asserted.
            System.out.printf("WIKI4AI-154 N+1 proof: N=1 -> %d statements, N=20 -> %d statements%n",
                    singleItem, twentyItems);

            assertThat(twentyItems)
                    .as("20 items with 20 comments and 1 handoff: the same number of statements"
                            + " as 1 item with 1 comment. A comment query per item — the N+1"
                            + " AC 154 forbids — would have made this number grow with N.")
                    .isEqualTo(singleItem);
            assertThat(singleItem)
                    .as("and the constant is small: a handful of reads, not a read per row")
                    .isBetween(1L, 12L);
        }

        /** One inbox read, measured as the statements Hibernate prepared for it. */
        private long measureInboxRead(int expectedItems) throws Exception {
            statistics.clear();
            JsonNode inbox = readInbox("?limit=50");
            long statements = statistics.getPrepareStatementCount();

            assertThat(streamOf(inbox, "item")).hasSize(expectedItems);
            return statements;
        }
    }

    // ── AC 154: proofs about how the reads are written ────────────────────────

    @Nested
    @DisplayName("AC 154: proofs")
    class Proofs {

        @Test
        @DisplayName("WorkItemCommentRepository and WorkItemEventRepository declare no @Query,"
                + " so neither can pattern-match a payload")
        void inboxRepositoriesDeclareNoQueryAnnotation() {
            for (Class<?> repository : List.of(WorkItemCommentRepository.class,
                    WorkItemEventRepository.class)) {
                for (Method method : repository.getDeclaredMethods()) {
                    assertThat(method.isAnnotationPresent(
                            org.springframework.data.jpa.repository.Query.class))
                            .as("%s of %s: every read of these two tables is a derived query,"
                                    + " which is what 'the payload TEXT is never pattern-matched'"
                                    + " means in this codebase", method.getName(),
                                    repository.getSimpleName())
                            .isFalse();
                }
            }
        }

        @Test
        @DisplayName("no inbox read uses OFFSET, and no inbox read pattern-matches")
        void noInboxReadUsesOffsetOrLike() {
            for (Class<?> repository : List.of(WorkItemRepository.class,
                    WorkItemCommentRepository.class, WorkItemEventRepository.class)) {
                for (Method method : repository.getDeclaredMethods()) {
                    String name = method.getName().toLowerCase();
                    assertThat(name)
                            .as("%s of %s must paginate by keyset and match whole values"
                                    + " (AC 154)", method.getName(), repository.getSimpleName())
                            .doesNotContain("offset")
                            .doesNotContain("like")
                            .doesNotContain("containing")
                            .doesNotContain("starting");
                }
            }

            for (Method method : WorkItemRepository.class.getDeclaredMethods()) {
                org.springframework.data.jpa.repository.Query query =
                        method.getAnnotation(org.springframework.data.jpa.repository.Query.class);
                if (query != null && method.getName().startsWith("findIds")) {
                    assertThat(query.value().toLowerCase())
                            .as("%s JPQL: the id projections the inbox scopes its comment reads"
                                    + " with", method.getName())
                            .doesNotContain("offset")
                            .doesNotContain(" like ");
                }
            }
        }

        @Test
        @DisplayName("the inbox service reaches the database only through repositories")
        void inboxServiceHasNoRawDatabaseHandle() {
            for (Field field : ActorInboxService.class.getDeclaredFields()) {
                String type = field.getType().getName();
                assertThat(type)
                        .as("ActorInboxService field %s: the keyset shape of AC 154 has to stay"
                                + " visible in the repository interface, not hidden in SQL",
                                field.getName())
                        .doesNotContain("JdbcTemplate")
                        .doesNotContain("EntityManager")
                        .doesNotContain("SessionFactory");
            }
        }

        @Test
        @DisplayName("the inbox DTOs carry LocalDateTime, never Instant (SPEC rule 11b)")
        void inboxDtosUseLocalDateTime() {
            for (Class<?> dto : List.of(com.wiki4ai.dto.InboxItemDTO.class,
                    com.wiki4ai.dto.InboxResponseDTO.class, com.wiki4ai.dto.UnseenEventsDTO.class)) {
                for (Field field : dto.getDeclaredFields()) {
                    assertThat(field.getType().getName())
                            .as("%s of %s", field.getName(), dto.getSimpleName())
                            .doesNotContain("java.time.Instant")
                            .doesNotContain("java.util.Date");
                }
            }
        }

        @Test
        @DisplayName("nothing in the inbox path is scheduled, pushed or asynchronous")
        void inboxPathHasNoPushOrScheduling() {
            for (Class<?> clazz : List.of(ActorInboxService.class,
                    com.wiki4ai.controller.ActorInboxController.class)) {
                for (Method method : clazz.getDeclaredMethods()) {
                    assertThat(method.getAnnotations())
                            .as("%s of %s: the inbox is polled; nothing here may be @Scheduled"
                                    + " or @Async", method.getName(), clazz.getSimpleName())
                            .noneSatisfy(annotation -> assertThat(annotation.toString())
                                    .containsAnyOf("Scheduled", "Async"));
                }
            }
        }
    }
}
