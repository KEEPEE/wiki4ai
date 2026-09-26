package com.wiki4ai.service;

import com.wiki4ai.dto.DocumentCreateDTO;
import com.wiki4ai.dto.DocumentDTO;
import com.wiki4ai.dto.DocumentUpdateDTO;
import com.wiki4ai.dto.ProjectCreateDTO;
import com.wiki4ai.dto.ProjectDTO;
import com.wiki4ai.dto.ProjectUpdateDTO;
import com.wiki4ai.exception.BadRequestException;
import com.wiki4ai.model.Document;
import com.wiki4ai.model.Permission;
import com.wiki4ai.model.Project;
import com.wiki4ai.model.ProjectPermission;
import com.wiki4ai.model.Role;
import com.wiki4ai.model.User;
import com.wiki4ai.repository.DocumentRepository;
import com.wiki4ai.repository.ProjectPermissionRepository;
import com.wiki4ai.repository.ProjectRepository;
import com.wiki4ai.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WIKI4AI-99: integration tests for the public/private visibility model on
 * projects and documents (real H2 database, test profile).
 *
 * <p>Rules under test:
 * <ul>
 *   <li>legacy rows (owner = null, visibility = 'public') stay fully public;</li>
 *   <li>owner is always the authenticated creator — never from client input;</li>
 *   <li>a PRIVATE project is visible only to its owner and ADMIN; for everyone
 *       else it is 404 and absent from lists (existence not revealed);</li>
 *   <li>a PRIVATE document inside a public project is visible only to its owner
 *       and ADMIN; for others it is 404 / absent from lists and search;</li>
 *   <li>public records keep the exact pre-privacy behaviour, including
 *       project_permissions sharing.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class VisibilityIntegrationTests {

    private static final String ADMIN = "vis_admin";
    private static final String ALICE = "vis_alice";
    private static final String BOB = "vis_bob";

    @Autowired
    private ProjectService projectService;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private ProjectPermissionRepository projectPermissionRepository;

    @BeforeEach
    void setUp() {
        // Clean up state from previous tests (H2 "testdb" is shared across contexts).
        documentRepository.deleteAll();
        projectPermissionRepository.deleteAll();
        projectRepository.deleteAll();
        for (String name : List.of(ADMIN, ALICE, BOB)) {
            userRepository.findByUsername(name).ifPresent(userRepository::delete);
        }

        saveUser(ADMIN, Role.ADMIN);
        saveUser(ALICE, Role.USER);
        saveUser(BOB, Role.USER);
    }

    private User saveUser(String username, Role role) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@test.local");
        user.setPassword("not-a-real-hash");
        user.setRole(role);
        return userRepository.save(user);
    }

    private User user(String username) {
        return userRepository.findByUsername(username).orElseThrow();
    }

    // ── Projects ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Project visibility")
    class ProjectVisibilityTests {

        @Test
        @DisplayName("Legacy project (no owner, public) is visible to every user")
        void legacyProjectStaysPublic() {
            // given — simulate a pre-V14 row: owner NULL, visibility 'public'
            Project legacy = new Project();
            legacy.setName("Legacy Project");
            legacy.setSlug("legacy-project");
            legacy.setVisibility(Project.VISIBILITY_PUBLIC);
            projectRepository.save(legacy);

            // when & then — BOB (not the owner, not admin) can read and list it
            ProjectDTO forBob = projectService.getProjectBySlug("legacy-project", BOB);
            assertThat(forBob.getId()).isEqualTo(legacy.getId());
            assertThat(projectService.getAllProjects(BOB))
                    .extracting(ProjectDTO::getSlug)
                    .contains("legacy-project");
        }

        @Test
        @DisplayName("Created project gets the creator as owner and defaults to public")
        void createProjectSetsOwnerAndDefaultsPublic() {
            // when
            ProjectDTO created = projectService.createProject(
                    ProjectCreateDTO.builder().name("Alice Wiki").build(), ALICE);

            // then
            assertThat(created.getVisibility()).isEqualTo(Project.VISIBILITY_PUBLIC);
            assertThat(created.getOwnerId()).isEqualTo(user(ALICE).getId());

            // and BOB can see it (public)
            assertThat(projectService.getAllProjects(BOB))
                    .extracting(ProjectDTO::getSlug)
                    .contains(created.getSlug());
        }

        @Test
        @DisplayName("Explicit visibility is stored; invalid value is rejected with 400")
        void createProjectValidatesVisibility() {
            ProjectDTO privateCreated = projectService.createProject(
                    ProjectCreateDTO.builder().name("Alice Private").visibility("private").build(), ALICE);
            assertThat(privateCreated.getVisibility()).isEqualTo(Project.VISIBILITY_PRIVATE);

            ProjectDTO upperCase = projectService.createProject(
                    ProjectCreateDTO.builder().name("Alice Upper").visibility("PRIVATE").build(), ALICE);
            assertThat(upperCase.getVisibility()).isEqualTo(Project.VISIBILITY_PRIVATE);

            assertThatThrownBy(() -> projectService.createProject(
                    ProjectCreateDTO.builder().name("Bad Visibility").visibility("internal").build(), ALICE))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Invalid visibility");
        }

        @Test
        @DisplayName("Foreign private project is 404 for other users and absent from their list")
        void foreignPrivateProjectIsHidden() {
            // given
            ProjectDTO alicePrivate = projectService.createProject(
                    ProjectCreateDTO.builder().name("Alice Secret").visibility("private").build(), ALICE);

            // when & then — BOB gets 404 (not 403) and does not see it in the list
            assertThatThrownBy(() -> projectService.getProjectBySlug(alicePrivate.getSlug(), BOB))
                    .isInstanceOf(EntityNotFoundException.class);
            assertThatThrownBy(() -> projectService.getProjectById(alicePrivate.getId(), BOB))
                    .isInstanceOf(EntityNotFoundException.class);
            assertThat(projectService.getAllProjects(BOB))
                    .extracting(ProjectDTO::getId)
                    .doesNotContain(alicePrivate.getId());

            // anonymous callers (public GET endpoints) see it neither
            assertThat(projectService.getAllProjects())
                    .extracting(ProjectDTO::getId)
                    .doesNotContain(alicePrivate.getId());
        }

        @Test
        @DisplayName("Owner and ADMIN can read a foreign private project")
        void ownerAndAdminSeePrivateProject() {
            // given
            ProjectDTO alicePrivate = projectService.createProject(
                    ProjectCreateDTO.builder().name("Alice Secret").visibility("private").build(), ALICE);

            // when & then
            assertThat(projectService.getProjectBySlug(alicePrivate.getSlug(), ALICE).getId())
                    .isEqualTo(alicePrivate.getId());
            assertThat(projectService.getProjectBySlug(alicePrivate.getSlug(), ADMIN).getId())
                    .isEqualTo(alicePrivate.getId());
            assertThat(projectService.getAllProjects(ADMIN))
                    .extracting(ProjectDTO::getId)
                    .contains(alicePrivate.getId());
        }

        @Test
        @DisplayName("Owner can flip project visibility; a foreign private project is 404 for updates")
        void updateProjectVisibility() {
            // given
            ProjectDTO created = projectService.createProject(
                    ProjectCreateDTO.builder().name("Flip Me").build(), ALICE);

            // when — owner flips to private
            ProjectDTO nowPrivate = projectService.updateProjectBySlug(created.getSlug(),
                    ProjectUpdateDTO.builder().visibility("private").build(), ALICE);
            assertThat(nowPrivate.getVisibility()).isEqualTo(Project.VISIBILITY_PRIVATE);

            // and BOB loses access (404)
            assertThatThrownBy(() -> projectService.getProjectBySlug(created.getSlug(), BOB))
                    .isInstanceOf(EntityNotFoundException.class);

            // when — owner flips back to public
            ProjectDTO nowPublic = projectService.updateProjectBySlug(created.getSlug(),
                    ProjectUpdateDTO.builder().visibility("public").build(), ALICE);
            assertThat(nowPublic.getVisibility()).isEqualTo(Project.VISIBILITY_PUBLIC);
            assertThat(projectService.getProjectBySlug(created.getSlug(), BOB).getId())
                    .isEqualTo(created.getId());

            // and a foreign private project cannot be updated by a non-owner (404, not 403)
            ProjectDTO secret = projectService.createProject(
                    ProjectCreateDTO.builder().name("Bob Cannot Touch").visibility("private").build(), ALICE);
            assertThatThrownBy(() -> projectService.updateProjectBySlug(secret.getSlug(),
                    ProjectUpdateDTO.builder().description("sneaky").build(), BOB))
                    .isInstanceOf(EntityNotFoundException.class);
        }

        @Test
        @DisplayName("Foreign private project is 404 for delete as well")
        void deleteForeignPrivateProjectIsHidden() {
            // given
            ProjectDTO alicePrivate = projectService.createProject(
                    ProjectCreateDTO.builder().name("Do Not Delete").visibility("private").build(), ALICE);

            // when & then
            assertThatThrownBy(() -> projectService.deleteProject(alicePrivate.getId(), BOB))
                    .isInstanceOf(EntityNotFoundException.class);
            assertThat(projectRepository.existsById(alicePrivate.getId())).isTrue();

            // owner can still delete it
            projectService.deleteProject(alicePrivate.getId(), ALICE);
            assertThat(projectRepository.existsById(alicePrivate.getId())).isFalse();
        }

        @Test
        @DisplayName("project_permissions do NOT open a foreign private project (privacy wins)")
        void permissionsDoNotOpenPrivateProject() {
            // given — ALICE's private project, BOB granted MANAGE on it
            ProjectDTO alicePrivate = projectService.createProject(
                    ProjectCreateDTO.builder().name("Perm Secret").visibility("private").build(), ALICE);
            projectPermissionRepository.save(ProjectPermission.builder()
                    .project(projectRepository.findById(alicePrivate.getId()).orElseThrow())
                    .user(user(BOB))
                    .permissions(List.of(Permission.MANAGE))
                    .build());

            // when & then — BOB still gets 404 despite the explicit grant
            assertThatThrownBy(() -> projectService.getProjectBySlug(alicePrivate.getSlug(), BOB))
                    .isInstanceOf(EntityNotFoundException.class);
        }
    }

    // ── Documents ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Document visibility")
    class DocumentVisibilityTests {

        private ProjectDTO publicProject;

        @BeforeEach
        void createPublicHostProject() {
            publicProject = projectService.createProject(
                    ProjectCreateDTO.builder().name("Shared Wiki").build(), ALICE);
        }

        @Test
        @DisplayName("Created document gets the creator as owner and defaults to public")
        void createDocumentSetsOwnerAndDefaultsPublic() {
            // when
            DocumentDTO created = documentService.createDocument(publicProject.getId(),
                    DocumentCreateDTO.builder().title("Open Note").content("hello").build(), ALICE);

            // then
            assertThat(created.getVisibility()).isEqualTo(Document.VISIBILITY_PUBLIC);
            assertThat(created.getOwnerId()).isEqualTo(user(ALICE).getId());

            // and BOB can read it (public)
            assertThat(documentService.getDocumentById(created.getId(), BOB).getId())
                    .isEqualTo(created.getId());
        }

        @Test
        @DisplayName("Foreign private document in a public project is 404 and absent from lists")
        void foreignPrivateDocumentIsHidden() {
            // given — ALICE's private doc inside the public project
            DocumentDTO privateDoc = documentService.createDocument(publicProject.getId(),
                    DocumentCreateDTO.builder().title("Secret Note").content("top secret content")
                            .visibility("private").build(), ALICE);

            // when & then — BOB gets 404 on detail, by slug and in the project list
            assertThatThrownBy(() -> documentService.getDocumentById(privateDoc.getId(), BOB))
                    .isInstanceOf(EntityNotFoundException.class);
            assertThatThrownBy(() -> documentService.getDocument(publicProject.getId(), privateDoc.getSlug(), BOB))
                    .isInstanceOf(EntityNotFoundException.class);
            assertThat(documentService.getDocumentsByProject(publicProject.getId(), BOB))
                    .extracting(DocumentDTO::getId)
                    .doesNotContain(privateDoc.getId());

            // and it is absent from per-project search results for BOB
            assertThat(documentService.searchDocuments(publicProject.getId(), "top secret", BOB))
                    .extracting(DocumentDTO::getId)
                    .doesNotContain(privateDoc.getId());

            // owner and ADMIN still see everything
            assertThat(documentService.getDocumentById(privateDoc.getId(), ALICE).getId())
                    .isEqualTo(privateDoc.getId());
            assertThat(documentService.getDocumentById(privateDoc.getId(), ADMIN).getId())
                    .isEqualTo(privateDoc.getId());
            assertThat(documentService.searchDocuments(publicProject.getId(), "top secret", ADMIN))
                    .extracting(DocumentDTO::getId)
                    .contains(privateDoc.getId());
        }

        @Test
        @DisplayName("Private document created directly with visibility=private")
        void createDocumentWithPrivateVisibility() {
            // when
            DocumentDTO privateDoc = documentService.createDocument(publicProject.getId(),
                    DocumentCreateDTO.builder().title("Born Private").content("hidden at birth")
                            .visibility("private").build(), ALICE);

            // then
            assertThat(privateDoc.getVisibility()).isEqualTo(Document.VISIBILITY_PRIVATE);
            assertThat(privateDoc.getOwnerId()).isEqualTo(user(ALICE).getId());
            assertThatThrownBy(() -> documentService.getDocumentById(privateDoc.getId(), BOB))
                    .isInstanceOf(EntityNotFoundException.class);
        }

        @Test
        @DisplayName("Invalid visibility on document create/update is rejected with 400")
        void documentVisibilityValidation() {
            assertThatThrownBy(() -> documentService.createDocument(publicProject.getId(),
                    DocumentCreateDTO.builder().title("Bad").content("x").visibility("internal").build(), ALICE))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Invalid visibility");

            DocumentDTO doc = documentService.createDocument(publicProject.getId(),
                    DocumentCreateDTO.builder().title("Valid").content("x").build(), ALICE);
            assertThatThrownBy(() -> documentService.updateDocument(doc.getId(),
                    DocumentUpdateDTO.builder().visibility("hidden").build(), ALICE))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Invalid visibility");
        }

        @Test
        @DisplayName("Visibility-only update is allowed (no title/content needed)")
        void visibilityOnlyUpdate() {
            // given
            DocumentDTO doc = documentService.createDocument(publicProject.getId(),
                    DocumentCreateDTO.builder().title("Flip Doc").content("x").build(), ALICE);

            // when — owner flips to private with a visibility-only payload
            DocumentDTO nowPrivate = documentService.updateDocument(doc.getId(),
                    DocumentUpdateDTO.builder().visibility("private").build(), ALICE);

            // then
            assertThat(nowPrivate.getVisibility()).isEqualTo(Document.VISIBILITY_PRIVATE);
            assertThatThrownBy(() -> documentService.getDocumentById(doc.getId(), BOB))
                    .isInstanceOf(EntityNotFoundException.class);
        }

        @Test
        @DisplayName("Foreign private document is 404 for update and delete (not 403)")
        void foreignPrivateDocumentUpdateDeleteHidden() {
            // given — ALICE's private doc; BOB has MANAGE on the public project
            DocumentDTO secret = documentService.createDocument(publicProject.getId(),
                    DocumentCreateDTO.builder().title("Untouchable").content("x")
                            .visibility("private").build(), ALICE);
            projectPermissionRepository.save(ProjectPermission.builder()
                    .project(projectRepository.findById(publicProject.getId()).orElseThrow())
                    .user(user(BOB))
                    .permissions(List.of(Permission.MANAGE))
                    .build());

            // when & then — even with MANAGE, BOB gets 404 (privacy wins over permissions)
            assertThatThrownBy(() -> documentService.updateDocument(secret.getId(),
                    DocumentUpdateDTO.builder().content("hacked").build(), BOB))
                    .isInstanceOf(EntityNotFoundException.class);
            assertThatThrownBy(() -> documentService.deleteDocument(secret.getId(), BOB))
                    .isInstanceOf(EntityNotFoundException.class);

            // the document survives
            assertThat(documentRepository.existsById(secret.getId())).isTrue();

            // owner can update and delete it
            documentService.updateDocument(secret.getId(),
                    DocumentUpdateDTO.builder().content("owner edit").build(), ALICE);
            documentService.deleteDocument(secret.getId(), ALICE);
            assertThat(documentRepository.existsById(secret.getId())).isFalse();
        }

        @Test
        @DisplayName("Documents inside a foreign private project are unreachable")
        void docsInForeignPrivateProjectHidden() {
            // given — ALICE's private project with a doc (created by ALICE)
            ProjectDTO alicePrivate = projectService.createProject(
                    ProjectCreateDTO.builder().name("Vault").visibility("private").build(), ALICE);
            DocumentDTO doc = documentService.createDocument(alicePrivate.getId(),
                    DocumentCreateDTO.builder().title("Inside Vault").content("vault content").build(), ALICE);

            // when & then — BOB cannot list, read or search docs in the project
            assertThatThrownBy(() -> documentService.getDocumentsByProject(alicePrivate.getId(), BOB))
                    .isInstanceOf(EntityNotFoundException.class);
            assertThatThrownBy(() -> documentService.getDocumentById(doc.getId(), BOB))
                    .isInstanceOf(EntityNotFoundException.class);
            assertThatThrownBy(() -> documentService.searchDocuments(alicePrivate.getId(), "vault", BOB))
                    .isInstanceOf(EntityNotFoundException.class);

            // ADMIN can
            assertThat(documentService.getDocumentsByProject(alicePrivate.getId(), ADMIN))
                    .extracting(DocumentDTO::getId)
                    .contains(doc.getId());
        }

        @Test
        @DisplayName("Global search hides foreign private docs and docs in foreign private projects")
        void globalSearchRespectsVisibility() {
            // given — three documents with a shared keyword
            documentService.createDocument(publicProject.getId(),
                    DocumentCreateDTO.builder().title("G Public").content("globalsearchterm public").build(), ALICE);
            documentService.createDocument(publicProject.getId(),
                    DocumentCreateDTO.builder().title("G Private Doc").content("globalsearchterm private doc")
                            .visibility("private").build(), ALICE);
            ProjectDTO alicePrivate = projectService.createProject(
                    ProjectCreateDTO.builder().name("G Vault").visibility("private").build(), ALICE);
            documentService.createDocument(alicePrivate.getId(),
                    DocumentCreateDTO.builder().title("G In Private Project").content("globalsearchterm in vault")
                            .build(), ALICE);

            // when — BOB searches globally
            List<com.wiki4ai.dto.GlobalSearchResultDTO> bobResults =
                    documentService.searchDocumentsGlobal("globalsearchterm", BOB, 50);

            // then — only the public one is visible to BOB
            assertThat(bobResults)
                    .extracting(com.wiki4ai.dto.GlobalSearchResultDTO::getTitle)
                    .containsExactly("G Public");

            // and ADMIN sees all three
            List<com.wiki4ai.dto.GlobalSearchResultDTO> adminResults =
                    documentService.searchDocumentsGlobal("globalsearchterm", ADMIN, 50);
            assertThat(adminResults)
                    .extracting(com.wiki4ai.dto.GlobalSearchResultDTO::getTitle)
                    .containsExactlyInAnyOrder("G Public", "G Private Doc", "G In Private Project");
        }

        @Test
        @DisplayName("Copy inherits source visibility and is owned by the copier")
        void copyInheritsVisibility() {
            // given — ALICE's private doc in the public project
            DocumentDTO secret = documentService.createDocument(publicProject.getId(),
                    DocumentCreateDTO.builder().title("Copy Me").content("copy content")
                            .visibility("private").build(), ALICE);

            // when — ADMIN copies it (allowed: admin sees everything)
            DocumentDTO copy = documentService.copyDocument(publicProject.getId(), secret.getSlug(), null, ADMIN);

            // then — the copy stays private and belongs to ADMIN
            assertThat(copy.getVisibility()).isEqualTo(Document.VISIBILITY_PRIVATE);
            assertThat(copy.getOwnerId()).isEqualTo(user(ADMIN).getId());
            assertThatThrownBy(() -> documentService.getDocumentById(copy.getId(), BOB))
                    .isInstanceOf(EntityNotFoundException.class);

            // BOB cannot copy ALICE's private doc at all (404)
            assertThatThrownBy(() -> documentService.copyDocument(publicProject.getId(), secret.getSlug(), null, BOB))
                    .isInstanceOf(EntityNotFoundException.class);
        }

        @Test
        @DisplayName("Move into a foreign private project is 404")
        void moveIntoForeignPrivateProjectHidden() {
            // given — ALICE's private target project; BOB has MANAGE on the public
            // host project (so he may create and move docs there)
            ProjectDTO alicePrivate = projectService.createProject(
                    ProjectCreateDTO.builder().name("Move Vault").visibility("private").build(), ALICE);
            projectPermissionRepository.save(ProjectPermission.builder()
                    .project(projectRepository.findById(publicProject.getId()).orElseThrow())
                    .user(user(BOB))
                    .permissions(List.of(Permission.MANAGE))
                    .build());
            DocumentDTO movable = documentService.createDocument(publicProject.getId(),
                    DocumentCreateDTO.builder().title("Movable").content("move me").build(), BOB);

            // when & then — BOB cannot move his doc into ALICE's private project (404)
            assertThatThrownBy(() -> documentService.moveDocument(
                    publicProject.getId(), movable.getSlug(), alicePrivate.getSlug(), BOB))
                    .isInstanceOf(EntityNotFoundException.class);

            // ADMIN can
            DocumentDTO moved = documentService.moveDocument(
                    publicProject.getId(), movable.getSlug(), alicePrivate.getSlug(), ADMIN);
            assertThat(moved.getProjectId()).isEqualTo(alicePrivate.getId());
        }

        @Test
        @DisplayName("Public project with project_permissions behaves exactly as before")
        void publicProjectSharingUnchanged() {
            // given — BOB granted MANAGE on ALICE's PUBLIC project
            projectPermissionRepository.save(ProjectPermission.builder()
                    .project(projectRepository.findById(publicProject.getId()).orElseThrow())
                    .user(user(BOB))
                    .permissions(List.of(Permission.MANAGE))
                    .build());

            // when — BOB creates and updates a document there
            DocumentDTO created = documentService.createDocument(publicProject.getId(),
                    DocumentCreateDTO.builder().title("Bob Doc").content("bob content").build(), BOB);
            assertThat(created.getOwnerId()).isEqualTo(user(BOB).getId());

            DocumentDTO updated = documentService.updateDocument(created.getId(),
                    DocumentUpdateDTO.builder().content("bob edit").build(), BOB);
            assertThat(updated.getContent()).isEqualTo("bob edit");

            // and ALICE (project owner) sees Bob's public doc
            Optional<Document> aliceView = documentRepository.findBySlugAndProjectId(
                    created.getSlug(), publicProject.getId());
            assertThat(aliceView).isPresent();
        }
    }
}
