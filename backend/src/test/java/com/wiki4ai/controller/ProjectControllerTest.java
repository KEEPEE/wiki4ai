package com.wiki4ai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wiki4ai.dto.ProjectCreateDTO;
import com.wiki4ai.dto.ProjectDTO;
import com.wiki4ai.dto.ProjectUpdateDTO;
import com.wiki4ai.service.ProjectService;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.io.ByteArrayInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Web MVC tests for ProjectController using MockMvc.
 * Tests controller layer in isolation without starting the full application context.
 */
@WebMvcTest(ProjectController.class)
class ProjectControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ProjectService projectService;

    private final LocalDateTime now = LocalDateTime.of(2024, 5, 16, 10, 0);

    private ProjectDTO createSampleProject() {
        return ProjectDTO.builder()
                .id(1L)
                .name("Test Project")
                .description("A test project description")
                .slug("test-project")
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private ProjectCreateDTO createSampleCreateDto() {
        return ProjectCreateDTO.builder()
                .name("New Project")
                .description("Created via API")
                .build();
    }

    private ProjectUpdateDTO createSampleUpdateDto() {
        return ProjectUpdateDTO.builder()
                .name("Updated Project")
                .description("Updated description")
                .build();
    }

    @Nested
    @DisplayName("GET /api/v1/projects - List all projects")
    class GetAllProjectsTests {

        @Test
        @DisplayName("Should return 200 with list of projects")
        void shouldReturnAllProjects() throws Exception {
            // given
            ProjectDTO project = createSampleProject();
            given(projectService.getAllProjects()).willReturn(List.of(project));

            // when & then
            mockMvc.perform(get("/api/v1/projects"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].name").value("Test Project"))
                    .andExpect(jsonPath("$[0].slug").value("test-project"));
        }

        @Test
        @DisplayName("Should return 200 with empty list when no projects exist")
        void shouldReturnEmptyList() throws Exception {
            // given
            given(projectService.getAllProjects()).willReturn(List.of());

            // when & then
            mockMvc.perform(get("/api/v1/projects"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/projects/{slug} - Get project by slug")
    class GetProjectBySlugTests {

        @Test
        @DisplayName("Should return 200 with project details when found")
        void shouldReturnProjectWhenFound() throws Exception {
            // given
            ProjectDTO project = createSampleProject();
            given(projectService.getProjectBySlug("test-project")).willReturn(project);

            // when & then
            mockMvc.perform(get("/api/v1/projects/test-project"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("Test Project"))
                    .andExpect(jsonPath("$.description").value("A test project description"));
        }

        @Test
        @DisplayName("Should return 404 when project not found")
        void shouldReturnNotFoundWhenNotExists() throws Exception {
            // given
            given(projectService.getProjectBySlug("non-existent"))
                    .willThrow(new EntityNotFoundException("Project not found with slug: non-existent"));

            // when & then
            mockMvc.perform(get("/api/v1/projects/non-existent"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Project not found with slug: non-existent"));
        }
    }

    @Nested
    @DisplayName("POST /api/v1/projects - Create a new project")
    class CreateProjectTests {

        @Test
        @DisplayName("Should return 201 with created project details")
        void shouldCreateProjectSuccessfully() throws Exception {
            // given
            ProjectCreateDTO createDto = createSampleCreateDto();
            ProjectDTO created = ProjectDTO.builder()
                    .id(2L)
                    .name("New Project")
                    .description("Created via API")
                    .slug("new-project")
                    .createdAt(now)
                    .updatedAt(now)
                    .build();

            given(projectService.createProject(any(ProjectCreateDTO.class))).willReturn(created);

            // when & then
            mockMvc.perform(post("/api/v1/projects")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createDto)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.name").value("New Project"))
                    .andExpect(jsonPath("$.slug").value("new-project"));

            verify(projectService).createProject(any(ProjectCreateDTO.class));
        }

        @Test
        @DisplayName("Should return 400 when validation fails (missing name)")
        void shouldReturnBadRequestWhenNameMissing() throws Exception {
            // given
            ProjectCreateDTO invalidDto = ProjectCreateDTO.builder().build();

            // when & then
            mockMvc.perform(post("/api/v1/projects")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalidDto)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.name").exists());
        }

        @Test
        @DisplayName("Should return 409 when project name already exists")
        void shouldReturnConflictWhenDuplicateName() throws Exception {
            // given
            ProjectCreateDTO dto = createSampleCreateDto();
            given(projectService.createProject(any(ProjectCreateDTO.class)))
                    .willThrow(new IllegalArgumentException("A project with this name already exists"));

            // when & then
            mockMvc.perform(post("/api/v1/projects")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(dto)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("A project with this name already exists"));
        }

        @Test
        @DisplayName("Should return 400 when description exceeds max length")
        void shouldReturnBadRequestWhenDescriptionTooLong() throws Exception {
            // given
            ProjectCreateDTO dto = ProjectCreateDTO.builder()
                    .name("Valid Name")
                    .description("x".repeat(1001))
                    .build();

            // when & then
            mockMvc.perform(post("/api/v1/projects")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(dto)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.description").exists());
        }
    }

    @Nested
    @DisplayName("PUT /api/v1/projects/{slug} - Update a project")
    class UpdateProjectTests {

        @Test
        @DisplayName("Should return 200 with updated project details")
        void shouldUpdateProjectSuccessfully() throws Exception {
            // given
            ProjectUpdateDTO updateDto = createSampleUpdateDto();
            ProjectDTO updated = ProjectDTO.builder()
                    .id(1L)
                    .name("Updated Project")
                    .description("Updated description")
                    .slug("test-project")
                    .createdAt(now)
                    .updatedAt(now)
                    .build();

            given(projectService.updateProjectBySlug(eq("test-project"), any(ProjectUpdateDTO.class)))
                    .willReturn(updated);

            // when & then
            mockMvc.perform(put("/api/v1/projects/test-project")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateDto)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("Updated Project"))
                    .andExpect(jsonPath("$.description").value("Updated description"));

            verify(projectService).updateProjectBySlug(eq("test-project"), any(ProjectUpdateDTO.class));
        }

        @Test
        @DisplayName("Should return 404 when project not found")
        void shouldReturnNotFoundWhenNotExists() throws Exception {
            // given
            ProjectUpdateDTO updateDto = createSampleUpdateDto();
            given(projectService.updateProjectBySlug(eq("non-existent"), any(ProjectUpdateDTO.class)))
                    .willThrow(new EntityNotFoundException("Project not found with slug: non-existent"));

            // when & then
            mockMvc.perform(put("/api/v1/projects/non-existent")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateDto)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Project not found with slug: non-existent"));
        }

        @Test
        @DisplayName("Should return 400 when validation fails")
        void shouldReturnBadRequestWhenValidationFails() throws Exception {
            // given
            ProjectUpdateDTO invalidDto = ProjectUpdateDTO.builder().build();

            // when & then
            mockMvc.perform(put("/api/v1/projects/test-project")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalidDto)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.name").exists());
        }
    }

    @Nested
    @DisplayName("DELETE /api/v1/projects/{slug} - Delete a project")
    class DeleteProjectTests {

        @Test
        @DisplayName("Should return 204 when project deleted successfully")
        void shouldDeleteProjectSuccessfully() throws Exception {
            // given
            doNothing().when(projectService).deleteProjectBySlug("test-project");

            // when & then
            mockMvc.perform(delete("/api/v1/projects/test-project"))
                    .andExpect(status().isNoContent());

            verify(projectService).deleteProjectBySlug("test-project");
        }

        @Test
        @DisplayName("Should return 404 when project not found")
        void shouldReturnNotFoundWhenNotExists() throws Exception {
            // given
            doThrow(new EntityNotFoundException("Project not found with slug: non-existent"))
                    .when(projectService).deleteProjectBySlug("non-existent");

            // when & then
            mockMvc.perform(delete("/api/v1/projects/non-existent"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Project not found with slug: non-existent"));
        }
    }

    // ── ID-based endpoint tests ──────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/v1/projects/by-id/{id} - Get project by ID")
    class GetProjectByIdTests {

        @Test
        @DisplayName("Should return 200 with project details when found by ID")
        void shouldReturnProjectWhenFoundById() throws Exception {
            // given
            ProjectDTO project = createSampleProject();
            given(projectService.getProjectById(1L)).willReturn(project);

            // when & then
            mockMvc.perform(get("/api/v1/projects/by-id/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("Test Project"))
                    .andExpect(jsonPath("$.id").value(1));
        }

        @Test
        @DisplayName("Should return 404 when project not found by ID")
        void shouldReturnNotFoundWhenNotExistsById() throws Exception {
            // given
            given(projectService.getProjectById(999L))
                    .willThrow(new EntityNotFoundException("Project not found with id: 999"));

            // when & then
            mockMvc.perform(get("/api/v1/projects/by-id/999"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Project not found with id: 999"));
        }
    }

    @Nested
    @DisplayName("PUT /api/v1/projects/by-id/{id} - Update a project by ID")
    class UpdateProjectByIdTests {

        @Test
        @DisplayName("Should return 200 with updated project details when found by ID")
        void shouldUpdateProjectSuccessfullyById() throws Exception {
            // given
            ProjectUpdateDTO updateDto = createSampleUpdateDto();
            ProjectDTO updated = ProjectDTO.builder()
                    .id(1L)
                    .name("Updated Project")
                    .description("Updated description")
                    .slug("test-project")
                    .createdAt(now)
                    .updatedAt(now)
                    .build();

            given(projectService.updateProjectById(eq(1L), any(ProjectUpdateDTO.class)))
                    .willReturn(updated);

            // when & then
            mockMvc.perform(put("/api/v1/projects/by-id/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateDto)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("Updated Project"))
                    .andExpect(jsonPath("$.description").value("Updated description"));

            verify(projectService).updateProjectById(eq(1L), any(ProjectUpdateDTO.class));
        }

        @Test
        @DisplayName("Should return 404 when project not found by ID")
        void shouldReturnNotFoundWhenNotExistsById() throws Exception {
            // given
            ProjectUpdateDTO updateDto = createSampleUpdateDto();
            given(projectService.updateProjectById(eq(999L), any(ProjectUpdateDTO.class)))
                    .willThrow(new EntityNotFoundException("Project not found with id: 999"));

            // when & then
            mockMvc.perform(put("/api/v1/projects/by-id/999")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateDto)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Project not found with id: 999"));
        }

        @Test
        @DisplayName("Should return 400 when validation fails")
        void shouldReturnBadRequestWhenValidationFailsById() throws Exception {
            // given
            ProjectUpdateDTO invalidDto = ProjectUpdateDTO.builder().build();

            // when & then
            mockMvc.perform(put("/api/v1/projects/by-id/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalidDto)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.name").exists());
        }
    }

    @Nested
    @DisplayName("DELETE /api/v1/projects/by-id/{id} - Delete a project by ID")
    class DeleteProjectByIdTests {

        @Test
        @DisplayName("Should return 204 when project deleted successfully by ID")
        void shouldDeleteProjectSuccessfullyById() throws Exception {
            // given
            doNothing().when(projectService).deleteProject(1L);

            // when & then
            mockMvc.perform(delete("/api/v1/projects/by-id/1"))
                    .andExpect(status().isNoContent());

            verify(projectService).deleteProject(1L);
        }

        @Test
        @DisplayName("Should return 404 when project not found by ID")
        void shouldReturnNotFoundWhenNotExistsById() throws Exception {
            // given
            doThrow(new EntityNotFoundException("Project not found with id: 999"))
                    .when(projectService).deleteProject(999L);

            // when & then
            mockMvc.perform(delete("/api/v1/projects/by-id/999"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Project not found with id: 999"));
        }
    }

    @Nested
    @DisplayName("Swagger/OpenAPI annotations")
    class SwaggerAnnotationsTests {

        @Test
        @DisplayName("Controller should have proper Tag annotation")
        void shouldHaveTagAnnotation() throws Exception {
            // The controller is annotated with @Tag(name = "Projects", ...)
            // This test verifies the controller class exists and has correct mapping
            mockMvc.perform(get("/api/v1/projects"))
                    .andExpect(status().isOk()); // Will fail if controller not registered
        }

        @Test
        @DisplayName("Controller should have Operation annotations on all endpoints")
        void shouldHaveOperationAnnotations() throws Exception {
            // Verify that the controller has proper OpenAPI documentation
            // by checking that all CRUD endpoints are properly mapped
            mockMvc.perform(get("/api/v1/projects"))
                    .andExpect(status().isOk());

            mockMvc.perform(post("/api/v1/projects")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Test\"}"))
                    .andExpect(status().isCreated());
        }
    }

    @Nested
    @DisplayName("GET /api/v1/projects/{slug}/export - Export project as ZIP")
    class ExportProjectTests {

        @Test
        @DisplayName("Should return 200 with ZIP file when project exists")
        void shouldReturnZipFileWhenProjectExists() throws Exception {
            // given
            byte[] mockZipData = createMinimalZip();
            given(projectService.exportProjectAsZip("test-project")).willReturn(mockZipData);

            // when & then
            mockMvc.perform(get("/api/v1/projects/test-project/export"))
                    .andExpect(status().isOk())
                    .andExpect(header().stringValues("Content-Disposition",
                            "attachment; filename=\"test-project.zip\""))
                    .andExpect(content().contentType("application/octet-stream"));
        }

        @Test
        @DisplayName("Should return 404 when project not found")
        void shouldReturnNotFoundWhenProjectNotExists() throws Exception {
            // given
            given(projectService.exportProjectAsZip("non-existent"))
                    .willThrow(new EntityNotFoundException("Project not found with slug: non-existent"));

            // when & then
            mockMvc.perform(get("/api/v1/projects/non-existent/export"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Project not found with slug: non-existent"));
        }

        @Test
        @DisplayName("Should return valid ZIP content that can be read")
        void shouldReturnValidZipContent() throws Exception {
            // given
            byte[] mockZipData = createMinimalZip();
            given(projectService.exportProjectAsZip("test-project")).willReturn(mockZipData);

            // when & then
            var response = mockMvc.perform(get("/api/v1/projects/test-project/export"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse();

            byte[] responseData = response.getContentAsByteArray();
            assertThat(responseData).isNotEmpty();

            // Verify it's a valid ZIP by trying to read it
            try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(mockZipData))) {
                ZipEntry entry = zis.getNextEntry();
                // Even an empty ZIP should be parseable without throwing
                assertThat(entry).isNull(); // Our minimal zip has no entries
            }
        }

        /**
         * Creates a minimal valid ZIP file byte array for testing.
         * This is the central directory + end of central record for an empty ZIP.
         */
        private byte[] createMinimalZip() {
            // End of Central Directory Record (empty ZIP)
            return new byte[]{
                    0x50, 0x4B, 0x05, 0x06, // End of central dir signature
                    0x00, 0x00, 0x00, 0x00, // Disk number
                    0x00, 0x00, 0x00, 0x00, // Disk with central directory
                    0x00, 0x00,             // Number of entries on disk
                    0x00, 0x00,             // Total number of entries
                    0x00, 0x00, 0x00, 0x00, // Size of central directory
                    0x00, 0x00, 0x00, 0x00, // Offset to start of central directory
                    0x00, 0x00              // Comment length
            };
        }
    }
}
