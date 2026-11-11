package com.wiki4ai.service;

import com.wiki4ai.dto.ProjectCreateDTO;
import com.wiki4ai.dto.ProjectDTO;
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
 * WIKI4AI-200 (PER-1): the project list is ordered by the CALLER's rows, not by the
 * project's own columns.
 *
 * <p>This class drives the <b>read</b> path only: it writes {@code user_project_order}
 * rows straight through the repository and then asks the list what it sees. That
 * separation is deliberate. If the read is proven correct against hand-written rows
 * before the write path exists, a later failure in the pin/reorder tests points at the
 * write, and not at whichever of the two was written second.</p>
 *
 * <h2>The property the whole story exists for</h2>
 * One viewer's row changes one viewer's list. Every test here therefore has two viewers in
 * it, and the interesting assertion is always about the second one — the one who must see
 * nothing move. A test with a single viewer would still pass with the old global code,
 * which is exactly how this bug survived WIKI4AI-184's own suite.</p>
 *
 * <h2>Why the DTO is asserted alongside the order</h2>
 * Sorting by the viewer's values while reporting the project's would produce a dashboard
 * that looks right and then reorders itself the moment anything refetches. The DTO and the
 * order must come from the same overlay, so both are asserted in every case.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("WIKI4AI-200: per-viewer project ordering, read path")
class PerUserProjectOrderReadIntegrationTest {

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

    /**
     * The service methods are {@code @Transactional}; a bare {@code @Modifying} repository
     * query is not, so the tests that set up GLOBAL state by hand (which is what the old
     * code did on its own) have to open a transaction themselves.
     */
    @Autowired
    private TransactionTemplate transactionTemplate;

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

    /** Create a project owned by {@code owner}, so MANAGE and visibility are realistic. */
    private ProjectDTO createProject(String name, String owner) {
        ProjectCreateDTO dto = new ProjectCreateDTO();
        dto.setName(name);
        dto.setDescription("per-user ordering playground");
        return projectService.createProject(dto, owner);
    }

    /** Set the GLOBAL pin/position of one project, the way the pre-WIKI4AI-200 code did. */
    private void pinGlobally(ProjectDTO project, int globalSortOrder) {
        transactionTemplate.executeWithoutResult(status ->
                projectRepository.updatePinning(project.getId(), true, globalSortOrder));
    }

    /** Hand-write one viewer's row for one project. */
    private void orderFor(User viewer, ProjectDTO project, boolean pinned, int sortOrder) {
        userProjectOrderRepository.saveAndFlush(UserProjectOrder.builder()
                .userId(viewer.getId())
                .projectId(project.getId())
                .pinned(pinned)
                .sortOrder(sortOrder)
                .build());
    }

    private List<String> slugs(List<ProjectDTO> list) {
        return list.stream().map(ProjectDTO::getSlug).collect(Collectors.toList());
    }

    @BeforeEach
    void leaveNothingBehind() {
        // Shared H2 across contexts, and no JPA cascade from Project to these rows on H2
        // (the entity maps plain ids; the ON DELETE CASCADE lives in V28, which H2 never
        // runs). So the wipe is explicit and runs in FK order.
        documentRepository.deleteAll();
        userProjectOrderRepository.deleteAll();
        projectPermissionRepository.deleteAll();
        projectRepository.deleteAll();
        // Only this class's own accounts: the H2 is shared with every other Spring
        // context in the suite, and a deleteAll() here would take their users too.
        userRepository.deleteAll(createdUsers);
        createdUsers.clear();
    }

    // =======================================================================

    @Nested
    @DisplayName("One viewer's row moves that viewer's list and nobody else's")
    class Isolation {

