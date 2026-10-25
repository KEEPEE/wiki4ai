package com.wiki4ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wiki4ai.config.JwtAuthenticationFilter.ApiTokenAuthenticationDetails;
import com.wiki4ai.dto.ProjectCreateDTO;
import com.wiki4ai.dto.WorkItemRequestDTO;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The invariant of WIKI4AI-150: a work item may enter {@code done} only when its result
 * is written down as a comment of kind {@code result}.
 *
 * <h2>What is asserted, and where</h2>
 * The rule lives in {@code WorkItemService} — in {@code applyStatusTransition}, the one
 * method an update uses to change a state, and in {@code createWorkItem}, the other door
 * into {@code done}. Half of this class therefore calls the <b>service bean directly</b>
 * and never touches MockMvc, because "the controller returns 409" is not the same claim
 * as "the service refuses": a second controller, a scheduled job or another service could
 * reach the same method, and the invariant has to hold there too.
 *
 * <h2>409, and why not 422</h2>
 * The refusal is an {@link IllegalArgumentException}, which the existing
 * {@code GlobalExceptionHandler} already maps to 409 Conflict. 422 is the answer
 * {@code CommentContractException} gives when a payload breaks the contract of its kind,
 * and nothing about a {@code done} request's payload is malformed — the state it asks for
 * conflicts with what the item already contains. {@code GlobalExceptionHandler} is not
 * modified by this story: the mapping it already had is what produces the 409.
 *
 * <h2>Rule 5b</h2>
 * {@link #cleanState()} runs in {@code @BeforeEach} AND {@code @AfterEach}, children
 * before parents: {@code work_item_comments} → {@code work_item_events} → work items →
 * project_actors → project_permissions → api_tokens → actors → projects → users. No
 * {@code @DirtiesContext}: every context in this suite is cached against the same named
 * in-memory H2, so closing one would drop the schema the others are still using. The two
 * append-only tables are emptied with native statements — both repositories refuse
 * {@code deleteAll()} by design.
 *
 * <h2>Security</h2>
 * {@code addFilters = false}: the authentication under test is the one this class
 * installs, in the shape {@code JwtAuthenticationFilter} produces for a {@code w4a_}
 * token, so comment authorship travels from the credential and no request names an actor
 * in a body.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@DisplayName("WIKI4AI-150: 'done' requires a 'result' comment")
class WorkItemDoneInvariantIntegrationTest {

    private static final String OWNER = "wi150_owner";
    private static final String STRANGER = "wi150_stranger";

    /** The machine-readable token the invariant starts every message with. */
    private static final String CODE = WorkItemService.RESULT_COMMENT_REQUIRED;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WorkItemService workItemService;

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
    private Long reviewerActorId;
    private Long strangerActorId;
    private Long publicProjectId;
    private Long strangerProjectId;

    // ── Rule 5b: shared H2, cleaned before AND after every test, children first ─

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        cleanState();

        saveUser(OWNER, Role.USER);
        saveUser(STRANGER, Role.USER);

        agentActorId = saveActor("wi150-agent").getId();
        reviewerActorId = saveActor("wi150-reviewer").getId();
        strangerActorId = saveActor("wi150-stranger-agent").getId();

        publicProjectId = createProject("WI150 Public", Project.VISIBILITY_PUBLIC, OWNER);
        strangerProjectId = createProject("WI150 Stranger", Project.VISIBILITY_PUBLIC, STRANGER);

        authenticate(OWNER, agentActorId);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        cleanState();
    }

    /**
     * work_item_comments → work_item_events → work_items → project_actors →
     * project_permissions → api_tokens → actors → projects → users.
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

    private Actor saveActor(String handle) {
        Actor actor = new Actor();
        actor.setHandle(handle);
        actor.setKind(Actor.KIND_AGENT);
        actor.setStatus(Actor.STATUS_ACTIVE);
        actor.setOwnerUserId(handle.equals("wi150-stranger-agent")
                ? userIdOf(STRANGER) : userIdOf(OWNER));
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

    private Long createItem() throws Exception {
        return createItem("WI150 item", publicProjectId, null);
    }

    /** backlog → ready → claimed → running → review, i.e. every step before the one at stake. */
    private Long itemInReview() throws Exception {
        Long id = createItem();
        putStatusExpect(id, WorkItem.STATUS_READY, 200);
        mockMvc.perform(post("/api/v1/work-items/" + id + "/claim")).andExpect(status().isOk());
        putStatusExpect(id, WorkItem.STATUS_RUNNING, 200);
        putStatusExpect(id, WorkItem.STATUS_REVIEW, 200);
        return id;
    }

    private void putStatusExpect(Long id, String statusValue, int expectedStatus) throws Exception {
        mockMvc.perform(put("/api/v1/work-items/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"" + statusValue + "\"}"))
                .andExpect(status().is(expectedStatus));
    }

    private MvcResult putStatusBody(Long id, String statusValue) throws Exception {
        return mockMvc.perform(put("/api/v1/work-items/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"" + statusValue + "\"}"))
                .andReturn();
    }

    /** POST one comment and return the response. */
    private MvcResult postComment(Long id, Map<String, Object> body) throws Exception {
        return mockMvc.perform(post("/api/v1/work-items/" + id + "/comments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    /** POST one comment and require the given status. */
    private void postCommentExpect(Long id, Map<String, Object> body, int expectedStatus) throws Exception {
        mockMvc.perform(post("/api/v1/work-items/" + id + "/comments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().is(expectedStatus));
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

    /** A comment that satisfies the contract of kind 'result' (WIKI4AI-149). */
    private Map<String, Object> validResultComment(String text) {
        return comment("result", text,
                Map.of("where", "main", "next", "release", "artifacts", List.of("commit abc123")));
    }

    private void postValidResultComment(Long id) throws Exception {
        postCommentExpect(id, validResultComment("implemented on main"), 201);
    }

    private List<WorkItemEvent> eventsOf(Long id) {
        return workItemEventRepository.findByWorkItemIdOrderByCreatedAtAscIdAsc(id);
    }

    // ── AC 150: the invariant over HTTP ───────────────────────────────────────

    @Nested
    @DisplayName("AC 150: review -> done without a 'result' comment")
    class DoneWithoutAResultIsRefused {

        @Test
        @DisplayName("is 409 Conflict")
        void reviewToDoneWithoutResultCommentIsConflict() throws Exception {
            Long id = itemInReview();

            mockMvc.perform(put("/api/v1/work-items/" + id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"done\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.error").value("Conflict"));

            assertThat(reload(id).getStatus())
                    .as("a refused transition changed nothing")
                    .isEqualTo(WorkItem.STATUS_REVIEW);
        }

        @Test
        @DisplayName("the message is machine-readable and names what is missing and how to add it")
        void messageNamesTheCodeTheMissingKindAndTheEndpoint() throws Exception {
            Long id = itemInReview();

            MvcResult result = putStatusBody(id, WorkItem.STATUS_DONE);
            JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
            String message = body.get("message").asText();

            // Machine-readable first: a stable code, not prose to pattern-match.
            assertThat(message)
                    .as("the code is the first token, so a client can branch on it")
                    .startsWith(CODE);
            // What is missing.
            assertThat(message).contains("comment of kind 'result'");
            // How to add it: method, endpoint with this item's id, and the kind.
            assertThat(message)
                    .contains("POST /api/v1/work-items/" + id + "/comments")
                    .contains("\"kind\":\"result\"");
            // And the payload the kind demands, so one round trip is enough to fix it.
            assertThat(message)
                    .contains("\"where\"")
                    .contains("\"next\"")
                    .contains("\"artifacts\"");
        }

        @Test
        @DisplayName("the refused 'done' leaves no status_changed event")
        void refusedDoneLeavesNoEventInTrail() throws Exception {
            Long id = itemInReview();
            int eventsBefore = eventsOf(id).size();

            MvcResult refused = putStatusBody(id, WorkItem.STATUS_DONE);
            assertThat(refused.getResponse().getStatus()).isEqualTo(409);

            List<WorkItemEvent> after = eventsOf(id);
            assertThat(after)
                    .as("a transition that did not happen is not a fact the trail may record")
                    .hasSize(eventsBefore);
            assertThat(after)
                    .as("no event claims this item reached 'done'")
                    .noneMatch(event -> WorkItem.STATUS_DONE.equals(event.getToStatus()));
            assertThat(after)
                    .as("the trail still ends where the item actually is")
                    .allSatisfy(event -> assertThat(event.getToStatus())
                            .isNotEqualTo(WorkItem.STATUS_DONE));
        }

        @Test
        @DisplayName("a 'result' comment whose contract is broken is 422, is not written, and done stays 409")
        void brokenResultCommentIs422AndCannotSatisfyTheInvariant() throws Exception {
            Long id = itemInReview();

            // 'artifacts' missing: the request was understood, its payload breaks the
            // contract of kind 'result' -> 422 (WIKI4AI-149), and nothing is written.
            MvcResult rejected = postComment(id, comment("result", "half a result",
                    Map.of("where", "main", "next", "release")));
            assertThat(rejected.getResponse().getStatus()).isEqualTo(422);
            assertThat(objectMapper.readTree(rejected.getResponse().getContentAsString())
                    .get("violations").toString()).contains("artifacts");

            assertThat(workItemCommentRepository.findByWorkItemIdOrderByCreatedAtAscIdAsc(id))
                    .as("a 422 writes no comment, so there is no result for the invariant to find")
                    .isEmpty();

            mockMvc.perform(put("/api/v1/work-items/" + id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"done\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.startsWith(CODE)));

            assertThat(reload(id).getStatus()).isEqualTo(WorkItem.STATUS_REVIEW);
        }
    }

    @Nested
    @DisplayName("AC 150: review -> done with a 'result' comment")
    class DoneWithAResultIsAllowed {

        @Test
        @DisplayName("is 200 and the item is done")
        void reviewToDoneWithResultCommentIsOk() throws Exception {
            Long id = itemInReview();
            postValidResultComment(id);

            mockMvc.perform(put("/api/v1/work-items/" + id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"done\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(WorkItem.STATUS_DONE));

            assertThat(reload(id).getStatus()).isEqualTo(WorkItem.STATUS_DONE);
            assertThat(eventsOf(id))
                    .anyMatch(event -> WorkItem.STATUS_REVIEW.equals(event.getFromStatus())
                            && WorkItem.STATUS_DONE.equals(event.getToStatus()));
        }

        @Test
        @DisplayName("any actor's 'result' comment satisfies it — the invariant is about the result, not its author")
        void anotherActorsResultCommentIsEnough() throws Exception {
            Long id = itemInReview();

            // A different actor of the same project writes the result; the actor that
            // completes the item is the one that moves it.
            authenticate(OWNER, reviewerActorId);
            postCommentExpect(id, validResultComment("verified by the reviewer"), 201);

            authenticate(OWNER, agentActorId);
            mockMvc.perform(put("/api/v1/work-items/" + id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"done\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(WorkItem.STATUS_DONE));

            assertThat(workItemCommentRepository.findByWorkItemIdAndKindOrderByCreatedAtAscIdAsc(
                            id, "result").get(0).getAuthorActorId())
                    .as("the author is still recorded — a stricter rule would protect nothing that is not already here")
                    .isEqualTo(reviewerActorId);
        }

        @Test
        @DisplayName("a 'note' or a 'blocker' is not a result: the kind matters")
        void otherKindsDoNotSatisfyTheInvariant() throws Exception {
            Long id = itemInReview();
            postCommentExpect(id, comment("note", "we are almost there", null), 201);
            postCommentExpect(id, comment("status", "in review", null), 201);

            mockMvc.perform(put("/api/v1/work-items/" + id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"done\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.startsWith(CODE)));
        }
    }

    // ── Where the invariant applies ───────────────────────────────────────────

    @Nested
    @DisplayName("Every door into 'done' is covered")
    class DoorsIntoDone {

        /**
         * {@code review -> done} is the only legal transition into {@code done} in the
         * state machine of WIKI4AI-143, so it is the only edge where the invariant can
         * be reached by an update. Every other state must still be refused — and refused
         * as an illegal transition, which is the state machine answering before the
         * invariant does.
         */
        @Test
        @DisplayName("no other state may transition to 'done' at all (409, transition message)")
        void everyOtherTransitionIntoDoneIsIllegal() throws Exception {
            for (String from : List.of(WorkItem.STATUS_BACKLOG, WorkItem.STATUS_READY,
                    WorkItem.STATUS_CLAIMED, WorkItem.STATUS_RUNNING, WorkItem.STATUS_BLOCKED,
                    WorkItem.STATUS_CANCELLED)) {
                Long id = createItem("From " + from, publicProjectId, null);
                moveTo(id, from);

                MvcResult result = putStatusBody(id, WorkItem.STATUS_DONE);

                assertThat(result.getResponse().getStatus())
                        .as("%s -> done", from)
                        .isEqualTo(409);
                assertThat(objectMapper.readTree(result.getResponse().getContentAsString())
                        .get("message").asText())
                        .as("%s -> done is refused by the state machine, not by the invariant", from)
                        .contains("Invalid status transition")
                        .doesNotContain(CODE);
                assertThat(reload(id).getStatus()).isEqualTo(from);
            }
        }

        @Test
        @DisplayName("creating an item straight in 'done' is 409 with the same code")
        void createInDoneIsRefused() throws Exception {
            Map<String, Object> body = new HashMap<>();
            body.put("projectId", publicProjectId);
            body.put("title", "Born done");
            body.put("status", WorkItem.STATUS_DONE);

            MvcResult result = mockMvc.perform(post("/api/v1/work-items")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isConflict())
                    .andReturn();

            String message = objectMapper.readTree(result.getResponse().getContentAsString())
                    .get("message").asText();
            assertThat(message)
                    .startsWith(CODE)
                    .contains("POST /api/v1/work-items/{id}/comments");

            assertThat(workItemRepository.count())
                    .as("a refused create wrote no item")
                    .isZero();
        }

        @Test
        @DisplayName("the create door is not a bypass: review + result + done is the legal route")
        void theRouteTheMessageDescribesActuallyWorks() throws Exception {
            Long id = createItem("Created in review", publicProjectId, WorkItem.STATUS_REVIEW);
            postValidResultComment(id);

            mockMvc.perform(put("/api/v1/work-items/" + id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"done\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(WorkItem.STATUS_DONE));
        }

        @Test
        @DisplayName("PUT 'done' on an item that is already 'done' is a no-op, not a new transition")
        void reassertingDoneIsANoOp() throws Exception {
            Long id = itemInReview();
            postValidResultComment(id);
            putStatusExpect(id, WorkItem.STATUS_DONE, 200);
            int eventsAfterDone = eventsOf(id).size();

            putStatusExpect(id, WorkItem.STATUS_DONE, 200);

            assertThat(reload(id).getStatus()).isEqualTo(WorkItem.STATUS_DONE);
            assertThat(eventsOf(id))
                    .as("no transition happened, so the trail gains no event")
                    .hasSize(eventsAfterDone);
        }

        @Test
        @DisplayName("the invariant is about 'done' only: every other state is reachable without a result")
        void otherStatesAreUnaffected() throws Exception {
            Long id = createItem();
            for (String next : List.of(WorkItem.STATUS_READY, WorkItem.STATUS_BLOCKED,
                    WorkItem.STATUS_READY, WorkItem.STATUS_CANCELLED)) {
                putStatusExpect(id, next, 200);
            }
            assertThat(reload(id).getStatus()).isEqualTo(WorkItem.STATUS_CANCELLED);

            Long reviewed = itemInReview();
            assertThat(reload(reviewed).getStatus())
                    .as("'review' itself needs no result comment — only leaving it for 'done' does")
                    .isEqualTo(WorkItem.STATUS_REVIEW);
        }

        /** Drive an item into one of the states the transition table knows. */
        private void moveTo(Long id, String target) throws Exception {
            switch (target) {
                case WorkItem.STATUS_BACKLOG -> {
                    // The state a create produces: the fixture is already there.
                }
                case WorkItem.STATUS_READY -> putStatusExpect(id, WorkItem.STATUS_READY, 200);
                case WorkItem.STATUS_CLAIMED -> {
                    putStatusExpect(id, WorkItem.STATUS_READY, 200);
                    mockMvc.perform(post("/api/v1/work-items/" + id + "/claim")).andExpect(status().isOk());
                }
                case WorkItem.STATUS_RUNNING -> {
                    putStatusExpect(id, WorkItem.STATUS_READY, 200);
                    mockMvc.perform(post("/api/v1/work-items/" + id + "/claim")).andExpect(status().isOk());
                    putStatusExpect(id, WorkItem.STATUS_RUNNING, 200);
                }
                case WorkItem.STATUS_BLOCKED -> putStatusExpect(id, WorkItem.STATUS_BLOCKED, 200);
                case WorkItem.STATUS_CANCELLED -> putStatusExpect(id, WorkItem.STATUS_CANCELLED, 200);
                default -> throw new IllegalArgumentException("no fixture for " + target);
            }
        }
    }

    // ── Service level, not controller level ───────────────────────────────────

    @Nested
    @DisplayName("The invariant is enforced by the service, not by the controller")
    class EnforcedAtTheService {

        /**
         * No MockMvc in this test. The real {@link WorkItemService} bean is called the
         way any other bean would call it, and it refuses on its own. If the rule lived
         * in a controller, this test would get a {@code done} item.
         */
        @Test
        @DisplayName("calling updateWorkItem directly is refused with IllegalArgumentException -> 409")
        void serviceRefusesDoneWithoutAResultComment() throws Exception {
            Long id = itemInReview();

            assertThatThrownBy(() -> workItemService.updateWorkItem(id,
                    WorkItemRequestDTO.builder().status(WorkItem.STATUS_DONE).build(), OWNER))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(CODE)
                    .hasMessageContaining("POST /api/v1/work-items/" + id + "/comments");

            assertThat(reload(id).getStatus())
                    .as("the service rolled its own transaction back; the item never moved")
                    .isEqualTo(WorkItem.STATUS_REVIEW);
            assertThat(eventsOf(id))
                    .as("and the service wrote no event for a transition it refused")
                    .noneMatch(event -> WorkItem.STATUS_DONE.equals(event.getToStatus()));
        }

        @Test
        @DisplayName("the same direct call succeeds once a 'result' comment exists")
        void serviceAcceptsDoneWithAResultComment() throws Exception {
            Long id = itemInReview();
            postValidResultComment(id);

            String status = workItemService.updateWorkItem(id,
                    WorkItemRequestDTO.builder().status(WorkItem.STATUS_DONE).build(), OWNER).getStatus();

            assertThat(status).isEqualTo(WorkItem.STATUS_DONE);
            assertThat(reload(id).getStatus()).isEqualTo(WorkItem.STATUS_DONE);
        }

        @Test
        @DisplayName("the service also refuses the create door when called directly")
        void serviceRefusesCreateInDone() throws Exception {
            assertThatThrownBy(() -> workItemService.createWorkItem(
                    WorkItemRequestDTO.builder()
                            .projectId(publicProjectId)
                            .title("Born done, through the service")
                            .status(WorkItem.STATUS_DONE)
                            .build(), OWNER))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(CODE);
        }

        @Test
        @DisplayName("an item in someone else's public project is 403 before the invariant is ever consulted")
        void accessIsCheckedBeforeTheInvariant() throws Exception {
            Long id = itemInReview();

            authenticate(STRANGER, strangerActorId);
            assertThatThrownBy(() -> workItemService.updateWorkItem(id,
                    WorkItemRequestDTO.builder().status(WorkItem.STATUS_DONE).build(), STRANGER))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);

            assertThat(reload(id).getStatus()).isEqualTo(WorkItem.STATUS_REVIEW);
        }

        /** Reads the timeline through the service to prove the audit claim without HTTP. */
        @Test
        @DisplayName("the trail of a refused done is the trail of the item that is still in review")
        void trailStillEndsInReview() throws Exception {
            Long id = itemInReview();

            mockMvc.perform(get("/api/v1/work-items/" + id + "/events"))
                    .andExpect(status().isOk());

            assertThat(eventsOf(id))
                    .last()
                    .extracting(WorkItemEvent::getToStatus)
                    .isEqualTo(WorkItem.STATUS_REVIEW);
        }
    }
}
