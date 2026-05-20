package com.wiki4ai.service;

import com.wiki4ai.dto.ProjectDTO;
import com.wiki4ai.model.Document;
import com.wiki4ai.model.Project;
import com.wiki4ai.repository.DocumentRepository;
import com.wiki4ai.repository.ProjectRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ProjectService using Mockito.
 * Tests pure service logic without database interaction.
 */
@ExtendWith(MockitoExtension.class)
class ProjectServiceTest {

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private PermissionService permissionService;

    @InjectMocks
    private ProjectService projectService;

    private ProjectDTO validProjectDto;
    private Project existingProject;

    @BeforeEach
    void setUp() {
        validProjectDto = ProjectDTO.builder()
                .name("Test Project")
                .description("A test description")
                .build();

        existingProject = Project.builder()
                .id(1L)
                .name("Existing Project")
                .description("Existing description")
                .slug("existing-project")
                .createdAt(LocalDateTime.of(2024, 1, 1, 0, 0))
                .updatedAt(LocalDateTime.of(2024, 1, 1, 0, 0))
                .build();
    }

    @Nested
    @DisplayName("getAllProjects")
    class GetAllProjectsTests {

        @Test
        @DisplayName("Should return all projects ordered by creation date descending")
        void shouldReturnAllProjectsOrdered() {
            // given — mock returns data already sorted (as repository would)
            Project newer = Project.builder()
                    .id(2L)
                    .name("Newer")
                    .slug("newer")
                    .createdAt(LocalDateTime.of(2024, 6, 1, 0, 0))
                    .updatedAt(LocalDateTime.of(2024, 6, 1, 0, 0))
                    .build();
            Project older = Project.builder()
                    .id(1L)
                    .name("Older")
                    .slug("older")
                    .createdAt(LocalDateTime.of(2024, 1, 1, 0, 0))
                    .updatedAt(LocalDateTime.of(2024, 1, 1, 0, 0))
                    .build();

            when(projectRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(newer, older));

            // when
            List<ProjectDTO> result = projectService.getAllProjects();

            // then
            assertThat(result).hasSize(2);
            assertThat(result.get(0).getName()).isEqualTo("Newer");
            assertThat(result.get(1).getName()).isEqualTo("Older");
        }

        @Test
        @DisplayName("Should return empty list when no projects exist")
        void shouldReturnEmptyListWhenNoProjects() {
            // given
            when(projectRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of());

            // when
            List<ProjectDTO> result = projectService.getAllProjects();

            // then
            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("getProjectById")
    class GetProjectByIdTests {

        @Test
        @DisplayName("Should return project DTO when found by ID")
        void shouldReturnProjectDtoWhenFound() {
            // given
            when(projectRepository.findById(1L)).thenReturn(Optional.of(existingProject));

            // when
            ProjectDTO result = projectService.getProjectById(1L);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(1L);
            assertThat(result.getName()).isEqualTo("Existing Project");
            assertThat(result.getSlug()).isEqualTo("existing-project");
        }

        @Test
        @DisplayName("Should throw EntityNotFoundException when project not found by ID")
        void shouldThrowWhenNotFoundById() {
            // given
            when(projectRepository.findById(99L)).thenReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> projectService.getProjectById(99L))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining("Project not found with id: 99");
        }
    }

    @Nested
    @DisplayName("getProjectBySlug")
    class GetProjectBySlugTests {

        @Test
        @DisplayName("Should return project DTO when found by slug")
        void shouldReturnProjectDtoWhenFoundBySlug() {
            // given
            when(projectRepository.findBySlug("existing-project")).thenReturn(Optional.of(existingProject));

            // when
            ProjectDTO result = projectService.getProjectBySlug("existing-project");

            // then
            assertThat(result).isNotNull();
            assertThat(result.getName()).isEqualTo("Existing Project");
        }

        @Test
        @DisplayName("Should throw EntityNotFoundException when project not found by slug")
        void shouldThrowWhenNotFoundBySlug() {
            // given
            when(projectRepository.findBySlug("non-existent")).thenReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> projectService.getProjectBySlug("non-existent"))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining("Project not found with slug: non-existent");
        }
    }

    @Nested
    @DisplayName("createProject")
    class CreateProjectTests {

        @Test
        @DisplayName("Should create a new project and return DTO")
        void shouldCreateNewProject() {
            // given
            when(projectRepository.existsByName("Test Project")).thenReturn(false);
            when(projectRepository.save(any(Project.class))).thenAnswer(invocation -> {
                Project p = invocation.getArgument(0);
                Project saved = Project.builder()
                        .id(1L)
                        .name(p.getName())
                        .description(p.getDescription())
                        .slug("test-project")
                        .createdAt(LocalDateTime.now())
                        .updatedAt(LocalDateTime.now())
                        .build();
                return saved;
            });

            // when
            ProjectDTO result = projectService.createProject(validProjectDto);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(1L);
            assertThat(result.getName()).isEqualTo("Test Project");
            assertThat(result.getDescription()).isEqualTo("A test description");
            assertThat(result.getSlug()).isEqualTo("test-project");
            verify(projectRepository, times(1)).save(any(Project.class));
        }