        @Test
        @DisplayName("a reorder row for viewer A leaves viewer B's order untouched")
        void aReorderRowMovesOnlyItsOwnersList() {
            User a = newUser("peruser-iso-a");
            User b = newUser("peruser-iso-b");
            ProjectDTO first = createProject("Alpha", a.getUsername());
            ProjectDTO second = createProject("Bravo", a.getUsername());
            ProjectDTO third = createProject("Charlie", a.getUsername());

            List<String> beforeB = slugs(projectService.getAllProjects("manual", null, null, b.getUsername()));

            // A drags the OLDEST project to the top of A's own manual order. The global
            // order is Charlie, Bravo, Alpha (createProject hands each new project one
            // slot above the current minimum), so "Alpha to the top" is a real move and
            // not a no-op. The expected list is derived from B's untouched one rather
            // than hardcoded, so the assertion says "A moved one item, everything else
            // kept its relative order" instead of restating the numbering scheme.
            orderFor(a, first, false, projectRepository.minSortOrder() - 1);
            List<String> expectedForA = new ArrayList<>(beforeB);
            expectedForA.remove(first.getSlug());
            expectedForA.add(0, first.getSlug());

            assertThat(slugs(projectService.getAllProjects("manual", null, null, a.getUsername())))
                    .as("A's list follows A's row")
                    .isEqualTo(expectedForA);
            assertThat(slugs(projectService.getAllProjects("manual", null, null, b.getUsername())))
                    .as("B saw A drag something and must have noticed nothing")
                    .isEqualTo(beforeB);
        }

        @Test
        @DisplayName("a pin row for viewer A does not pin the project for viewer B")
        void aPinRowPinsOnlyForItsOwner() {
            User a = newUser("peruser-pin-a");
            User b = newUser("peruser-pin-b");
            ProjectDTO bottom = createProject("Bottom", a.getUsername());
            ProjectDTO top = createProject("Top", a.getUsername());

            orderFor(a, bottom, true, -1);

            List<ProjectDTO> aList =
                    projectService.getAllProjects("created_desc", null, null, a.getUsername());
            List<ProjectDTO> bList =
                    projectService.getAllProjects("created_desc", null, null, b.getUsername());

            assertThat(aList.get(0).getSlug())
                    .as("pinned is the first sort key, so A sees the pinned project first")
                    .isEqualTo(bottom.getSlug());
            assertThat(aList.get(0).isPinned())
                    .as("and the DTO says so too, because the order and the DTO must agree")
                    .isTrue();

            assertThat(bList.get(0).getSlug())
                    .as("B's list is untouched")
                    .isEqualTo(top.getSlug());
            assertThat(bList).allSatisfy(p -> assertThat(p.isPinned()).isFalse());
        }

