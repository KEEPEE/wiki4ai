package com.wiki4ai.service;

import com.wiki4ai.dto.ProjectCreateDTO;
import com.wiki4ai.dto.ProjectDTO;
import com.wiki4ai.exception.BadRequestException;
import com.wiki4ai.model.Document;
import com.wiki4ai.model.Project;
import com.wiki4ai.repository.DocumentRepository;
import com.wiki4ai.repository.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for ProjectService using real database (H2).
 * Uses @SpringBootTest with test profile to load full application context.
 */
@SpringBootTest
@ActiveProfiles("test")
class ProjectServiceIntegrationTests {

    @Autowired
    private ProjectService projectService;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private ProjectRepository projectRepository;

    // Clean up before each test to ensure isolation
    @BeforeEach
    void setUp() {
        // Get all projects and delete them for clean state
        List<ProjectDTO> all = projectService.getAllProjects();
        for (ProjectDTO p : all) {
            try {
                projectService.deleteProject(p.getId());
            } catch (Exception ignored) {
            }
        }
    }

    @Nested
    @DisplayName("Create Project Integration Tests")
    class CreateProjectIntegrationTests {

        @Test
        @DisplayName("Should create a new project with auto-generated slug")
        void shouldCreateProjectWithAutoSlug() {
            // when
            ProjectDTO created = projectService.createProject(
                    ProjectDTO.builder().name("My Wiki").description("First wiki").build()
            );

            // then
            assertThat(created.getId()).isNotNull();
            assertThat(created.getName()).isEqualTo("My Wiki");
            assertThat(created.getDescription()).isEqualTo("First wiki");
            assertThat(created.getSlug()).isEqualTo("my-wiki");
            assertThat(created.getCreatedAt()).isNotNull();
            assertThat(created.getUpdatedAt()).isNotNull();
        }

        @Test
        @DisplayName("Should reject creation of project with duplicate name")
        void shouldRejectDuplicateName() {
            // given
            projectService.createProject(ProjectDTO.builder().name("Unique Name").build());

            // when & then
            assertThatThrownBy(() ->
                    projectService.createProject(ProjectDTO.builder().name("Unique Name").build())
            ).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("A project with this name already exists");
        }

        @Test
        @DisplayName("Should allow different names to create different projects")
        void shouldAllowDifferentNames() {
            // when
            ProjectDTO first = projectService.createProject(ProjectDTO.builder().name("First").build());
            ProjectDTO second = projectService.createProject(ProjectDTO.builder().name("Second").build());

            // then
            assertThat(first.getId()).isNotEqualTo(second.getId());
        }
    }

    @Nested
    @DisplayName("Read Project Integration Tests")
    class ReadProjectIntegrationTests {

        @Test
        @DisplayName("Should retrieve all projects ordered by creation date descending")
        void shouldReturnAllProjectsOrdered() {
            // given
            projectService.createProject(ProjectDTO.builder().name("Older").build());
            try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            projectService.createProject(ProjectDTO.builder().name("Newer").build());

            // when
            List<ProjectDTO> projects = projectService.getAllProjects();

            // then
            assertThat(projects).hasSize(2);
            assertThat(projects.get(0).getName()).isEqualTo("Newer");
            assertThat(projects.get(1).getName()).isEqualTo("Older");
        }

        @Test
        @DisplayName("Should retrieve project by ID")
        void shouldGetProjectById() {
            // given
            ProjectDTO created = projectService.createProject(ProjectDTO.builder().name("ByID").build());

            // when
            ProjectDTO found = projectService.getProjectById(created.getId());

            // then
            assertThat(found).isNotNull();
            assertThat(found.getName()).isEqualTo("ByID");
        }

        @Test
        @DisplayName("Should throw when getting non-existent project by ID")
        void shouldThrowWhenGetNonExistentById() {
            assertThatThrownBy(() -> projectService.getProjectById(999L))
                    .isInstanceOf(jakarta.persistence.EntityNotFoundException.class)
                    .hasMessageContaining("Project not found with id: 999");
        }

        @Test
        @DisplayName("Should retrieve project by slug")
        void shouldGetProjectBySlug() {
            // given
            ProjectDTO created = projectService.createProject(ProjectDTO.builder().name("By Slug").build());

            // when
            ProjectDTO found = projectService.getProjectBySlug(created.getSlug());

            // then
            assertThat(found).isNotNull();
            assertThat(found.getName()).isEqualTo("By Slug");
        }