        @Test
        @DisplayName("Should throw IllegalArgumentException when name already exists")
        void shouldThrowWhenNameExists() {
            // given
            when(projectRepository.existsByName("Duplicate")).thenReturn(true);
            ProjectDTO duplicateDto = ProjectDTO.builder().name("Duplicate").build();

            // when & then
            assertThatThrownBy(() -> projectService.createProject(duplicateDto))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("A project with this name already exists");

            verify(projectRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should generate slug from project name")
        void shouldGenerateSlugFromName() {
            // given
            ProjectDTO dtoWithSpaces = ProjectDTO.builder()
                    .name("My Wiki Project!")
                    .description("Description")
                    .build();
            when(projectRepository.existsByName("My Wiki Project!")).thenReturn(false);
            when(projectRepository.save(any(Project.class))).thenAnswer(invocation -> {
                Project p = invocation.getArgument(0);
                return Project.builder()
                        .id(1L)
                        .name(p.getName())
                        .slug(p.getSlug())
                        .createdAt(LocalDateTime.now())
                        .updatedAt(LocalDateTime.now())
                        .build();
            });

            // when
            ProjectDTO result = projectService.createProject(dtoWithSpaces);

            // then
            assertThat(result.getSlug()).isEqualTo("my-wiki-project");
        }
    }

    @Nested
    @DisplayName("updateProject")
    class UpdateProjectTests {

        @Test
        @DisplayName("Should update an existing project and return updated DTO")
        void shouldUpdateExistingProject() {
            // given
            when(projectRepository.findById(1L)).thenReturn(Optional.of(existingProject));
            when(projectRepository.save(any(Project.class))).thenAnswer(invocation -> {
                Project p = invocation.getArgument(0);
                // updatedAt is set by @PreUpdate lifecycle callback, not via setter
                return Project.builder()
                        .id(p.getId())
                        .name(p.getName())
                        .description(p.getDescription())
                        .slug(p.getSlug())
                        .createdAt(LocalDateTime.now())
                        .updatedAt(LocalDateTime.now())
                        .build();
            });

            ProjectDTO updateDto = ProjectDTO.builder()
                    .name("Updated Name")
                    .description("Updated description")
                    .build();

            // when
            ProjectDTO result = projectService.updateProject(1L, updateDto);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getName()).isEqualTo("Updated Name");
            assertThat(result.getDescription()).isEqualTo("Updated description");
        }

        @Test
        @DisplayName("Should throw EntityNotFoundException when updating non-existent project")
        void shouldThrowWhenUpdatingNonExistent() {
            // given
            when(projectRepository.findById(99L)).thenReturn(Optional.empty());
            ProjectDTO updateDto = ProjectDTO.builder().name("New Name").build();

            // when & then
            assertThatThrownBy(() -> projectService.updateProject(99L, updateDto))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining("Project not found with id: 99");
        }
    }

    @Nested
    @DisplayName("deleteProject")
    class DeleteProjectTests {

        @Test
        @DisplayName("Should delete an existing project")
        void shouldDeleteExistingProject() {
            // given
            when(projectRepository.existsById(1L)).thenReturn(true);

            // when
            projectService.deleteProject(1L);

            // then
            verify(projectRepository, times(1)).deleteById(1L);
        }

        @Test
        @DisplayName("Should throw EntityNotFoundException when deleting non-existent project")
        void shouldThrowWhenDeletingNonExistent() {
            // given
            when(projectRepository.existsById(99L)).thenReturn(false);

            // when & then
            assertThatThrownBy(() -> projectService.deleteProject(99L))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining("Project not found with id: 99");

            verify(projectRepository, never()).deleteById(any());
        }
    }

    @Nested
    @DisplayName("convertToDTO")
    class ConvertToDtoTests {

        @Test
        @DisplayName("Should correctly convert Project entity to DTO")
        void shouldConvertEntityToDto() {
            // given
            when(projectRepository.findById(1L)).thenReturn(Optional.of(existingProject));

            // when
            ProjectDTO result = projectService.getProjectById(1L);

            // then
            assertThat(result.getId()).isEqualTo(1L);
            assertThat(result.getName()).isEqualTo("Existing Project");
            assertThat(result.getDescription()).isEqualTo("Existing description");
            assertThat(result.getSlug()).isEqualTo("existing-project");
            assertThat(result.getCreatedAt()).isEqualTo(LocalDateTime.of(2024, 1, 1, 0, 0));
            assertThat(result.getUpdatedAt()).isEqualTo(LocalDateTime.of(2024, 1, 1, 0, 0));
        }

