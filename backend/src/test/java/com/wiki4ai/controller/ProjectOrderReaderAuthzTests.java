package com.wiki4ai.controller;

import com.wiki4ai.config.JwtUtil;
import com.wiki4ai.dto.ProjectCreateDTO;
import com.wiki4ai.dto.ProjectDTO;
import com.wiki4ai.dto.ReorderRequestDTO;
import com.wiki4ai.model.Project;
import com.wiki4ai.model.Role;
import com.wiki4ai.model.User;
import com.wiki4ai.repository.DocumentRepository;
import com.wiki4ai.repository.ProjectPermissionRepository;
import com.wiki4ai.repository.ProjectRepository;
import com.wiki4ai.repository.UserProjectOrderRepository;
import com.wiki4ai.repository.UserRepository;
import com.wiki4ai.service.ProjectService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WIKI4AI-200 follow-up: pinning and dragging a project is a personal preference, so it
 * asks for READ, not MANAGE.
 *
 * <h2>Why the level changed</h2>
 * Since WIKI4AI-200 a pin or a drag writes one row of {@code user_project_order} that
 * belongs to the caller. It does not edit the project. Under MANAGE, a user who can only
 * read a project could not push aside a project the administrator had pinned globally —
 * which is the exact defect PER-1 exists to remove, only inverted. The manager decided
 * READ; the reasoning is recorded on
 * {@code ProjectService.requireVisibleReadableProject}.
 *
 * <h2>What still requires more than READ</h2>
 * The <b>global</b> writers keep MANAGE, and {@code /admin/projects-order} stays ADMIN:
 * those renumber the curated default that every viewer without a row inherits, so they
 * change everybody's dashboard and not just the caller's.
 *
 * <h2>Why security is switched on</h2>
 * The anonymous half of this story only exists at the HTTP layer. With
 * {@code security.enabled=true} the pin and reorder routes fall through to
 * {@code anyRequest()}, so an unauthenticated caller gets 401 — the service's
 * {@code username == null} branch is reachable only because the test profile disables
 * security, and it is the branch that writes the global default.
 *
 * <h2>Why this class is named {@code *Tests} and not {@code *IT}</h2>
 * The backend has no Failsafe plugin and no surefire {@code <includes>}, so surefire runs
 * its default patterns only — {@code Test*}, {@code *Test}, {@code *Tests},
 * {@code *TestCase}. A class named {@code *IT} is therefore <b>never executed by
 * {@code mvn test}</b>; it only runs when someone selects it with {@code -Dtest=}. The
 * six pre-existing {@code *IT} classes in this package have been quietly dead that way,
 * two of them so long that they no longer even start (they autowire {@code MockMvc}
 * without {@code @AutoConfigureMockMvc}). These two classes carry the WIKI4AI-200
 * acceptance proof, so they are named to actually run. Renaming them back to {@code *IT}
 * would silently un-test this story.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "security.enabled=true")
