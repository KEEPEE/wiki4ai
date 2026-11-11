package com.wiki4ai.controller;

import com.wiki4ai.config.JwtUtil;
import com.wiki4ai.dto.ProjectCreateDTO;
import com.wiki4ai.dto.ProjectDTO;
import com.wiki4ai.model.Role;
import com.wiki4ai.model.User;
import com.wiki4ai.repository.DocumentRepository;
import com.wiki4ai.repository.ProjectPermissionRepository;
import com.wiki4ai.repository.ProjectRepository;
import com.wiki4ai.repository.UserProjectOrderRepository;
import com.wiki4ai.repository.UserRepository;
import com.wiki4ai.service.ProjectService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WIKI4AI-200 (PER-1): {@code GET/PUT /api/v1/admin/projects-order}.
 *
 * <p>The endpoint exists because WIKI4AI-200 turned the global
 * {@code projects.pinned} / {@code projects.sort_order} columns into the default a user
 * inherits. A default nobody can set is not a default, it is a leftover.</p>
 *
 * <h2>The test that matters most</h2>
 * {@link AdminWrite#anAdminWriteDoesNotDisturbAUserWhoAlreadyCustomisedTheirView}: the
 * story's whole premise is that one user's arrangement stops being everybody's. The same
 * asymmetry has to hold in the other direction, or the admin endpoint would silently undo
 * what PER-1 exists to give users.
 *
 * <h2>Why security is switched on</h2>
 * {@code verifyAdminRole()} reads the SecurityContext, which the test profile disables by
 * default. {@code @TestPropertySource(security.enabled=true)} is the pattern
 * {@code AdminControllerIT} established for exactly this reason.
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
@DisplayName("WIKI4AI-200: admin global project order endpoint")
class AdminProjectsOrderTests {

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

    private String adminToken;
    private String userToken;

    private User newUser(String username, Role role) {
        User user = User.builder()
                .username(username)
                .email(username + "@test.local")
                .password(new BCryptPasswordEncoder().encode("not-a-real-hash"))
                .role(role)
                .build();
        return userRepository.save(user);
    }

    private ProjectDTO createProject(String name, String owner) {
        ProjectCreateDTO dto = new ProjectCreateDTO();
        dto.setName(name);
        dto.setDescription("admin order playground");
        return projectService.createProject(dto, owner);
    }

    @BeforeEach
    void startFromAnEmptyWiki() {
        documentRepository.deleteAll();
        userProjectOrderRepository.deleteAll();
        projectPermissionRepository.deleteAll();
        projectRepository.deleteAll();
        userRepository.deleteAll();

        newUser("admin", Role.ADMIN);
        newUser("plainuser", Role.USER);
        adminToken = jwtUtil.generateToken("admin");
        userToken = jwtUtil.generateToken("plainuser");
    }

    @Nested
    @DisplayName("GET /api/v1/admin/projects-order")
    class AdminRead {

        @Test
        @DisplayName("an admin sees every project in the global manual order")
        void adminSeesTheGlobalOrder() throws Exception {
            User admin = userRepository.findByUsername("admin").orElseThrow();
            createProject("Alpha", admin.getUsername());
            createProject("Bravo", admin.getUsername());

            mockMvc.perform(get("/api/v1/admin/projects-order")
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[0].slug").exists())
                    .andExpect(jsonPath("$[0].pinned").value(false))
                    .andExpect(jsonPath("$[0].sortOrder").isNumber());
        }

        @Test
        @DisplayName("a non-admin gets 403")
        void nonAdminIsForbidden() throws Exception {
            mockMvc.perform(get("/api/v1/admin/projects-order")
                            .header("Authorization", "Bearer " + userToken))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").exists());
        }

        @Test
        @DisplayName("an unauthenticated caller gets 401")
        void anonymousIsUnauthenticated() throws Exception {
            mockMvc.perform(get("/api/v1/admin/projects-order"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("PUT /api/v1/admin/projects-order")
    class AdminWrite {

        @Test
        @DisplayName("an admin's order is what a user who customised nothing inherits")
        void adminOrderIsTheDefaultForACleanViewer() throws Exception {
            User admin = userRepository.findByUsername("admin").orElseThrow();
            ProjectDTO alpha = createProject("Alpha", admin.getUsername());
            ProjectDTO bravo = createProject("Bravo", admin.getUsername());

            String body = "{\"items\":["
                    + "{\"slug\":\"" + bravo.getSlug() + "\",\"pinned\":true,\"sortOrder\":-1},"
                    + "{\"slug\":\"" + alpha.getSlug() + "\",\"pinned\":false,\"sortOrder\":0}"
                    + "]}";

            mockMvc.perform(put("/api/v1/admin/projects-order")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].slug").value(bravo.getSlug()))
                    .andExpect(jsonPath("$[0].pinned").value(true));

            // The user who has touched nothing sees exactly what the admin arranged.
            List<ProjectDTO> asUser =
                    projectService.getAllProjects("created_desc", null, null, "plainuser");
            assertThat(asUser.get(0).getSlug())
                    .as("the global default is what a clean viewer inherits")
                    .isEqualTo(bravo.getSlug());
            assertThat(asUser.get(0).isPinned()).isTrue();

            assertThat(projectRepository.findById(bravo.getId()).orElseThrow().isPinned())
                    .as("the admin write went to the global column, which is its whole point")
                    .isTrue();
        }

        @Test
        @DisplayName("an admin write does not disturb a user who already customised their view")
        void anAdminWriteDoesNotDisturbAUserWhoAlreadyCustomisedTheirView() throws Exception {
            // Owned by plainuser, because pin keeps the WIKI4AI-184 rule (visible plus
            // MANAGE) and only the owner holds it. The admin endpoint needs no MANAGE at
            // all — it writes the global default, not a project — so the two roles still
            // meet in this one test.
            ProjectDTO alpha = createProject("Alpha", "plainuser");
            ProjectDTO bravo = createProject("Bravo", "plainuser");

            // The user pins one project, so they now have a row and no longer inherit.
            projectService.pinProject(bravo.getSlug(), "plainuser");
            assertThat(userProjectOrderRepository
                    .findByUserIdAndProjectId(
                            userRepository.findByUsername("plainuser").orElseThrow().getId(),
                            bravo.getId()))
                    .isPresent();

            String body = "{\"items\":[{\"slug\":\"" + alpha.getSlug()
                    + "\",\"pinned\":true,\"sortOrder\":-5}]}";

            mockMvc.perform(put("/api/v1/admin/projects-order")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk());

            List<ProjectDTO> userView =
                    projectService.getAllProjects("created_desc", null, null, "plainuser");
            assertThat(userView.get(0).getSlug())
                    .as("the user pinned Bravo themselves; an admin arranging the DEFAULT must "
                            + "not silently move Alpha above it — that is PER-1 in reverse")
                    .isEqualTo(bravo.getSlug());
        }

        @Test
        @DisplayName("an entry may omit sortOrder and keep the position it had")
        void anEntryMayLeaveOneValueAlone() throws Exception {
            User admin = userRepository.findByUsername("admin").orElseThrow();
            ProjectDTO alpha = createProject("Alpha", admin.getUsername());
            int before = projectRepository.findById(alpha.getId()).orElseThrow().getSortOrder();

            String body = "{\"items\":[{\"slug\":\"" + alpha.getSlug() + "\",\"pinned\":true}]}";

            mockMvc.perform(put("/api/v1/admin/projects-order")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].pinned").value(true))
                    .andExpect(jsonPath("$[0].sortOrder").value(before));
        }

        @Test
        @DisplayName("an unknown slug is 404, not a silent no-op")
        void unknownSlugIsNotFound() throws Exception {
            mockMvc.perform(put("/api/v1/admin/projects-order")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"items\":[{\"slug\":\"no-such-project\",\"pinned\":true}]}"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("an empty payload is 400")
        void emptyPayloadIsBadRequest() throws Exception {
            mockMvc.perform(put("/api/v1/admin/projects-order")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"items\":[]}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("a non-admin cannot write the global order")
        void nonAdminCannotWrite() throws Exception {
            mockMvc.perform(put("/api/v1/admin/projects-order")
                            .header("Authorization", "Bearer " + userToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"items\":[{\"slug\":\"x\",\"pinned\":true}]}"))
                    .andExpect(status().isForbidden());
        }
    }
}