        @Test
        @DisplayName("Should convert null description to DTO without error")
        void shouldConvertNullDescription() {
            // given
            Project projectWithoutDesc = Project.builder()
                    .id(2L)
                    .name("No Description")
                    .slug("no-description")
                    .description(null)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            when(projectRepository.findById(2L)).thenReturn(Optional.of(projectWithoutDesc));

            // when
            ProjectDTO result = projectService.getProjectById(2L);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getDescription()).isNull();
        }
    }

    @Nested
    @DisplayName("exportProjectAsZip")
    class ExportProjectAsZipTests {

        @Test
        @DisplayName("Should create ZIP with all documents when project has documents")
        void shouldCreateZipWithDocuments() throws Exception {
            // given
            Project project = existingProject;
            Document doc1 = Document.builder()
                    .id(1L)
                    .title("First Doc")
                    .slug("first-doc")
                    .content("# First Document\n\nSome content here.")
                    .project(project)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            Document doc2 = Document.builder()
                    .id(2L)
                    .title("Second Doc")
                    .slug("second-doc")
                    .content("# Second Document\n\nMore content.")
                    .project(project)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();

            when(projectRepository.findBySlug("existing-project")).thenReturn(Optional.of(project));
            when(documentRepository.findByProjectId(1L)).thenReturn(List.of(doc1, doc2));

            // when
            byte[] zipData = projectService.exportProjectAsZip("existing-project");

            // then
            assertThat(zipData).isNotEmpty();

            // Verify ZIP contents
            try (ZipInputStream zis = new ZipInputStream(new java.io.ByteArrayInputStream(zipData))) {
                ZipEntry entry;
                int docCount = 0;
                while ((entry = zis.getNextEntry()) != null) {
                    docCount++;
                    if ("first-doc.md".equals(entry.getName())) {
                        String content = new String(zis.readAllBytes(), StandardCharsets.UTF_8);
                        assertThat(content).contains("First Document");
                    } else if ("second-doc.md".equals(entry.getName())) {
                        String content = new String(zis.readAllBytes(), StandardCharsets.UTF_8);
                        assertThat(content).contains("Second Document");
                    }
                    zis.closeEntry();
                }
                assertThat(docCount).isEqualTo(2);
            }
        }

        @Test
        @DisplayName("Should create empty ZIP when project has no documents")
        void shouldCreateEmptyZipForProjectWithoutDocuments() throws Exception {
            // given
            Project project = existingProject;
            when(projectRepository.findBySlug("existing-project")).thenReturn(Optional.of(project));
            when(documentRepository.findByProjectId(1L)).thenReturn(Collections.emptyList());

            // when
            byte[] zipData = projectService.exportProjectAsZip("existing-project");

            // then
            assertThat(zipData).isNotEmpty(); // ZIP file header is always present

            // Verify empty ZIP (no entries)
            try (ZipInputStream zis = new ZipInputStream(new java.io.ByteArrayInputStream(zipData))) {
                ZipEntry entry = zis.getNextEntry();
                assertThat(entry).isNull(); // No entries in the ZIP
            }
        }

        @Test
        @DisplayName("Should throw EntityNotFoundException when project not found")
        void shouldThrowWhenProjectNotFound() {
            // given
            when(projectRepository.findBySlug("non-existent")).thenReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> projectService.exportProjectAsZip("non-existent"))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining("Project not found with slug: non-existent");

            verify(documentRepository, never()).findByProjectId(any());
        }

        @Test
        @DisplayName("Should handle document with null content gracefully")
        void shouldHandleNullContentDocument() throws Exception {
            // given
            Project project = existingProject;
            Document docWithNullContent = Document.builder()
                    .id(1L)
                    .title("Empty Doc")
                    .slug("empty-doc")
                    .content(null)
                    .project(project)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();

            when(projectRepository.findBySlug("existing-project")).thenReturn(Optional.of(project));
            when(documentRepository.findByProjectId(1L)).thenReturn(List.of(docWithNullContent));

            // when
            byte[] zipData = projectService.exportProjectAsZip("existing-project");

            // then
            assertThat(zipData).isNotEmpty();

            try (ZipInputStream zis = new ZipInputStream(new java.io.ByteArrayInputStream(zipData))) {
                ZipEntry entry = zis.getNextEntry();
                assertThat(entry).isNotNull();
                assertThat(entry.getName()).isEqualTo("empty-doc.md");
                String content = new String(zis.readAllBytes(), StandardCharsets.UTF_8);
                assertThat(content).isEmpty(); // null content becomes empty string
            }
        }
    }
}
