package com.wiki4ai.service;

import com.wiki4ai.dto.DocumentCreateDTO;
import com.wiki4ai.dto.DocumentDTO;
import com.wiki4ai.dto.DocumentSummaryDTO;
import com.wiki4ai.dto.ProjectCreateDTO;
import com.wiki4ai.dto.ProjectDTO;
import com.wiki4ai.dto.ReorderRequestDTO;
import com.wiki4ai.exception.BadRequestException;
import com.wiki4ai.model.Document;
import com.wiki4ai.model.Project;
import com.wiki4ai.model.Role;
import com.wiki4ai.model.User;
import com.wiki4ai.repository.DocumentRepository;
import com.wiki4ai.repository.ProjectPermissionRepository;
import com.wiki4ai.repository.ProjectRepository;
import com.wiki4ai.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.test.context.ActiveProfiles;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WIKI4AI-184: pin to top and manual order for projects and documents, plus the
 * {@code sort} / {@code q} / {@code visibility} parameters of the two list endpoints.
 *
 * <p>{@code @SpringBootTest} on the shared H2 with the schema built from the entities
 * (Flyway is off on H2 — the SQL itself is asserted in
 * {@code PinningSortOrderMigrationTest}). Username is null for almost every call, which is
 * how the rest of this suite calls the service layer: {@code resolveUser(null)} yields no
 * user and {@code PermissionService.checkPermission} skips anonymous callers, so these
 * tests cover ordering and filtering, not RBAC (that is covered by the existing visibility
 * suites). The two visibility-filter tests are the exception and pass a real owner,
 * because a filter that only ever runs for someone who can see everything would prove
 * nothing.</p>
 *
 * <h2>Why several assertions compare against a mirrored comparator</h2>
 * Rows created in the same test run can share a {@code createdAt}/{@code updatedAt} to the
 * microsecond, so asserting a literal order for a timestamp sort is a flake waiting to
 * happen. Those assertions instead assert that the endpoint agrees with the ordering the
 * service documents — which is the real contract, and it also catches a divergence between
 * the Java comparator (projects) and the SQL ORDER BY (documents), the two places a sort
 * key is implemented twice on purpose.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class PinningReorderIntegrationTests {

    @Autowired
    private ProjectService projectService;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProjectPermissionRepository projectPermissionRepository;

    /** Users created by this class, removed again in {@link #leaveNothingBehind()}. */
    private final List<User> createdUsers = new java.util.ArrayList<>();

    /**
     * A throwaway owner for the visibility tests. The username is unique per call
     * because the H2 is shared with other Spring contexts in the same JVM, and
     * {@code VisibilityIntegrationTests} deletes its own users by name.
     */
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

    /**
     * Wipe the shared H2 before each test.
     *
     * <p>The order is not incidental: {@code Project} has no collection mapping to
     * {@code ProjectPermission} (the FK lives only on the permission side), so deleting
     * projects does NOT cascade to their grants and H2 rejects the delete with a
     * referential-integrity violation. Documents have the same problem. This is the order
     * {@code VisibilityIntegrationTests} uses for the same reason.</p>
     */
    @BeforeEach
    void cleanUp() {
        leaveNothingBehind();
    }

    /**
     * Clean up AFTER the class too. The H2 is shared by every Spring context in this
     * JVM and the next class to run gets no warning that a previous one left rows behind;
     * {@code ProjectActorServiceTest} in particular deletes projects without deleting
     * their permissions, so a single leftover grant turns its whole class into FK
     * errors. Cleaning up after yourself is cheaper than depending on the next class.
     */
    @AfterEach
    void leaveNothingBehind() {
        documentRepository.deleteAll();
        projectPermissionRepository.deleteAll();
        projectRepository.deleteAll();
        if (!createdUsers.isEmpty()) {
            userRepository.deleteAll(createdUsers);
            createdUsers.clear();
        }
    }

    private ProjectDTO createProject(String name, String description) {
        ProjectCreateDTO dto = new ProjectCreateDTO();
        dto.setName(name);
        dto.setDescription(description);
        return projectService.createProject(dto, null);
    }

    private DocumentDTO createDocument(Long projectId, String title, String content) {
        DocumentCreateDTO dto = new DocumentCreateDTO();
        dto.setTitle(title);
        dto.setContent(content);
        return documentService.createDocument(projectId, dto, null);
    }

    private List<String> slugs(List<ProjectDTO> projects) {
        return projects.stream().map(ProjectDTO::getSlug).collect(Collectors.toList());
    }

    private List<String> docSlugs(Page<DocumentSummaryDTO> page) {
        return page.getContent().stream().map(DocumentSummaryDTO::getSlug)
                .collect(Collectors.toList());
    }

    /** The order the project list promises, computed independently of the endpoint. */
    private List<String> expectedProjectOrder(String sort) {
        return projectRepository.findAll().stream()
                .sorted(ListSort.projectComparator(sort))
                .map(Project::getSlug)
                .collect(Collectors.toList());
    }

    /** The order the document list promises, computed independently of the endpoint. */
    private List<String> expectedDocumentOrder(Long projectId, String sort) {
        return documentRepository.findByProjectId(projectId).stream()
                .sorted(ListSort.documentComparator(sort))
                .map(Document::getSlug)
                .collect(Collectors.toList());
    }

    // =======================================================================
    @Nested
    @DisplayName("Project list: sort, q, visibility")
    class ProjectList {

        @Test
        @DisplayName("no parameters keep the pre-WIKI4AI-184 order (created DESC)")
        void defaultOrderIsCreatedByCreationDate() {
            createProject("Alpha", "first");
            createProject("Bravo", "second");
            createProject("Charlie", "third");

            assertThat(projectService.getAllProjects(null))
                    .extracting(ProjectDTO::getSlug)
                    .containsExactlyElementsOf(expectedProjectOrder(ListSort.CREATED_DESC));
            assertThat(projectService.getAllProjects(null, null, null, null))
                    .isEqualTo(projectService.getAllProjects(null));
        }

        @Test
        @DisplayName("pinned projects float to the top under every sort key")
        void pinnedProjectsComeFirstUnderEverySort() {
            ProjectDTO alpha = createProject("Alpha", "a");
            createProject("Bravo", "b");
            ProjectDTO charlie = createProject("Charlie", "c");

            // Each first pin takes the slot above the current minimum, so alpha ends up
            // with the lowest sort_order. That only shows under sort=manual: for every
            // other key pinned is the FIRST key and the requested key decides the order
            // INSIDE the pinned group — which is exactly why pinned is a modifier of the
            // sort rather than a separate list.
            projectService.pinProject(charlie.getSlug(), null);
            projectService.pinProject(alpha.getSlug(), null);

            assertThat(slugs(projectService.getAllProjects(null, null, null, null)))
                    .containsExactly(charlie.getSlug(), alpha.getSlug(), "bravo");
            assertThat(slugs(projectService.getAllProjects("name_asc", null, null, null)))
                    .containsExactly(alpha.getSlug(), charlie.getSlug(), "bravo");
            assertThat(slugs(projectService.getAllProjects("created_asc", null, null, null)))
                    .containsExactly(alpha.getSlug(), charlie.getSlug(), "bravo");
            assertThat(slugs(projectService.getAllProjects("manual", null, null, null)))
                    .containsExactly(alpha.getSlug(), charlie.getSlug(), "bravo");
        }

        @Test
        @DisplayName("sort=name_asc orders by name, A to Z")
        void nameAscOrdersByName() {
            createProject("Zulu", "z");
            createProject("alpha", "a");
            createProject("Mike", "m");

            assertThat(slugs(projectService.getAllProjects("name_asc", null, null, null)))
                    .containsExactly("alpha", "mike", "zulu");
        }

        @Test
        @DisplayName("sort=name_desc is the reverse of name_asc")
        void nameDescIsTheReverse() {
            createProject("Zulu", "z");
            createProject("alpha", "a");
            createProject("Mike", "m");

            assertThat(slugs(projectService.getAllProjects("name_desc", null, null, null)))
                    .containsExactly("zulu", "mike", "alpha");
        }

        @Test
        @DisplayName("sort=manual orders by sort_order, and the reorder endpoint owns it")
        void manualSortFollowsSortOrder() {
            ProjectDTO one = createProject("One", "1");
            ProjectDTO two = createProject("Two", "2");
            ProjectDTO three = createProject("Three", "3");

            // Creation alone already gives a usable manual order: newest first.
            assertThat(slugs(projectService.getAllProjects("manual", null, null, null)))
                    .containsExactly(three.getSlug(), two.getSlug(), one.getSlug());

            projectService.reorderProject(one.getSlug(),
                    ReorderRequestDTO.builder().position(0).build(), null);

            assertThat(slugs(projectService.getAllProjects("manual", null, null, null)))
                    .containsExactly(one.getSlug(), three.getSlug(), two.getSlug());
            assertThat(projectService.getAllProjects("manual", null, null, null))
                    .extracting(ProjectDTO::getSortOrder)
                    .containsExactly(0, 1, 2);
        }

        @Test
        @DisplayName("q matches name and description, case-insensitively")
        void qFiltersNameAndDescription() {
            createProject("Rocket Launch", "about orbits");
            createProject("Garden Notes", "about ROCKET fuel");
            createProject("Nothing Here", "unrelated");

            assertThat(slugs(projectService.getAllProjects(null, "rocket", null, null)))
                    .containsExactlyInAnyOrder("rocket-launch", "garden-notes");
            assertThat(slugs(projectService.getAllProjects(null, "ORBITS", null, null)))
                    .containsExactly("rocket-launch");
            assertThat(projectService.getAllProjects(null, "zzz-nothing", null, null)).isEmpty();
        }

        @Test
        @DisplayName("blank q is no filter at all")
        void blankQIsNoFilter() {
            createProject("Alpha", "a");
            assertThat(projectService.getAllProjects(null, "   ", null, null)).hasSize(1);
        }

        @Test
        @DisplayName("visibility narrows the list to public or private")
        void visibilityFilterNarrowsTheList() {
            String owner = "pinning-owner-" + System.nanoTime();
            userRepository.save(newUser(owner));

            ProjectCreateDTO privateDto = new ProjectCreateDTO();
            privateDto.setName("Secret Plan");
            privateDto.setVisibility("private");
            projectService.createProject(privateDto, owner);
            createProject("Open Book", "public one");

            // The owner sees both, and the filter narrows them.
            assertThat(slugs(projectService.getAllProjects(null, null, null, owner))).hasSize(2);
            assertThat(slugs(projectService.getAllProjects(null, null, "public", owner)))
                    .containsExactly("open-book");
            assertThat(slugs(projectService.getAllProjects(null, null, "PRIVATE", owner)))
                    .containsExactly("secret-plan");
        }

        @Test
        @DisplayName("the filter narrows, it never widens: an anonymous caller cannot ask for private")
        void visibilityFilterCannotRevealForeignPrivateProjects() {
            ProjectCreateDTO privateDto = new ProjectCreateDTO();
            privateDto.setName("Someone Elses Secret");
            privateDto.setVisibility("private");
            // username null = anonymous creator, so nobody owns it and nobody can see it
            projectService.createProject(privateDto, null);
            createProject("Open Book", "public one");

            assertThat(slugs(projectService.getAllProjects(null, null, "public", null)))
                    .containsExactly("open-book");
            assertThat(projectService.getAllProjects(null, null, "private", null)).isEmpty();
            assertThat(projectService.getAllProjects(null, null, null, null))
                    .extracting(ProjectDTO::getSlug).containsExactly("open-book");
        }

        @Test
        @DisplayName("an unknown sort key is a 400 that names the allowed values")
        void unknownSortIsRejected() {
            createProject("Alpha", "a");
            assertThatThrownBy(() -> projectService.getAllProjects("sideways", null, null, null))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("sideways")
                    .hasMessageContaining("manual")
                    .hasMessageContaining("created_desc");
        }

        @Test
        @DisplayName("an unknown visibility filter is a 400")
        void unknownVisibilityIsRejected() {
            createProject("Alpha", "a");
            assertThatThrownBy(() -> projectService.getAllProjects(null, null, "secret", null))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("secret");
        }

        @Test
        @DisplayName("sort and q combine: filter first, then order")
        void sortAndQCombine() {
            createProject("Zulu One", "keep");
            createProject("alpha Two", "keep");
            createProject("Mike Three", "drop");

            assertThat(slugs(projectService.getAllProjects("name_asc", "keep", null, null)))
                    .containsExactly("alpha-two", "zulu-one");
        }
    }

    // =======================================================================
    @Nested
    @DisplayName("Project pin / unpin")
    class ProjectPin {

        @Test
        @DisplayName("pin sets pinned=true and puts the project at the top of the manual order")
        void pinMovesProjectToTheTop() {
            ProjectDTO first = createProject("First", "1");
            createProject("Second", "2");
            createProject("Third", "3");

            ProjectDTO pinned = projectService.pinProject(first.getSlug(), null);

            assertThat(pinned.isPinned()).isTrue();
            assertThat(slugs(projectService.getAllProjects("manual", null, null, null)))
                    .startsWith(first.getSlug());
        }

        @Test
        @DisplayName("re-pinning an already pinned project does not move it")
        void repinKeepsPosition() {
            ProjectDTO one = createProject("One", "1");
            ProjectDTO two = createProject("Two", "2");
            ProjectDTO three = createProject("Three", "3");

            projectService.pinProject(one.getSlug(), null);
            int orderAfterFirstPin = projectService.getProjectBySlug(one.getSlug(), null).getSortOrder();
            projectService.pinProject(two.getSlug(), null);
            ProjectDTO again = projectService.pinProject(one.getSlug(), null);

            assertThat(again.isPinned()).isTrue();
            assertThat(again.getSortOrder()).isEqualTo(orderAfterFirstPin);
            assertThat(slugs(projectService.getAllProjects("manual", null, null, null)))
                    .containsExactly(two.getSlug(), one.getSlug(), three.getSlug());
        }

        @Test
        @DisplayName("unpin keeps sort_order so the project returns to where the pin left it")
        void unpinKeepsSortOrder() {
            ProjectDTO one = createProject("One", "1");
            createProject("Two", "2");

            // The first pin deliberately MOVES sort_order (to min-1); unpin must then
            // leave it alone, otherwise unpinning would also throw the project to the
            // bottom of a manual list.
            projectService.pinProject(one.getSlug(), null);
            int orderWhilePinned = projectService.getProjectBySlug(one.getSlug(), null).getSortOrder();
            ProjectDTO unpinned = projectService.unpinProject(one.getSlug(), null);

            assertThat(unpinned.isPinned()).isFalse();
            assertThat(unpinned.getSortOrder()).isEqualTo(orderWhilePinned);
        }

        @Test
        @DisplayName("pin and unpin do not move updated_at (pinning is not editing)")
        void pinDoesNotTouchUpdatedAt() {
            ProjectDTO project = createProject("Alpha", "a");
            // Read the timestamp from the database, not from the create response: the
            // entity in memory still carries nanoseconds, while H2 stores microseconds.
            var updatedAtBefore = projectRepository.findBySlug("alpha").orElseThrow().getUpdatedAt();

            projectService.pinProject(project.getSlug(), null);
            var afterPin = projectRepository.findBySlug("alpha").orElseThrow().getUpdatedAt();
            projectService.unpinProject(project.getSlug(), null);
            var afterUnpin = projectRepository.findBySlug("alpha").orElseThrow().getUpdatedAt();

            assertThat(afterPin).isEqualTo(updatedAtBefore);
            assertThat(afterUnpin).isEqualTo(updatedAtBefore);
        }

        @Test
        @DisplayName("pinning an unknown slug is a 404")
        void pinUnknownProjectIs404() {
            assertThatThrownBy(() -> projectService.pinProject("no-such-project", null))
                    .isInstanceOf(EntityNotFoundException.class);
        }
    }

    // =======================================================================
    @Nested
    @DisplayName("Project reorder")
    class ProjectReorder {

        private static final String[] NAMES = {"Alpha", "Bravo", "Charlie", "Delta"};

        private List<ProjectDTO> seedFour() {
            for (String name : NAMES) {
                createProject(name, name.toLowerCase());
            }
            // Creation puts the newest first in the manual order.
            return projectService.getAllProjects("manual", null, null, null);
        }

        @Test
        @DisplayName("beforeSlug moves the item directly above the anchor")
        void beforeSlugMovesUp() {
            List<ProjectDTO> seeded = seedFour();
            String delta = seeded.get(0).getSlug();   // last created, currently first
            String alpha = seeded.get(3).getSlug();   // first created, currently last

            // alpha is at the bottom; putting it "before delta" (the head) must lift it
            // to the top and leave the rest of the order untouched.
            projectService.reorderProject(alpha,
                    ReorderRequestDTO.builder().beforeSlug(delta).build(), null);

            assertThat(slugs(projectService.getAllProjects("manual", null, null, null)))
                    .containsExactly("alpha", "delta", "charlie", "bravo");
        }

        @Test
        @DisplayName("beforeSlug lands directly above the anchor, not at the head of the list")
        void beforeSlugLandsNextToTheAnchor() {
            List<ProjectDTO> seeded = seedFour();
            String delta = seeded.get(0).getSlug();
            String alpha = seeded.get(3).getSlug();

            // delta is the head; "delta before alpha" (alpha is last) must move delta
            // down to the second-to-last slot, not to the top.
            projectService.reorderProject(delta,
                    ReorderRequestDTO.builder().beforeSlug(alpha).build(), null);

            assertThat(slugs(projectService.getAllProjects("manual", null, null, null)))
                    .containsExactly("charlie", "bravo", "delta", "alpha");
        }

        @Test
        @DisplayName("afterSlug moves the item directly below the anchor")
        void afterSlugMovesDown() {
            List<ProjectDTO> seeded = seedFour();
            String alpha = seeded.get(3).getSlug();
            String delta = seeded.get(0).getSlug();

            projectService.reorderProject(alpha,
                    ReorderRequestDTO.builder().afterSlug(delta).build(), null);

            assertThat(slugs(projectService.getAllProjects("manual", null, null, null)))
                    .containsExactly("delta", "alpha", "charlie", "bravo");
        }

        @Test
        @DisplayName("position moves to an absolute index and renumbers densely 0..n-1")
        void positionRenumbersDensely() {
            seedFour();

            projectService.reorderProject("alpha",
                    ReorderRequestDTO.builder().position(2).build(), null);

            List<ProjectDTO> ordered = projectService.getAllProjects("manual", null, null, null);
            assertThat(slugs(ordered)).containsExactly("delta", "charlie", "alpha", "bravo");
            assertThat(ordered).extracting(ProjectDTO::getSortOrder)
                    .containsExactly(0, 1, 2, 3);
        }

        @Test
        @DisplayName("a position past the end clamps to the end instead of failing")
        void positionClampsToTheEnd() {
            seedFour();

            projectService.reorderProject("alpha",
                    ReorderRequestDTO.builder().position(99).build(), null);

            assertThat(slugs(projectService.getAllProjects("manual", null, null, null)))
                    .endsWith("alpha");
        }

        @Test
        @DisplayName("a negative position clamps to the top")
        void negativePositionClampsToTheTop() {
            seedFour();

            projectService.reorderProject("alpha",
                    ReorderRequestDTO.builder().position(-5).build(), null);

            assertThat(slugs(projectService.getAllProjects("manual", null, null, null)))
                    .startsWith("alpha");
        }

        @Test
        @DisplayName("moving an item relative to itself is a no-op, not a 404")
        void selfAnchorIsANoOp() {
            seedFour();
            List<String> before = slugs(projectService.getAllProjects("manual", null, null, null));

            projectService.reorderProject("alpha",
                    ReorderRequestDTO.builder().beforeSlug("alpha").build(), null);

            assertThat(slugs(projectService.getAllProjects("manual", null, null, null)))
                    .isEqualTo(before);
        }

        @Test
        @DisplayName("the body must carry exactly one selector")
        void bodyMustCarryExactlyOneSelector() {
            seedFour();

            assertThatThrownBy(() -> projectService.reorderProject("alpha",
                    ReorderRequestDTO.builder().build(), null))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("exactly one");

            assertThatThrownBy(() -> projectService.reorderProject("alpha",
                    ReorderRequestDTO.builder().position(0).afterSlug("bravo").build(), null))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("exactly one");

            assertThatThrownBy(() -> projectService.reorderProject("alpha", null, null))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        @DisplayName("an anchor slug that does not exist is a 404")
        void unknownAnchorIs404() {
            seedFour();
            assertThatThrownBy(() -> projectService.reorderProject("alpha",
                    ReorderRequestDTO.builder().beforeSlug("nope").build(), null))
                    .isInstanceOf(EntityNotFoundException.class);
        }

        @Test
        @DisplayName("reorder does not move updated_at")
        void reorderDoesNotTouchUpdatedAt() {
            seedFour();
            var before = projectService.getProjectBySlug("alpha", null).getUpdatedAt();

            projectService.reorderProject("alpha",
                    ReorderRequestDTO.builder().position(0).build(), null);

            assertThat(projectService.getProjectBySlug("alpha", null).getUpdatedAt()).isEqualTo(before);
        }

        @Test
        @DisplayName("reordering one project leaves the pinned flags of all of them alone")
        void reorderKeepsPinnedFlags() {
            seedFour();
            projectService.pinProject("bravo", null);

            projectService.reorderProject("alpha",
                    ReorderRequestDTO.builder().position(0).build(), null);

            List<ProjectDTO> ordered = projectService.getAllProjects("manual", null, null, null);
            // bravo is pinned, so it still leads; alpha leads the unpinned rest.
            assertThat(slugs(ordered)).startsWith("bravo");
            assertThat(ordered).filteredOn(ProjectDTO::isPinned)
                    .extracting(ProjectDTO::getSlug).containsExactly("bravo");
        }
    }

    // =======================================================================
    @Nested
    @DisplayName("Document pin, reorder, sort and filter")
    class DocumentList {

        private Long projectId;

        @BeforeEach
        void seedProject() {
            projectId = createProject("Docs Playground", "the project for document ordering").getId();
        }

        @Test
        @DisplayName("default document order is updatedAt DESC, pinned first")
        void defaultOrderIsUpdatedDescWithPinnedFirst() {
            DocumentDTO a = createDocument(projectId, "Alpha Doc", "a");
            createDocument(projectId, "Bravo Doc", "b");
            DocumentDTO c = createDocument(projectId, "Charlie Doc", "c");

            assertThat(docSlugs(documentService.getDocumentsByProjectPaginated(
                    projectId, 0, 50, null, null, null)))
                    .containsExactlyElementsOf(expectedDocumentOrder(projectId, ListSort.UPDATED_DESC));

            documentService.pinDocument(projectId, a.getSlug(), null);

            assertThat(docSlugs(documentService.getDocumentsByProjectPaginated(
                    projectId, 0, 50, null, null, null)))
                    .containsExactly(a.getSlug(), c.getSlug(), "bravo-doc");
        }

        @Test
        @DisplayName("sort=manual follows the reorder endpoint and is stable across pages")
        void manualOrderIsStableAcrossPages() {
            DocumentDTO one = createDocument(projectId, "One", "1");
            DocumentDTO two = createDocument(projectId, "Two", "2");
            DocumentDTO three = createDocument(projectId, "Three", "3");
            DocumentDTO four = createDocument(projectId, "Four", "4");
            DocumentDTO five = createDocument(projectId, "Five", "5");

            // Newest first out of the box; then put "one" last and "four" first.
            documentService.reorderDocument(projectId, one.getSlug(),
                    ReorderRequestDTO.builder().position(4).build(), null);
            documentService.reorderDocument(projectId, four.getSlug(),
                    ReorderRequestDTO.builder().position(0).build(), null);

            Page<DocumentSummaryDTO> full = documentService.getDocumentsByProjectPaginated(
                    projectId, 0, 50, "manual", null, null);
            assertThat(docSlugs(full))
                    .containsExactly("four", "five", "three", "two", "one");
            assertThat(full.getContent()).extracting(DocumentSummaryDTO::getSortOrder)
                    .containsExactly(0, 1, 2, 3, 4);

            List<String> paged = new java.util.ArrayList<>();
            paged.addAll(docSlugs(documentService.getDocumentsByProjectPaginated(
                    projectId, 0, 2, "manual", null, null)));
            paged.addAll(docSlugs(documentService.getDocumentsByProjectPaginated(
                    projectId, 1, 2, "manual", null, null)));
            paged.addAll(docSlugs(documentService.getDocumentsByProjectPaginated(
                    projectId, 2, 2, "manual", null, null)));
            assertThat(paged).isEqualTo(docSlugs(full));
        }

        @Test
        @DisplayName("sort=name_asc orders documents by title")
        void nameAscOrdersByTitle() {
            createDocument(projectId, "Zulu", "z");
            createDocument(projectId, "alpha", "a");
            createDocument(projectId, "Mike", "m");

            assertThat(docSlugs(documentService.getDocumentsByProjectPaginated(
                    projectId, 0, 50, "name_asc", null, null)))
                    .containsExactly("alpha", "mike", "zulu");
        }

        @Test
        @DisplayName("q filters title and content, case-insensitively")
        void qFiltersTitleAndContent() {
            createDocument(projectId, "Rocket Plan", "nothing special");
            createDocument(projectId, "Garden", "mentions a ROCKET engine");
            createDocument(projectId, "Unrelated", "nothing");

            assertThat(docSlugs(documentService.getDocumentsByProjectPaginated(
                    projectId, 0, 50, null, "rocket", null)))
                    .containsExactlyInAnyOrder("rocket-plan", "garden");
            assertThat(docSlugs(documentService.getDocumentsByProjectPaginated(
                    projectId, 0, 50, null, "mentions", null)))
                    .containsExactly("garden");
            assertThat(documentService.getDocumentsByProjectPaginated(
                    projectId, 0, 50, null, "zzz", null)).isEmpty();
        }

        @Test
        @DisplayName("q narrows the page totals, not just the rows on the page")
        void qNarrowsThePageTotals() {
            createDocument(projectId, "Keep A", "a");
            createDocument(projectId, "Keep B", "b");
            createDocument(projectId, "Drop C", "c");

            Page<DocumentSummaryDTO> page = documentService.getDocumentsByProjectPaginated(
                    projectId, 0, 50, "name_asc", "keep", null);
            assertThat(page.getTotalElements()).isEqualTo(2);
            assertThat(page.getTotalPages()).isEqualTo(1);
        }

        @Test
        @DisplayName("an unknown document sort key is a 400")
        void unknownSortIsRejected() {
            createDocument(projectId, "Alpha", "a");
            assertThatThrownBy(() -> documentService.getDocumentsByProjectPaginated(
                    projectId, 0, 50, "sideways", null, null))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("sideways");
        }

        @Test
        @DisplayName("a new document starts at the top of the manual order")
        void newDocumentStartsAtTheTop() {
            createDocument(projectId, "First", "1");
            DocumentDTO second = createDocument(projectId, "Second", "2");

            assertThat(docSlugs(documentService.getDocumentsByProjectPaginated(
                    projectId, 0, 50, "manual", null, null))).startsWith(second.getSlug());
        }

        @Test
        @DisplayName("pin does not bump the optimistic-locking version")
        void pinDoesNotBumpVersion() {
            DocumentDTO doc = createDocument(projectId, "Versioned Doc", "body");
            long versionBefore = doc.getVersion();

            DocumentDTO pinned = documentService.pinDocument(projectId, doc.getSlug(), null);
            DocumentDTO unpinned = documentService.unpinDocument(projectId, doc.getSlug(), null);

            assertThat(pinned.getVersion()).isEqualTo(versionBefore);
            assertThat(unpinned.getVersion()).isEqualTo(versionBefore);
            assertThat(documentRepository.findById(doc.getId()).orElseThrow().getVersion())
                    .isEqualTo(versionBefore);
        }

        @Test
        @DisplayName("reorder does not bump the optimistic-locking version")
        void reorderDoesNotBumpVersion() {
            createDocument(projectId, "Alpha", "a");
            DocumentDTO beta = createDocument(projectId, "Beta", "b");
            long versionBefore = beta.getVersion();

            DocumentDTO reordered = documentService.reorderDocument(projectId, beta.getSlug(),
                    ReorderRequestDTO.builder().position(0).build(), null);

            assertThat(reordered.getVersion()).isEqualTo(versionBefore);
            assertThat(documentRepository.findById(beta.getId()).orElseThrow().getVersion())
                    .isEqualTo(versionBefore);
        }

        @Test
        @DisplayName("pin and reorder leave updated_at alone")
        void pinAndReorderLeaveUpdatedAtAlone() {
            DocumentDTO doc = createDocument(projectId, "Stable Timestamp", "body");
            // Both sides of the comparison come from the database, so the nanosecond vs
            // microsecond precision difference between the entity and the column cannot
            // masquerade as a change.
            var before = documentRepository.findById(doc.getId()).orElseThrow().getUpdatedAt();

            documentService.pinDocument(projectId, doc.getSlug(), null);
            documentService.reorderDocument(projectId, doc.getSlug(),
                    ReorderRequestDTO.builder().position(0).build(), null);

            assertThat(documentRepository.findById(doc.getId()).orElseThrow().getUpdatedAt())
                    .isEqualTo(before);
        }

        @Test
        @DisplayName("reorder reports the new position and the whole project stays dense")
        void reorderReturnsTheNewPosition() {
            createDocument(projectId, "Alpha", "a");
            DocumentDTO beta = createDocument(projectId, "Beta", "b");

            DocumentDTO moved = documentService.reorderDocument(projectId, beta.getSlug(),
                    ReorderRequestDTO.builder().position(0).build(), null);

            assertThat(moved.getSortOrder()).isZero();
            assertThat(moved.isPinned()).isFalse();
            assertThat(documentService.getDocumentsByProjectPaginated(
                    projectId, 0, 50, "manual", null, null).getContent())
                    .extracting(DocumentSummaryDTO::getSortOrder)
                    .containsExactly(0, 1);
        }

        @Test
        @DisplayName("reordering with two selectors is a 400")
        void twoSelectorsAreRejected() {
            createDocument(projectId, "Alpha", "a");
            DocumentDTO beta = createDocument(projectId, "Beta", "b");

            assertThatThrownBy(() -> documentService.reorderDocument(projectId, beta.getSlug(),
                    ReorderRequestDTO.builder().position(0).beforeSlug("alpha").build(), null))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("exactly one");
        }

        @Test
        @DisplayName("a document anchor from another project is a 404")
        void anchorMustBelongToTheSameProject() {
            DocumentDTO alpha = createDocument(projectId, "Alpha", "a");
            Long otherProject = createProject("Other Playground", "other").getId();
            createDocument(otherProject, "Foreign", "f");

            assertThatThrownBy(() -> documentService.reorderDocument(projectId, alpha.getSlug(),
                    ReorderRequestDTO.builder().beforeSlug("foreign").build(), null))
                    .isInstanceOf(EntityNotFoundException.class);
        }

        @Test
        @DisplayName("pinning a document does not pin the project it lives in")
        void documentPinDoesNotTouchTheProject() {
            DocumentDTO doc = createDocument(projectId, "Alpha", "a");
            documentService.pinDocument(projectId, doc.getSlug(), null);

            assertThat(projectService.getProjectBySlug("docs-playground", null).isPinned()).isFalse();
        }
    }

    // =======================================================================
    @Nested
    @DisplayName("pinned / sort_order survive a whole-entity save")
    class NonUpdatableDisplayColumns {

        private Long projectId;

        @BeforeEach
        void seedProject() {
            projectId = createProject("Stale Save Playground", "the project for stale writes").getId();
        }

        private Document docBySlug(String slug) {
            return documentRepository.findBySlugAndProjectId(slug, projectId).orElseThrow();
        }

        /**
         * WIKI4AI-197 commit 10. A content save loads the entity and writes it back, so the
         * UPDATE it issues carries every column the loaded row had. If {@code pinned} and
         * {@code sort_order} were updatable, a pin or a sidebar drag that landed between that
         * read and that write would be silently reverted — and the optimistic-lock check
         * would not catch it, because pinning deliberately does not bump {@code @Version}.
         *
         * <p>The stale copy is read through the repository rather than the service: the
         * service loads and saves inside one transaction, so it can never carry a value
         * older than its own read. The stale window is real in production because a request
         * holds the entity from the moment it resolves it until the moment it flushes (OSIV
         * is on), and a drag can commit in between.</p>
         */
        @Test
        @DisplayName("a stale document save cannot un-pin the document")
        void staleDocumentSaveKeepsPinned() {
            DocumentDTO doc = createDocument(projectId, "Alpha Doc", "original");

            Document stale = docBySlug("alpha-doc");

            documentService.pinDocument(projectId, doc.getSlug(), null);
            assertThat(docBySlug("alpha-doc").isPinned()).isTrue();

            stale.setContent("edited later");
            documentRepository.save(stale);

            Document after = docBySlug("alpha-doc");
            assertThat(after.getContent()).isEqualTo("edited later");
            assertThat(after.isPinned()).isTrue();
        }

        @Test
        @DisplayName("a stale document save cannot reset sort_order")
        void staleDocumentSaveKeepsSortOrder() {
            createDocument(projectId, "Alpha Doc", "a");
            DocumentDTO two = createDocument(projectId, "Bravo Doc", "b");

            Document stale = docBySlug("alpha-doc");

            documentService.reorderDocument(projectId, two.getSlug(),
                    ReorderRequestDTO.builder().position(0).build(), null);
            int afterReorder = docBySlug("alpha-doc").getSortOrder();

            stale.setContent("edited later");
            documentRepository.save(stale);

            Document after = docBySlug("alpha-doc");
            assertThat(after.getContent()).isEqualTo("edited later");
            assertThat(after.getSortOrder()).isEqualTo(afterReorder);
        }

        @Test
        @DisplayName("a stale project save cannot un-pin the project")
        void staleProjectSaveKeepsPinned() {
            ProjectDTO project = createProject("Alpha", "a");

            Project stale = projectRepository.findBySlug("alpha").orElseThrow();

            projectService.pinProject(project.getSlug(), null);
            assertThat(projectRepository.findBySlug("alpha").orElseThrow().isPinned()).isTrue();

            stale.setDescription("edited later");
            projectRepository.save(stale);

            Project after = projectRepository.findBySlug("alpha").orElseThrow();
            assertThat(after.getDescription()).isEqualTo("edited later");
            assertThat(after.isPinned()).isTrue();
        }

        @Test
        @DisplayName("a stale project save cannot reset sort_order")
        void staleProjectSaveKeepsSortOrder() {
            ProjectDTO project = createProject("Alpha", "a");
            createProject("Bravo", "b");

            Project stale = projectRepository.findBySlug("alpha").orElseThrow();

            projectService.reorderProject(project.getSlug(),
                    ReorderRequestDTO.builder().position(1).build(), null);
            int afterReorder = projectRepository.findBySlug("alpha").orElseThrow().getSortOrder();

            stale.setDescription("edited later");
            projectRepository.save(stale);

            Project after = projectRepository.findBySlug("alpha").orElseThrow();
            assertThat(after.getDescription()).isEqualTo("edited later");
            assertThat(after.getSortOrder()).isEqualTo(afterReorder);
        }

        /**
         * {@code updatable = false} must leave INSERTs alone. Both create paths set
         * sort_order on the entity before inserting it (the "new item on top" trick uses
         * min−1), so if Hibernate dropped the column from the INSERT every new row would
         * land at 0 and the manual order would collapse.
         */
        @Test
        @DisplayName("a brand-new row still gets its sort_order")
        void newRowsStillInsertTheirSortOrder() {
            ProjectDTO project = createProject("Insert Order Check", "a");
            assertThat(projectRepository.findBySlug("insert-order-check").orElseThrow().getSortOrder())
                    .isEqualTo(project.getSortOrder());

            DocumentDTO doc = createDocument(project.getId(), "Insert Order Doc", "a");
            assertThat(documentRepository.findBySlugAndProjectId("insert-order-doc", project.getId())
                    .orElseThrow().getSortOrder())
                    .isEqualTo(doc.getSortOrder());
        }
    }
}