        @Test
        @DisplayName("Should throw when getting non-existent project by slug")
        void shouldThrowWhenGetNonExistentBySlug() {
            assertThatThrownBy(() -> projectService.getProjectBySlug("non-existent-slug"))
                    .isInstanceOf(jakarta.persistence.EntityNotFoundException.class)
                    .hasMessageContaining("Project not found with slug: non-existent-slug");
        }

        @Test
        @DisplayName("Should return empty list when no projects exist")
        void shouldReturnEmptyListWhenNoProjects() {
            assertThat(projectService.getAllProjects()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Update Project Integration Tests")
    class UpdateProjectIntegrationTests {

        @Test
        @DisplayName("Should update project name and description")
        void shouldUpdateProjectFields() {
            // given
            ProjectDTO created = projectService.createProject(
                    ProjectDTO.builder().name("Original").description("Old desc").build()
            );

            // when
            ProjectDTO updated = projectService.updateProject(created.getId(),
                    ProjectDTO.builder().name("Updated").description("New desc").build()
            );

            // then
            assertThat(updated.getName()).isEqualTo("Updated");
            assertThat(updated.getDescription()).isEqualTo("New desc");

            // Verify the update persisted
            ProjectDTO retrieved = projectService.getProjectById(created.getId());
            assertThat(retrieved.getName()).isEqualTo("Updated");
        }

        @Test
        @DisplayName("Should throw when updating non-existent project")
        void shouldThrowWhenUpdateNonExistent() {
            assertThatThrownBy(() ->
                    projectService.updateProject(999L, ProjectDTO.builder().name("X").build())
            ).isInstanceOf(jakarta.persistence.EntityNotFoundException.class)
                    .hasMessageContaining("Project not found with id: 999");
        }

        @Test
        @DisplayName("Should auto-generate new slug when name is updated")
        void shouldAutoGenerateSlugOnNameUpdate() {
            // given
            ProjectDTO created = projectService.createProject(
                    ProjectDTO.builder().name("Old Name").build()
            );
            assertThat(created.getSlug()).isEqualTo("old-name");

            // when
            ProjectDTO updated = projectService.updateProject(created.getId(),
                    ProjectDTO.builder().name("New Name").build()
            );

            // then - the slug should be regenerated via Project.setName()
            assertThat(updated.getSlug()).isEqualTo("new-name");
        }
    }

    @Nested
    @DisplayName("Delete Project Integration Tests")
    class DeleteProjectIntegrationTests {

        @Test
        @DisplayName("Should delete an existing project")
        void shouldDeleteExistingProject() {
            // given
            ProjectDTO created = projectService.createProject(ProjectDTO.builder().name("To Delete").build());
            Long id = created.getId();

            // when
            projectService.deleteProject(id);

            // then - verify it's gone
            assertThatThrownBy(() -> projectService.getProjectById(id))
                    .isInstanceOf(jakarta.persistence.EntityNotFoundException.class);
        }

        @Test
        @DisplayName("Should throw when deleting non-existent project")
        void shouldThrowWhenDeleteNonExistent() {
            assertThatThrownBy(() -> projectService.deleteProject(999L))
                    .isInstanceOf(jakarta.persistence.EntityNotFoundException.class)
                    .hasMessageContaining("Project not found with id: 999");
        }

        @Test
        @DisplayName("Should be able to create a new project after deleting one")
        void shouldAllowRecreateAfterDelete() {
            // given
            ProjectDTO created = projectService.createProject(ProjectDTO.builder().name("Temp").build());
            projectService.deleteProject(created.getId());

            // when & then - same name should be allowed now
            ProjectDTO recreated = projectService.createProject(ProjectDTO.builder().name("Temp").build());
            assertThat(recreated).isNotNull();
        }
    }

    @Nested
    @DisplayName("Slug Generation Integration Tests")
    class SlugGenerationIntegrationTests {

        @Test
        @DisplayName("Should generate slug with special characters removed")
        void shouldHandleSpecialCharacters() {
            // when
            ProjectDTO created = projectService.createProject(
                    ProjectDTO.builder().name("Hello World! @#$%").build()
            );

            // then
            assertThat(created.getSlug()).isEqualTo("hello-world");
        }

        @Test
        @DisplayName("Should generate slug with multiple spaces collapsed")
        void shouldCollapseMultipleSpaces() {
            // when
            ProjectDTO created = projectService.createProject(
                    ProjectDTO.builder().name("  Multiple   Spaces  ").build()
            );

            // then
            assertThat(created.getSlug()).isEqualTo("multiple-spaces");
        }

        @Test
        @DisplayName("Should generate lowercase slug")
        void shouldGenerateLowercaseSlug() {
            // when
            ProjectDTO created = projectService.createProject(
                    ProjectDTO.builder().name("MiXeD CaSe NaMe").build()
            );

            // then
            assertThat(created.getSlug()).isEqualTo("mixed-case-name");
        }
    }

    @Nested
    @DisplayName("Full CRUD Cycle Integration Tests")
    class FullCrudCycleTests {

        @Test
        @DisplayName("Should complete full CRUD cycle: Create -> Read -> Update -> Delete")
        void shouldCompleteFullCrudCycle() {
            // CREATE
            ProjectDTO created = projectService.createProject(
                    ProjectDTO.builder().name("CRUD Test").description("Initial").build()
            );
            Long id = created.getId();

            // READ (by ID)
            ProjectDTO byId = projectService.getProjectById(id);
            assertThat(byId.getName()).isEqualTo("CRUD Test");

            // READ (by slug)
            ProjectDTO bySlug = projectService.getProjectBySlug(created.getSlug());
            assertThat(bySlug.getId()).isEqualTo(id);

            // UPDATE
            ProjectDTO updated = projectService.updateProject(id,
                    ProjectDTO.builder().name("Updated CRUD").description("Modified").build()
            );
            assertThat(updated.getName()).isEqualTo("Updated CRUD");

            // READ after update
            ProjectDTO afterUpdate = projectService.getProjectById(id);
            assertThat(afterUpdate.getName()).isEqualTo("Updated CRUD");
            assertThat(afterUpdate.getDescription()).isEqualTo("Modified");

            // DELETE
            projectService.deleteProject(id);

            // VERIFY deleted
            assertThatThrownBy(() -> projectService.getProjectById(id))
                    .isInstanceOf(jakarta.persistence.EntityNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("Project Hierarchy Integration Tests (WIKI4AI-29)")
    class ProjectHierarchyIntegrationTests {

        private ProjectDTO create(String name, Long parentId) {
            return projectService.createProject(
                    ProjectCreateDTO.builder().name(name).parentId(parentId).build());
        }

        @Test
        @DisplayName("Should create a chain root→L1→L2→L3→L4 (depth 5) and expose depths")
        void shouldCreateChainToDepthFive() {
            // when
            ProjectDTO root = create("Hier Root", null);
            ProjectDTO l1 = create("Hier L1", root.getId());
            ProjectDTO l2 = create("Hier L2", l1.getId());
            ProjectDTO l3 = create("Hier L3", l2.getId());
            ProjectDTO l4 = create("Hier L4", l3.getId());

            // then
            assertThat(root.getDepth()).isEqualTo(1);
            assertThat(root.getParentSlug()).isNull();
            assertThat(l1.getDepth()).isEqualTo(2);
            assertThat(l1.getParentSlug()).isEqualTo(root.getSlug());
            assertThat(l2.getDepth()).isEqualTo(3);
            assertThat(l3.getDepth()).isEqualTo(4);
            assertThat(l4.getDepth()).isEqualTo(5);
            assertThat(l4.getParentSlug()).isEqualTo(l3.getSlug());

            // GET by slug returns parentSlug (e2e contract)
            ProjectDTO fetched = projectService.getProjectBySlug(l4.getSlug());
            assertThat(fetched.getParentSlug()).isEqualTo(l3.getSlug());
        }

        @Test
        @DisplayName("Should reject creating a 6th level under a depth-5 project")
        void shouldRejectDepthSix() {
            // given — chain to depth 5
            ProjectDTO root = create("Deep Root", null);
            ProjectDTO l1 = create("Deep L1", root.getId());
            ProjectDTO l2 = create("Deep L2", l1.getId());
            ProjectDTO l3 = create("Deep L3", l2.getId());
            ProjectDTO l4 = create("Deep L4", l3.getId());

            // when & then
            assertThatThrownBy(() -> create("Deep L5-rejected", l4.getId()))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("maximum hierarchy depth");
        }

        @Test
        @DisplayName("Should reject moving a project under its own subproject (cycle)")
        void shouldRejectCycleOnMove() {
            // given — root with child
            ProjectDTO root = create("Cycle Root", null);
            ProjectDTO child = create("Cycle Child", root.getId());

            // when & then — moving root under its child would create a cycle
            assertThatThrownBy(() -> projectService.moveProjectBySlug(root.getSlug(), child.getId()))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("cycle");

            // hierarchy unchanged
            assertThat(projectService.getProjectBySlug(root.getSlug()).getParentSlug()).isNull();
        }

        @Test
        @DisplayName("Should move a subproject to another parent and recompute depth")
        void shouldMoveSubprojectAndRecomputeDepth() {
            // given — two roots, one child of the first
            ProjectDTO rootA = create("Move Root A", null);
            ProjectDTO rootB = create("Move Root B", null);
            ProjectDTO child = create("Move Child", rootA.getId());
            assertThat(child.getDepth()).isEqualTo(2);

            // when — move child under rootB
            ProjectDTO moved = projectService.moveProjectBySlug(child.getSlug(), rootB.getId());

            // then
            assertThat(moved.getParentSlug()).isEqualTo(rootB.getSlug());
            assertThat(projectService.getProjectBySlug(child.getSlug()).getParentSlug()).isEqualTo(rootB.getSlug());
        }

        @Test
        @DisplayName("Should move a subproject back to root with null newParentId")
        void shouldMoveBackToRoot() {
            // given
            ProjectDTO root = create("Retro Root", null);
            ProjectDTO child = create("Retro Child", root.getId());
            assertThat(child.getDepth()).isEqualTo(2);

            // when — move back to root
            ProjectDTO movedToRoot = projectService.moveProjectBySlug(child.getSlug(), null);

            // then
            assertThat(movedToRoot.getParentSlug()).isNull();
            assertThat(movedToRoot.getDepth()).isEqualTo(1);
            assertThat(projectService.getProjectBySlug(child.getSlug()).getParentSlug()).isNull();
        }

        @Test
        @DisplayName("Update without parentId key must NOT move the project (backward compatible)")
        void updateWithoutParentKeyShouldNotMove() {
            // given — child of a root
            ProjectDTO root = create("Keep Root", null);
            ProjectDTO child = create("Keep Child", root.getId());

            // when — plain name/description update (no parentId key → builder leaves parentIdPresent=false)
            ProjectDTO updated = projectService.updateProjectBySlug(
                    child.getSlug(),
                    com.wiki4ai.dto.ProjectUpdateDTO.builder()
                            .name("Keep Child Renamed")
                            .description("still a subproject")
                            .build());

            // then — still under the same parent
            assertThat(updated.getName()).isEqualTo("Keep Child Renamed");
            assertThat(updated.getParentSlug()).isEqualTo(root.getSlug());
        }

        @Test
        @DisplayName("Deleting a root should cascade to subprojects and their documents")
        void deleteRootShouldCascadeToSubprojectsAndDocuments() {
            // given — root → child, with a document in each
            ProjectDTO root = create("Cascade Root", null);
            ProjectDTO child = create("Cascade Child", root.getId());

            Document docInRoot = Document.builder()
                    .title("Doc In Root")
                    .slug("doc-in-root")
                    .content("# Root doc")
                    .project(projectRepository.findById(root.getId()).orElseThrow())
                    .build();
            documentRepository.save(docInRoot);

            Document docInChild = Document.builder()
                    .title("Doc In Child")
                    .slug("doc-in-child")
                    .content("# Child doc")
                    .project(projectRepository.findById(child.getId()).orElseThrow())
                    .build();
            documentRepository.save(docInChild);

            // when — delete the root project (anonymous path, cascade)
            projectService.deleteProject(root.getId());

            // then — child project and both documents are gone
            assertThatThrownBy(() -> projectService.getProjectById(child.getId()))
                    .isInstanceOf(jakarta.persistence.EntityNotFoundException.class);
            assertThat(documentRepository.findById(docInRoot.getId())).isEmpty();
            assertThat(documentRepository.findById(docInChild.getId())).isEmpty();
        }
    }
}