        @Test
        @DisplayName("the same project can be pinned for one viewer and not for another")
        void twoViewersHoldDifferentViewsOfOneProject() {
            User a = newUser("peruser-two-a");
            User b = newUser("peruser-two-b");
            ProjectDTO shared = createProject("Shared", a.getUsername());

            orderFor(a, shared, true, -5);

            assertThat(projectService.getAllProjects("manual", null, null, a.getUsername()).get(0).isPinned())
                    .isTrue();
            assertThat(projectService.getAllProjects("manual", null, null, b.getUsername()).get(0).isPinned())
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("No row means the global value, which is not a degraded case")
    class Fallback {

        @Test
        @DisplayName("a viewer with no rows sees exactly the global order and the global pins")
        void noRowsIsTheGlobalView() {
            User owner = newUser("peruser-global-owner");
            User viewer = newUser("peruser-global-viewer");
            ProjectDTO older = createProject("Older", owner.getUsername());
            ProjectDTO newer = createProject("Newer", owner.getUsername());

            // The global state: Newer is pinned by the old global column.
            pinGlobally(newer, projectRepository.minSortOrder() - 1);

            List<ProjectDTO> list =
                    projectService.getAllProjects("manual", null, null, viewer.getUsername());

            assertThat(slugs(list)).containsExactly(newer.getSlug(), older.getSlug());
            assertThat(list.get(0).isPinned()).isTrue();
            assertThat(list.get(0).getSortOrder())
                    .as("the DTO reports the project's own position when the viewer has no row")
                    .isEqualTo(projectRepository.findById(newer.getId())
                            .orElseThrow().getSortOrder());
        }

        @Test
        @DisplayName("an anonymous caller sees the global view, never a stray row")
        void anonymousSeesTheGlobalView() {
            User owner = newUser("peruser-anon-owner");
            ProjectDTO project = createProject("Anon Target", owner.getUsername());
            orderFor(owner, project, true, -1);

            List<ProjectDTO> anonymous = projectService.getAllProjects("manual", null, null, null);

            assertThat(anonymous).hasSize(1);
            assertThat(anonymous.get(0).isPinned())
                    .as("the owner's pin is the owner's, and an anonymous caller has no row")
                    .isFalse();
        }

        @Test
        @DisplayName("a viewer row can UNPIN a project that is pinned globally")
        void aViewerRowCanUndoAGlobalPinForThatViewerOnly() {
            User owner = newUser("peruser-unpin-owner");
            User viewer = newUser("peruser-unpin-viewer");
            ProjectDTO pinnedGlobally = createProject("Globally Pinned", owner.getUsername());
            ProjectDTO plain = createProject("Plain", owner.getUsername());

            pinGlobally(pinnedGlobally, projectRepository.minSortOrder() - 1);
            orderFor(viewer, pinnedGlobally, false, plain.getSortOrder());

            List<ProjectDTO> viewerList =
                    projectService.getAllProjects("created_desc", null, null, viewer.getUsername());
            List<ProjectDTO> ownerList =
                    projectService.getAllProjects("created_desc", null, null, owner.getUsername());

            assertThat(slugs(viewerList).get(0))
                    .as("for this viewer the global pin is overridden, so the list falls back "
                            + "to the requested sort key")
                    .isNotEqualTo(pinnedGlobally.getSlug());
            assertThat(ownerList.get(0).getSlug())
                    .as("the global pin still stands for everyone else")
                    .isEqualTo(pinnedGlobally.getSlug());
        }
    }

    @Nested
    @DisplayName("The overlay never widens visibility")
    class Visibility {

        @Test
        @DisplayName("a foreign private project stays absent even when it has a row for the viewer")
        void aPrivateProjectOfSomeoneElseIsStillAbsent() {
            User owner = newUser("peruser-priv-owner");
            User viewer = newUser("peruser-priv-viewer");

            ProjectCreateDTO dto = new ProjectCreateDTO();
            dto.setName("Secret Plan");
            dto.setVisibility(Project.VISIBILITY_PRIVATE);
            ProjectDTO secret = projectService.createProject(dto, owner.getUsername());

            // A row for the viewer cannot make a private project of someone else visible:
            // the visibility filter runs before any per-viewer value is read.
            orderFor(viewer, secret, true, -1);

            assertThat(slugs(projectService.getAllProjects("manual", null, null, viewer.getUsername())))
                    .doesNotContain(secret.getSlug());
            assertThat(slugs(projectService.getAllProjects("manual", null, null, owner.getUsername())))
                    .contains(secret.getSlug());
        }
    }

    @Nested
    @DisplayName("The overlay applies to every sort key, not only to manual")
    class EverySortKey {

        @Test
        @DisplayName("pinned is the first key for name_asc too, and the viewer's row decides it")
        void theViewerPinFloatsToTheTopUnderANameSort() {
            User a = newUser("peruser-sort-a");
            User b = newUser("peruser-sort-b");
            ProjectDTO alpha = createProject("Zulu", a.getUsername());
            ProjectDTO zulu = createProject("Alpha", a.getUsername());

            orderFor(a, alpha, true, 99);

            assertThat(slugs(projectService.getAllProjects("name_asc", null, null, a.getUsername())))
                    .as("name_asc would put Alpha first; A's pin of Zulu wins, because pinned "
                            + "is the first key of every sort")
                    .containsExactly(alpha.getSlug(), zulu.getSlug());
            assertThat(slugs(projectService.getAllProjects("name_asc", null, null, b.getUsername())))
                    .as("B gets the plain name order")
                    .containsExactly(zulu.getSlug(), alpha.getSlug());
        }
    }
}