@DisplayName("WIKI4AI-200: a reader may arrange their own project order")
class ProjectOrderReaderAuthzTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private ProjectPermissionRepository projectPermissionRepository;

    @Autowired
    private UserProjectOrderRepository userProjectOrderRepository;

    @Autowired
    private ProjectService projectService;

    private String readerToken;

    private User newUser(String username, Role role) {
        return userRepository.save(User.builder()
                .username(username)
                .email(username + "@test.local")
                .password(new BCryptPasswordEncoder().encode("not-a-real-hash"))
                .role(role)
                .build());
    }

    /** A project owned by `owner`; every other account has READ and nothing else. */
    private ProjectDTO ownedBy(String name, String owner, String visibility) {
        ProjectCreateDTO dto = new ProjectCreateDTO();
        dto.setName(name);
        dto.setDescription("reader order playground");
        dto.setVisibility(visibility);
        return projectService.createProject(dto, owner);
    }

    private List<String> manualOrder(String username) {
        return projectService.getAllProjects("manual", null, null, username).stream()
                .map(ProjectDTO::getSlug)
                .toList();
    }

    @BeforeEach
    void startFromAnEmptyWiki() {
        clearWikiRows();

        newUser("owner", Role.USER);
        newUser("reader", Role.USER);
        newUser("stranger", Role.USER);
        readerToken = jwtUtil.generateToken("reader");
    }

    @AfterEach
    void leaveNoRowsBehind() {
        clearWikiRows();
    }

    /**
     * Row-level cleanup, in defined order to respect foreign keys.
     *
     * <p>The H2 database is shared by name across the whole JVM and Spring caches its test
     * contexts, so the rows written here outlive the class unless it removes them itself.
     * Cleaning up only before itself left {@code owner}, {@code reader} and {@code stranger}
     * plus their projects for whatever ran next, and {@code AdminControllerIT} — which asserts
     * absolute user counts — cannot delete a user who still owns a project, because
     * {@code project.owner_id} holds it. That is the order-dependent failure this fixes; the
     * teardown half is what makes the suite independent of the order surefire picks.</p>
     */
    private void clearWikiRows() {
        documentRepository.deleteAll();
        userProjectOrderRepository.deleteAll();
        projectPermissionRepository.deleteAll();
        projectRepository.deleteAll();
        userRepository.deleteAll();
    }

    // =======================================================================

    @Nested
    @DisplayName("a reader with no project grant")
    class ReaderWithoutManage {

        @Test
        @DisplayName("pinning a project over HTTP answers 200 and reports it pinned")
        void readerCanPinOverHttp() throws Exception {
            ProjectDTO project = ownedBy("Readable Public", "owner", Project.VISIBILITY_PUBLIC);

            // The reader has no row in project_permissions at all. Before this change the
            // call was a 403.
            assertThat(projectPermissionRepository
                    .findByProjectIdAndUserId(project.getId(),
                            userRepository.findByUsername("reader").orElseThrow().getId()))
                    .as("the reader holds no explicit grant")
                    .isEmpty();

            mockMvc.perform(post("/api/v1/projects/" + project.getSlug() + "/pin")
                            .header("Authorization", "Bearer " + readerToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.slug").value(project.getSlug()))
                    .andExpect(jsonPath("$.pinned").value(true));
        }

        @Test
        @DisplayName("reordering a project over HTTP answers 200")
        void readerCanReorderOverHttp() throws Exception {
            ownedBy("Readable One", "owner", Project.VISIBILITY_PUBLIC);
            ProjectDTO second = ownedBy("Readable Two", "owner", Project.VISIBILITY_PUBLIC);

            mockMvc.perform(post("/api/v1/projects/" + second.getSlug() + "/reorder")
                            .header("Authorization", "Bearer " + readerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"position\":0}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.slug").value(second.getSlug()));
        }

        @Test
        @DisplayName("unpinning answers 200 too, because it is the same personal write")
        void readerCanUnpinOverHttp() throws Exception {
            ProjectDTO project = ownedBy("Readable Unpin", "owner", Project.VISIBILITY_PUBLIC);

            mockMvc.perform(post("/api/v1/projects/" + project.getSlug() + "/pin")
                            .header("Authorization", "Bearer " + readerToken))
                    .andExpect(status().isOk());
            mockMvc.perform(post("/api/v1/projects/" + project.getSlug() + "/unpin")
                            .header("Authorization", "Bearer " + readerToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.pinned").value(false));
        }

        @Test
        @DisplayName("the reader's pin moves their own list and nobody else's")
        void readersPinLeavesOtherAccountsAlone() throws Exception {
            ProjectDTO alpha = ownedBy("Shared Alpha", "owner", Project.VISIBILITY_PUBLIC);
            ownedBy("Shared Beta", "owner", Project.VISIBILITY_PUBLIC);

            // Give the stranger a customised view first, so "unchanged" is a claim about a
            // real order and not about an empty table.
            projectService.pinProject(alpha.getSlug(), "stranger");

            List<String> ownerBefore = manualOrder("owner");
            List<String> strangerBefore = manualOrder("stranger");
            List<String> readerBefore = manualOrder("reader");

            mockMvc.perform(post("/api/v1/projects/" + alpha.getSlug() + "/pin")
                            .header("Authorization", "Bearer " + readerToken))
                    .andExpect(status().isOk());

            assertThat(manualOrder("reader"))
                    .as("the reader's own list did move — otherwise the two checks below "
                            + "would pass simply because nothing happened")
                    .isNotEqualTo(readerBefore)
                    .startsWith(alpha.getSlug());
            assertThat(manualOrder("owner"))
                    .as("the owner's order is untouched by a reader's pin")
                    .isEqualTo(ownerBefore);
            assertThat(manualOrder("stranger"))
                    .as("the stranger's order is untouched too")
                    .isEqualTo(strangerBefore);
        }
    }

    // =======================================================================

    @Nested
    @DisplayName("access is still required")
    class AccessStillRequired {

        @Test
        @DisplayName("a stranger cannot pin someone else's private project")
        void strangerCannotPinForeignPrivate() throws Exception {
            ProjectDTO secret = ownedBy("Hidden Private", "owner", Project.VISIBILITY_PRIVATE);

            mockMvc.perform(post("/api/v1/projects/" + secret.getSlug() + "/pin")
                            .header("Authorization", "Bearer " + jwtUtil.generateToken("stranger")))
                    .andExpect(status().isNotFound());

            assertThat(userProjectOrderRepository.findByUserId(
                    userRepository.findByUsername("stranger").orElseThrow().getId()))
                    .as("and no row was written for the stranger")
                    .isEmpty();
        }

        @Test
        @DisplayName("a stranger cannot reorder someone else's private project")
        void strangerCannotReorderForeignPrivate() throws Exception {
            ProjectDTO secret = ownedBy("Hidden Private Reorder", "owner", Project.VISIBILITY_PRIVATE);

            mockMvc.perform(post("/api/v1/projects/" + secret.getSlug() + "/reorder")
                            .header("Authorization", "Bearer " + jwtUtil.generateToken("stranger"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"position\":0}"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("an unauthenticated pin is refused — it is not a 200")
        void anonymousPinIsNotAccepted() throws Exception {
            ProjectDTO project = ownedBy("Public For Anonymous", "owner", Project.VISIBILITY_PUBLIC);

            // SecurityConfig permits GET /projects/** to anyone, but the write routes fall
            // through to anyRequest(), so an anonymous caller never reaches the service.
            mockMvc.perform(post("/api/v1/projects/" + project.getSlug() + "/pin"))
                    .andExpect(status().isUnauthorized());

            assertThat(projectRepository.findBySlug(project.getSlug()).orElseThrow().isPinned())
                    .as("the global default was not touched by an anonymous caller")
                    .isFalse();
        }
    }
}
