package com.wiki4ai.service;

import com.wiki4ai.dto.ProjectCreateDTO;
import com.wiki4ai.dto.ProjectDTO;
import com.wiki4ai.dto.ReorderRequestDTO;
import com.wiki4ai.model.Project;
import com.wiki4ai.model.Role;
import com.wiki4ai.model.User;
import com.wiki4ai.model.UserProjectOrder;
import com.wiki4ai.repository.DocumentRepository;
import com.wiki4ai.repository.ProjectPermissionRepository;
import com.wiki4ai.repository.ProjectRepository;
import com.wiki4ai.repository.UserProjectOrderRepository;
import com.wiki4ai.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WIKI4AI-200 (PER-1): pin, unpin and reorder write the CALLER's row.
 *
 * <p>The read path is proven separately in {@link PerUserProjectOrderReadIntegrationTest}
 * against hand-written rows, so a failure here points at a write.</p>
 *
 * <h2>The assertion that would have caught the original bug</h2>
 * Every test here asks a second account what it sees. A test with one account passes just
 * as happily when pin writes {@code projects.pinned}, which is exactly why WIKI4AI-184's
 * own suite never noticed that a pin was global. The interesting line in each test is the
 * one about the bystander.</p>
 *
 * <h2>Why the global column is asserted too</h2>
 * "Bystander unaffected" can be true for two different reasons: the write went to the
 * caller's row, or the write went nowhere. Reading {@code projects.pinned} straight from
 * the repository distinguishes them.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("WIKI4AI-200: per-viewer project ordering, write path")
class PerUserProjectOrderWriteIntegrationTest {

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private ProjectPermissionRepository projectPermissionRepository;

    @Autowired
    private UserProjectOrderRepository userProjectOrderRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private com.wiki4ai.service.PermissionService permissionService;

    private final List<User> createdUsers = new ArrayList<>();

