package com.wiki4ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wiki4ai.config.JwtAuthenticationFilter.ApiTokenAuthenticationDetails;
import com.wiki4ai.dto.ProjectCreateDTO;
import com.wiki4ai.dto.ProjectDTO;
import com.wiki4ai.model.Actor;
import com.wiki4ai.model.Document;
import com.wiki4ai.model.Permission;
import com.wiki4ai.model.Role;
import com.wiki4ai.model.User;
import com.wiki4ai.repository.ActorRepository;
import com.wiki4ai.repository.DocumentRepository;
import com.wiki4ai.repository.ProjectPermissionRepository;
import com.wiki4ai.repository.ProjectRepository;
import com.wiki4ai.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for document attribution (WIKI4AI-134) against the real H2
 * database and the real {@link DocumentService} / {@link RequestActorResolver}.
 * <p>
 * The caller is put on the request the way {@code JwtAuthenticationFilter} puts it
 * there: an authentication whose details are an {@link ApiTokenAuthenticationDetails}
 * carrying the actor the token was issued for. A legacy JWT is the same shape with
 * {@code details == null}. The security-critical assertions are the ones that send an
 * actor id through the body, a query parameter and a header and prove none of it lands
 * in the database.
 * <p>
 * MockMvc runs without the security filter chain here ({@code addFilters = false}) so
 * the authentication under test is exactly the one this test installs.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
// NOT @DirtiesContext, deliberately: every test context in this suite points at the
// same named in-memory database (jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1) and runs with
// ddl-auto=create-drop, so closing one context DROPS THE SCHEMA that the other, still
// open contexts are using — they then fail with "Table ... not found (this database is
// empty)". Verified: adding DirtiesContext here broke ApiTokenServiceTest and
// DocumentDeleteUnscheduleRegressionTest. Isolation is therefore done at the row level
// in cleanState() below, which is order-independent and touches nobody else's schema.
class DocumentActorAttributionIntegrationTest {

    private static final String OWNER = "attr_owner";
    private static final String PLAIN_USER = "attr_plain";
    private static final String PROJECT_SLUG = "attribution-project";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private ProjectPermissionRepository projectPermissionRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private ActorRepository actorRepository;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private PermissionService permissionService;

    private Long agentAId;
    private Long agentBId;
    private Long humanAId;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        cleanState();

        User owner = saveUser(OWNER, Role.USER);
        saveUser(PLAIN_USER, Role.USER);

        agentAId = saveActor("attr-agent-a", Actor.KIND_AGENT, owner.getId()).getId();
        agentBId = saveActor("attr-agent-b", Actor.KIND_AGENT, owner.getId()).getId();
        humanAId = saveActor("attr-human-a", Actor.KIND_HUMAN, owner.getId()).getId();

        ProjectDTO project = projectService.createProject(
                ProjectCreateDTO.builder().name("Attribution Project").build(), OWNER);
        assertThat(project.getSlug()).isEqualTo(PROJECT_SLUG);

