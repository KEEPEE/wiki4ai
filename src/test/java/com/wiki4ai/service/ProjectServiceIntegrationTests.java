package com.wiki4ai.service;

import com.wiki4ai.dto.ProjectDTO;
import com.wiki4ai.model.Project;
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
}