    private User newUser(String username) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@test.local");
        user.setPassword("not-a-real-hash");
        user.setRole(Role.USER);
        User saved = userRepository.save(user);
        createdUsers.add(saved);
        return saved;
    }

    private ProjectDTO createProject(String name, String owner) {
        ProjectCreateDTO dto = new ProjectCreateDTO();
        dto.setName(name);
        dto.setDescription("per-user ordering playground");
        return projectService.createProject(dto, owner);
    }

    private List<String> slugs(List<ProjectDTO> list) {
        return list.stream().map(ProjectDTO::getSlug).collect(Collectors.toList());
    }

    /** The project's OWN pinned flag, read past every DTO. */
    private boolean globallyPinned(Long projectId) {
        return projectRepository.findById(projectId).orElseThrow().isPinned();
    }

    /** Set the GLOBAL pin/position the way the pre-WIKI4AI-200 code did. */
    private void pinGlobally(ProjectDTO project, int globalSortOrder) {
        transactionTemplate.executeWithoutResult(status ->
                projectRepository.updatePinning(project.getId(), true, globalSortOrder));
    }

    private List<Integer> viewerPositions(Long userId) {
        return userProjectOrderRepository.findByUserId(userId).stream()
                .map(UserProjectOrder::getSortOrder)
                .sorted()
                .collect(Collectors.toList());
    }

    @BeforeEach
    void leaveNothingBehind() {
        documentRepository.deleteAll();
        userProjectOrderRepository.deleteAll();
        projectPermissionRepository.deleteAll();
        projectRepository.deleteAll();
        userRepository.deleteAll(createdUsers);
        createdUsers.clear();
    }

    // =======================================================================

    @Nested
    @DisplayName("pin")
    class Pin {

        @Test
        @DisplayName("a pin by A pins for A, leaves B's list alone and never touches projects.pinned")
        void aPinByAIsApinForAOnly() {
            User a = newUser("write-pin-a");
            User b = newUser("write-pin-b");
            ProjectDTO alpha = createProject("Alpha", a.getUsername());
            ProjectDTO bravo = createProject("Bravo", a.getUsername());

            List<String> beforeB = slugs(
                    projectService.getAllProjects("created_desc", null, null, b.getUsername()));

            ProjectDTO response = projectService.pinProject(alpha.getSlug(), a.getUsername());

            assertThat(response.isPinned())
                    .as("the response is what the frontend applies to its local list "
                            + "(WIKI4AI-186), so it must be A's view")
                    .isTrue();
            assertThat(slugs(projectService.getAllProjects("created_desc", null, null, a.getUsername())))
                    .as("A sees the pin")
                    .startsWith(alpha.getSlug());
            assertThat(slugs(projectService.getAllProjects("created_desc", null, null, b.getUsername())))
                    .as("B must have noticed nothing")
                    .isEqualTo(beforeB);
            assertThat(globallyPinned(alpha.getId()))
                    .as("the write went to A's row, not to projects.pinned")
                    .isFalse();
            assertThat(bravo.isPinned()).isFalse();
        }

        @Test
        @DisplayName("the pin also lands above every global position, so sort=manual shows it first")
        void aPinReachesTheTopOfAManualListToo() {
            User a = newUser("write-pin-manual-a");
            User b = newUser("write-pin-manual-b");
            // Ten projects the caller never customises, so the "top" slot has to undercut
            // the global column and not merely A's own three rows.
            for (int i = 1; i <= 10; i++) {
                createProject("Global " + i, b.getUsername());
            }
            ProjectDTO late = createProject("Late Arrival", a.getUsername());

            projectService.pinProject(late.getSlug(), a.getUsername());

            assertThat(slugs(projectService.getAllProjects("manual", null, null, a.getUsername())))
                    .as("a pin that only undercut the caller's own rows would still sit below "
                            + "eighty global positions on a sort=manual list")
                    .startsWith(late.getSlug());
        }

        @Test
        @DisplayName("pinning twice does not teleport the project")
        void repinningKeepsThePosition() {
            User a = newUser("write-pin-twice-a");
            ProjectDTO first = createProject("First", a.getUsername());
            createProject("Second", a.getUsername());

            projectService.pinProject(first.getSlug(), a.getUsername());
            int afterFirst = userProjectOrderRepository
                    .findByUserIdAndProjectId(a.getId(), first.getId()).orElseThrow().getSortOrder();

            projectService.pinProject(first.getSlug(), a.getUsername());
            int afterSecond = userProjectOrderRepository
                    .findByUserIdAndProjectId(a.getId(), first.getId()).orElseThrow().getSortOrder();

            assertThat(afterSecond).isEqualTo(afterFirst);
        }

        @Test
        @DisplayName("one viewer's pin creates exactly one row")
        void aPinWritesOneRow() {
            User a = newUser("write-pin-rows-a");
            ProjectDTO project = createProject("One Row", a.getUsername());

            projectService.pinProject(project.getSlug(), a.getUsername());

            assertThat(userProjectOrderRepository.findByUserId(a.getId())).hasSize(1);
        }
    }

    @Nested
    @DisplayName("unpin")
    class Unpin {

        @Test
        @DisplayName("unpinning a globally pinned project unpins it for the caller and keeps the global pin")
        void unpinOverridesAGlobalPinForOneViewerOnly() {
            User owner = newUser("write-unpin-owner");
            User viewer = newUser("write-unpin-viewer");
            ProjectDTO pinned = createProject("Globally Pinned", owner.getUsername());
            ProjectDTO plain = createProject("Plain", owner.getUsername());
            pinGlobally(pinned, projectRepository.minSortOrder() - 1);
            // The write endpoints keep the WIKI4AI-184 permission rule UNCHANGED: the
            // project must be visible and the caller must hold MANAGE. A per-user pin is a
            // personal preference, so MANAGE is arguably stricter than the feature needs —
            // but relaxing it is a product decision, not a side effect of this story, so
            // the test grants it rather than assuming it away.
            permissionService.grantManageToCreator(viewer.getUsername(), pinned.getId());

            assertThat(globallyPinned(pinned.getId())).isTrue();

            projectService.unpinProject(pinned.getSlug(), viewer.getUsername());

            List<ProjectDTO> viewerList =
                    projectService.getAllProjects("created_desc", null, null, viewer.getUsername());
            List<ProjectDTO> ownerList =
                    projectService.getAllProjects("created_desc", null, null, owner.getUsername());

            assertThat(slugs(viewerList).get(0))
                    .as("the viewer's row says false, so the global pin no longer floats it "
                            + "to their top")
                    .isEqualTo(plain.getSlug());
            assertThat(ownerList.get(0).getSlug())
                    .as("the global pin stands for everyone else")
                    .isEqualTo(pinned.getSlug());
            assertThat(globallyPinned(pinned.getId()))
                    .as("and the global column was never written")
                    .isTrue();
        }

        @Test
        @DisplayName("unpin keeps the position the project had, for that viewer")
        void unpinKeepsThePosition() {
            User a = newUser("write-unpin-pos-a");
            ProjectDTO project = createProject("Keep Position", a.getUsername());

            projectService.pinProject(project.getSlug(), a.getUsername());
            int pinnedAt = userProjectOrderRepository
                    .findByUserIdAndProjectId(a.getId(), project.getId()).orElseThrow().getSortOrder();

            projectService.unpinProject(project.getSlug(), a.getUsername());

            UserProjectOrder after = userProjectOrderRepository
                    .findByUserIdAndProjectId(a.getId(), project.getId()).orElseThrow();
            assertThat(after.isPinned()).isFalse();
            assertThat(after.getSortOrder())
                    .as("unpinning puts the project back where it stood, it does not send it "
                            + "to the end of the order")
                    .isEqualTo(pinnedAt);
        }
    }

    @Nested
    @DisplayName("reorder")
    class Reorder {

        @Test
        @DisplayName("a drag by A renumbers A's rows and leaves B's list and the global column alone")
        void aDragByARenumbersOnlyAsRows() {
            User a = newUser("write-drag-a");
            User b = newUser("write-drag-b");
            ProjectDTO alpha = createProject("Alpha", a.getUsername());
            createProject("Bravo", a.getUsername());
            createProject("Charlie", a.getUsername());

            List<String> beforeB = slugs(
                    projectService.getAllProjects("manual", null, null, b.getUsername()));
            List<Integer> globalBefore = projectRepository.findAll().stream()
                    .map(Project::getSortOrder).sorted().collect(Collectors.toList());

            ProjectDTO response = projectService.reorderProject(
                    alpha.getSlug(), ReorderRequestDTO.builder().position(0).build(),
                    a.getUsername());

            assertThat(slugs(projectService.getAllProjects("manual", null, null, a.getUsername())))
                    .as("A's own list now starts with the dragged project")
                    .startsWith(alpha.getSlug());
            assertThat(slugs(projectService.getAllProjects("manual", null, null, b.getUsername())))
                    .as("B watched a drag and saw nothing")
                    .isEqualTo(beforeB);
            assertThat(projectRepository.findAll().stream()
                    .map(Project::getSortOrder).sorted().collect(Collectors.toList()))
                    .as("the global column is untouched — this is the renumbering that used to "
                            + "rewrite other people's order")
                    .isEqualTo(globalBefore);
            assertThat(response.getSortOrder())
                    .as("the response reports A's new position, which is 0")
                    .isZero();
        }

        @Test
        @DisplayName("the caller's positions stay a dense permutation with no duplicates")
        void theCallersPositionsStayADensePermutation() {
            User a = newUser("write-dense-a");
            for (int i = 1; i <= 6; i++) {
                createProject("Dense " + i, a.getUsername());
            }
            List<String> all = slugs(
                    projectService.getAllProjects("manual", null, null, a.getUsername()));

            projectService.reorderProject(all.get(0),
                    ReorderRequestDTO.builder().position(5).build(), a.getUsername());
            projectService.reorderProject(all.get(5),
                    ReorderRequestDTO.builder().position(0).build(), a.getUsername());

            assertThat(viewerPositions(a.getId()))
                    .as("two drags must leave 0..n-1 with no duplicate — a duplicate is a lost "
                            + "writer, and the acceptance criterion names this exact query")
                    .containsExactly(0, 1, 2, 3, 4, 5);
        }

        @Test
        @DisplayName("a reorder does not unpin a project the caller pinned earlier")
        void aReorderKeepsTheCallersOwnPins() {
            User a = newUser("write-reorder-pin-a");
            ProjectDTO pinned = createProject("Pinned First", a.getUsername());
            createProject("Second", a.getUsername());
            createProject("Third", a.getUsername());
            projectService.pinProject(pinned.getSlug(), a.getUsername());

            List<String> before = slugs(
                    projectService.getAllProjects("manual", null, null, a.getUsername()));
            assertThat(before).startsWith(pinned.getSlug());

            projectService.reorderProject(before.get(2),
                    ReorderRequestDTO.builder().position(1).build(), a.getUsername());

            assertThat(slugs(projectService.getAllProjects("manual", null, null, a.getUsername())))
                    .as("pinned is the first key, so the pinned project stays on top even "
                            + "though the reorder renumbered the list")
                    .startsWith(pinned.getSlug());
            assertThat(userProjectOrderRepository
                    .findByUserIdAndProjectId(a.getId(), pinned.getId()).orElseThrow().isPinned())
                    .isTrue();
        }

        @Test
        @DisplayName("an anonymous caller still writes the global column")
        void anAnonymousCallerWritesTheGlobalDefault() {
            User owner = newUser("write-anon-owner");
            ProjectDTO project = createProject("Anonymous Pin Target", owner.getUsername());

            projectService.pinProject(project.getSlug(), null);

            assertThat(globallyPinned(project.getId()))
                    .as("no caller means no personal view, so the curated default is written")
                    .isTrue();
            assertThat(userProjectOrderRepository.findByUserId(owner.getId()))
                    .as("and no viewer row is invented for the project's owner")
                    .isEmpty();
        }
    }
}