        // PLAIN_USER needs write access to the project to be a document caller.
        permissionService.grantPermissions(PLAIN_USER, project.getId(), List.of(Permission.MANAGE));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        cleanState();
    }

    /**
     * The class writes users, actors, projects, permissions and documents into the
     * H2 database the whole test JVM shares ({@code jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1}).
     * Left behind, those rows make their users undeletable by anybody else — a user
     * owning a document, a permission or an actor cannot be removed — and that breaks
     * tests whose premise is "this instance has no accounts yet", {@code AuthFirstRunFlowTest}
     * above all.
     * <p>
     * Cleaning up after every test, children first, makes the result independent of
     * surefire's run order — a filesystem order, and a fresh CI checkout does not list
     * files in the order a local working tree created them.
     */
    private void cleanState() {
        documentRepository.deleteAll();
        projectPermissionRepository.deleteAll();
        actorRepository.deleteAll();
        projectRepository.deleteAll();
        for (String name : List.of(OWNER, PLAIN_USER)) {
            userRepository.findByUsername(name).ifPresent(userRepository::delete);
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

    private Actor saveActor(String handle, String kind, Long ownerUserId) {
        Actor actor = new Actor();
        actor.setHandle(handle);
        actor.setKind(kind);
        actor.setOwnerUserId(ownerUserId);
        return actorRepository.save(actor);
    }

    /** Install an authentication exactly as the JWT filter does for a w4a_ token. */
    private void authenticate(String username, Long actorId, List<String> scopes) {
        authenticateWithDetails(username,
                new ApiTokenAuthenticationDetails(1L, userIdOf(username), actorId, scopes));
    }

    /** Legacy JWT: authenticated, details null. */
    private void authenticateLegacy(String username) {
        authenticateWithDetails(username, null);
    }

    private void authenticateWithDetails(String username, ApiTokenAuthenticationDetails details) {
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                username, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        authentication.setDetails(details);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private Long userIdOf(String username) {
        return userRepository.findByUsername(username).orElseThrow().getId();
    }

    private Document reload(Long documentId) {
        return documentRepository.findById(documentId).orElseThrow();
    }

    private Long createDocument(String username, Long actorId, Map<String, Object> body) throws Exception {
        authenticate(username, actorId, List.of("read", "write"));
        MvcResult result = mockMvc.perform(post("/api/v1/projects/" + PROJECT_SLUG + "/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private Map<String, Object> docBody(String title, String content) {
        Map<String, Object> body = new HashMap<>();
        body.put("title", title);
        body.put("content", content);
        return body;
    }

    // ── Attribution on write ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Attribution is recorded from the credential")
    class AttributionIsRecorded {

        @Test
        @DisplayName("A document created by an agent token carries that token's actor")
        void agentCreateRecordsItsActor() throws Exception {
            Long id = createDocument(OWNER, agentAId, docBody("Agent Doc", "written by an agent"));

            Document document = reload(id);
            assertThat(document.getCreatedByActorId()).isEqualTo(agentAId);
            assertThat(document.getUpdatedByActorId()).isNull();
        }

        @Test
        @DisplayName("A document updated by a different actor records the second actor")
        void agentUpdateRecordsTheOtherActor() throws Exception {
            Long id = createDocument(OWNER, agentAId, docBody("Shared Doc", "first word"));

            authenticate(OWNER, agentBId, List.of("read", "write"));
            mockMvc.perform(put("/api/v1/projects/" + PROJECT_SLUG + "/documents/shared-doc")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("content", "second word"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.createdByActorId").value(agentAId))
                    .andExpect(jsonPath("$.updatedByActorId").value(agentBId));

            Document document = reload(id);
            assertThat(document.getCreatedByActorId())
                    .as("the creating actor is never rewritten by a later update")
                    .isEqualTo(agentAId);
            assertThat(document.getUpdatedByActorId()).isEqualTo(agentBId);
        }

        @Test
        @DisplayName("A human with their own human actor is attributed to it")
        void humanIsAttributedToTheirHumanActor() throws Exception {
            authenticateLegacy(OWNER);

            Long id = createDocumentKeepingLegacy(OWNER, docBody("Human Doc", "written by a human"));

            Document document = reload(id);
            assertThat(document.getCreatedByActorId()).isEqualTo(humanAId);
        }

        @Test
        @DisplayName("A human without a human actor leaves both columns null")
        void humanWithoutActorLeavesColumnsNull() throws Exception {
            Long id = createDocument(PLAIN_USER, null, docBody("Plain Doc", "no actor anywhere"));

            Document document = reload(id);
            assertThat(document.getCreatedByActorId()).isNull();
            assertThat(document.getUpdatedByActorId()).isNull();
            assertThat(document.getTitle()).isEqualTo("Plain Doc");
        }

        @Test
        @DisplayName("Attribution is additive: owner_id still records the user")
        void ownerIdIsUntouched() throws Exception {
            Long id = createDocument(OWNER, agentAId, docBody("Owned Doc", "owner is a user"));

            Document document = reload(id);
            assertThat(document.getOwner()).isNotNull();
            assertThat(document.getOwner().getId()).isEqualTo(userIdOf(OWNER));
            assertThat(document.getCreatedByActorId()).isEqualTo(agentAId);
        }
    }

    // ── The security AC: nothing on the request can name an actor ─────────────

    @Nested
    @DisplayName("An actor id sent by the client is ignored")
    class ClientSuppliedActorIsIgnored {

        @Test
        @DisplayName("createdByActorId in the request body is ignored — the token's actor wins")
        void bodyCannotChooseTheActor() throws Exception {
            Map<String, Object> body = docBody("Injected Doc", "trying to name another actor");
            body.put("createdByActorId", agentBId);
            body.put("updatedByActorId", agentBId);

            Long id = createDocument(OWNER, agentAId, body);

            Document document = reload(id);
            assertThat(document.getCreatedByActorId())
                    .as("the value came from the verified credential, not from the body")
                    .isEqualTo(agentAId)
                    .isNotEqualTo(agentBId);
            assertThat(document.getUpdatedByActorId()).isNull();
        }

        @Test
        @DisplayName("createdByActorId in the body is ignored when the caller has no actor at all")
        void bodyCannotCreateAnActorOutOfNothing() throws Exception {
            Map<String, Object> body = docBody("Injected Doc 2", "no credential actor to fall back on");
            body.put("createdByActorId", agentAId);

            Long id = createDocument(PLAIN_USER, null, body);

            Document document = reload(id);
            assertThat(document.getCreatedByActorId())
                    .as("a body field cannot mint attribution")
                    .isNull();
        }

        @Test
        @DisplayName("A query parameter and a header cannot name the actor either")
        void queryAndHeaderCannotChooseTheActor() throws Exception {
            authenticateLegacy(PLAIN_USER);

            mockMvc.perform(post("/api/v1/projects/" + PROJECT_SLUG + "/documents")
                            .param("createdByActorId", String.valueOf(agentAId))
                            .header("X-Actor-Id", String.valueOf(agentAId))
                            .header("X-Wiki4ai-Actor", String.valueOf(agentAId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(docBody("Header Doc", "no actor"))))
                    .andExpect(status().isCreated())
                    // The DTO omits a null attribution, so the field is absent —
                    // which is the same answer as "no actor recorded".
                    .andExpect(jsonPath("$.createdByActorId").doesNotExist())
                    .andExpect(jsonPath("$.updatedByActorId").doesNotExist());

            Document document = documentRepository.findAll().stream()
                    .filter(d -> "Header Doc".equals(d.getTitle()))
                    .findFirst().orElseThrow();
            assertThat(document.getCreatedByActorId()).isNull();
            assertThat(document.getUpdatedByActorId()).isNull();
        }

        @Test
        @DisplayName("An update body cannot name the updating actor either")
        void updateBodyCannotChooseTheActor() throws Exception {
            Long id = createDocument(OWNER, agentAId, docBody("Update Inject", "before"));

            authenticate(OWNER, agentBId, List.of("read", "write"));
            Map<String, Object> update = new HashMap<>();
            update.put("content", "after");
            update.put("updatedByActorId", agentAId);
            update.put("createdByActorId", agentBId);

            mockMvc.perform(put("/api/v1/projects/" + PROJECT_SLUG + "/documents/update-inject")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(update)))
                    .andExpect(status().isOk());

            Document document = reload(id);
            assertThat(document.getUpdatedByActorId())
                    .as("the update is attributed to the credential that made it")
                    .isEqualTo(agentBId);
            assertThat(document.getCreatedByActorId()).isEqualTo(agentAId);
        }
    }

    // Helper for the legacy branch: authenticate first, then create.
    private Long createDocumentKeepingLegacy(String username, Map<String, Object> body) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/projects/" + PROJECT_SLUG + "/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }
}
