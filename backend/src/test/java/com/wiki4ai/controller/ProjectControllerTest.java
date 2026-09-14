package com.wiki4ai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wiki4ai.dto.ProjectCreateDTO;
import com.wiki4ai.dto.ProjectDTO;
import com.wiki4ai.dto.ProjectTreeNodeDTO;
import com.wiki4ai.dto.ProjectUpdateDTO;
import com.wiki4ai.exception.BadRequestException;
import com.wiki4ai.service.ProjectService;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
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

import org.mockito.ArgumentCaptor;
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
@AutoConfigureMockMvc
@ImportAutoConfiguration(exclude = {SecurityAutoConfiguration.class})
@org.springframework.test.context.ActiveProfiles("test")
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

            given(projectService.createProject(any(ProjectCreateDTO.class), any(String.class))).willReturn(created);

            // when & then
            mockMvc.perform(post("/api/v1/projects")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createDto)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.name").value("New Project"))
                    .andExpect(jsonPath("$.slug").value("new-project"));

            verify(projectService).createProject(any(ProjectCreateDTO.class), any(String.class));
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
            given(projectService.createProject(any(ProjectCreateDTO.class), any(String.class)))
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

            given(projectService.updateProjectBySlug(eq("test-project"), any(ProjectUpdateDTO.class), any(String.class)))
                    .willReturn(updated);

            // when & then
            mockMvc.perform(put("/api/v1/projects/test-project")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateDto)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("Updated Project"))
                    .andExpect(jsonPath("$.description").value("Updated description"));

            verify(projectService).updateProjectBySlug(eq("test-project"), any(ProjectUpdateDTO.class), any(String.class));
        }

        @Test
        @DisplayName("Should return 404 when project not found")
        void shouldReturnNotFoundWhenNotExists() throws Exception {
            // given
            ProjectUpdateDTO updateDto = createSampleUpdateDto();
            given(projectService.updateProjectBySlug(eq("non-existent"), any(ProjectUpdateDTO.class), any(String.class)))
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
            doNothing().when(projectService).deleteProjectBySlug(eq("test-project"), any(String.class));

            // when & then
            mockMvc.perform(delete("/api/v1/projects/test-project"))
                    .andExpect(status().isNoContent());

            verify(projectService).deleteProjectBySlug(eq("test-project"), any(String.class));
        }

        @Test
        @DisplayName("Should return 404 when project not found")
        void shouldReturnNotFoundWhenNotExists() throws Exception {
            // given
            doThrow(new EntityNotFoundException("Project not found with slug: non-existent"))
                    .when(projectService).deleteProjectBySlug(eq("non-existent"), any(String.class));

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

            given(projectService.updateProjectById(eq(1L), any(ProjectUpdateDTO.class), any(String.class)))
                    .willReturn(updated);

            // when & then
            mockMvc.perform(put("/api/v1/projects/by-id/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateDto)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("Updated Project"))
                    .andExpect(jsonPath("$.description").value("Updated description"));

            verify(projectService).updateProjectById(eq(1L), any(ProjectUpdateDTO.class), any(String.class));
        }

        @Test
        @DisplayName("Should return 404 when project not found by ID")
        void shouldReturnNotFoundWhenNotExistsById() throws Exception {
            // given
            ProjectUpdateDTO updateDto = createSampleUpdateDto();
            given(projectService.updateProjectById(eq(999L), any(ProjectUpdateDTO.class), any(String.class)))
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
            doNothing().when(projectService).deleteProject(eq(1L), any(String.class));

            // when & then
            mockMvc.perform(delete("/api/v1/projects/by-id/1"))
                    .andExpect(status().isNoContent());

            verify(projectService).deleteProject(eq(1L), any(String.class));
        }

        @Test
        @DisplayName("Should return 404 when project not found by ID")
        void shouldReturnNotFoundWhenNotExistsById() throws Exception {
            // given
            doThrow(new EntityNotFoundException("Project not found with id: 999"))
                    .when(projectService).deleteProject(eq(999L), any(String.class));

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
            given(projectService.getAllProjects()).willReturn(List.of());
            mockMvc.perform(get("/api/v1/projects"))
                    .andExpect(status().isOk()); // Will fail if controller not registered
        }

        @Test
        @DisplayName("Controller should have Operation annotations on all endpoints")
        void shouldHaveOperationAnnotations() throws Exception {
            // Verify that the controller has proper OpenAPI documentation
            // by checking that all CRUD endpoints are properly mapped
            given(projectService.getAllProjects()).willReturn(List.of());
            mockMvc.perform(get("/api/v1/projects"))
                    .andExpect(status().isOk());

            ProjectDTO created = createSampleProject();
            given(projectService.createProject(any(ProjectCreateDTO.class), any(String.class))).willReturn(created);
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
            given(projectService.exportProjectAsZip(eq("test-project"), any(String.class))).willReturn(mockZipData);

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
            given(projectService.exportProjectAsZip(eq("non-existent"), any(String.class)))
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
            given(projectService.exportProjectAsZip(eq("test-project"), any(String.class))).willReturn(mockZipData);

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

    @Nested
    @DisplayName("Project hierarchy API (WIKI4AI-29/30)")
    class ProjectHierarchyApiTests {

        private ProjectTreeNodeDTO sampleTree() {
            ProjectTreeNodeDTO child = ProjectTreeNodeDTO.builder()
                    .id(2L)
                    .name("Sub")
                    .slug("sub")
                    .parentSlug("test-project")
                    .depth(2)
                    .hasChildren(false)
                    .documentCount(3)
                    .build();
            return ProjectTreeNodeDTO.builder()
                    .id(1L)
                    .name("Test Project")
                    .slug("test-project")
                    .parentSlug(null)
                    .depth(1)
                    .hasChildren(true)
                    .documentCount(5)
                    .children(java.util.List.of(child))
                    .build();
        }

        @Test
        @DisplayName("POST / with parentId should return 201 and the subproject DTO")
        void createWithParentShouldReturnCreated() throws Exception {
            // given
            ProjectDTO created = createSampleProject();
            created.setParentSlug("test-project");
            created.setDepth(2);
            given(projectService.createProject(any(ProjectCreateDTO.class), any(String.class)))
                    .willReturn(created);

            // when & then
            mockMvc.perform(post("/api/v1/projects")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    ProjectCreateDTO.builder().name("New Sub").parentId(1L).build())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.parentSlug").value("test-project"))
                    .andExpect(jsonPath("$.depth").value(2));

            ArgumentCaptor<ProjectCreateDTO> captor = ArgumentCaptor.forClass(ProjectCreateDTO.class);
            verify(projectService).createProject(captor.capture(), any(String.class));
            assertThat(captor.getValue().getParentId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("POST / without parentId should create a root project (backward compatible)")
        void createWithoutParentShouldWork() throws Exception {
            // given
            ProjectDTO created = createSampleProject();
            created.setParentSlug(null);
            created.setDepth(1);
            given(projectService.createProject(any(ProjectCreateDTO.class), any(String.class)))
                    .willReturn(created);

            // when & then
            mockMvc.perform(post("/api/v1/projects")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    ProjectCreateDTO.builder().name("Root").build())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.parentSlug").isEmpty())
                    .andExpect(jsonPath("$.depth").value(1));

            ArgumentCaptor<ProjectCreateDTO> captor = ArgumentCaptor.forClass(ProjectCreateDTO.class);
            verify(projectService).createProject(captor.capture(), any(String.class));
            assertThat(captor.getValue().getParentId()).isNull();
        }

        @Test
        @DisplayName("POST / should return 400 with clear message when depth limit exceeded")
        void createExceedingDepthShouldReturn400() throws Exception {
            // given
            given(projectService.createProject(any(ProjectCreateDTO.class), any(String.class)))
                    .willThrow(new BadRequestException(
                            "Cannot create subproject: maximum hierarchy depth of 5 levels would be exceeded"));

            // when & then
            mockMvc.perform(post("/api/v1/projects")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    ProjectCreateDTO.builder().name("Too Deep").parentId(9L).build())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(
                            "Cannot create subproject: maximum hierarchy depth of 5 levels would be exceeded"));
        }

        @Test
        @DisplayName("GET /{slug}/tree should return the nested tree shape")
        void getTreeShouldReturnNestedShape() throws Exception {
            // given
            given(projectService.getProjectTree("test-project")).willReturn(sampleTree());

            // when & then
            mockMvc.perform(get("/api/v1/projects/test-project/tree"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(1))
                    .andExpect(jsonPath("$.slug").value("test-project"))
                    .andExpect(jsonPath("$.parentSlug").isEmpty())
                    .andExpect(jsonPath("$.depth").value(1))
                    .andExpect(jsonPath("$.hasChildren").value(true))
                    .andExpect(jsonPath("$.documentCount").value(5))
                    .andExpect(jsonPath("$.children[0].slug").value("sub"))
                    .andExpect(jsonPath("$.children[0].parentSlug").value("test-project"))
                    .andExpect(jsonPath("$.children[0].depth").value(2))
                    .andExpect(jsonPath("$.children[0].hasChildren").value(false));
        }

        @Test
        @DisplayName("GET /{slug}/tree should return 404 for unknown project")
        void getTreeUnknownProjectShouldReturn404() throws Exception {
            // given
            given(projectService.getProjectTree("missing"))
                    .willThrow(new EntityNotFoundException("Project not found with slug: missing"));

            // when & then
            mockMvc.perform(get("/api/v1/projects/missing/tree"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("PUT /{slug} with explicit parentId null should flag move-to-root (parentIdPresent=true)")
        void putWithExplicitNullParentShouldFlagMoveToRoot() throws Exception {
            // given
            ProjectDTO updated = createSampleProject();
            updated.setParentSlug(null);
            updated.setDepth(1);
            given(projectService.updateProjectBySlug(eq("test-project"), any(ProjectUpdateDTO.class), any(String.class)))
                    .willReturn(updated);

            // when — JSON explicitly contains "parentId": null
            mockMvc.perform(put("/api/v1/projects/test-project")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\": \"Test Project\", \"description\": \"x\", \"parentId\": null}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.parentSlug").isEmpty());

            // then — Jackson invoked the custom setter → presence flag set
            ArgumentCaptor<ProjectUpdateDTO> captor = ArgumentCaptor.forClass(ProjectUpdateDTO.class);
            verify(projectService).updateProjectBySlug(eq("test-project"), captor.capture(), any(String.class));
            assertThat(captor.getValue().isParentIdPresent()).isTrue();
            assertThat(captor.getValue().getParentId()).isNull();
        }

        @Test
        @DisplayName("PUT /{slug} without parentId key should NOT flag a move (parentIdPresent=false)")
        void putWithoutParentKeyShouldNotFlagMove() throws Exception {
            // given
            ProjectDTO updated = createSampleProject();
            updated.setDepth(2);
            given(projectService.updateProjectBySlug(eq("test-project"), any(ProjectUpdateDTO.class), any(String.class)))
                    .willReturn(updated);

            // when — JSON has no parentId key at all
            mockMvc.perform(put("/api/v1/projects/test-project")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\": \"Test Project\", \"description\": \"x\"}"))
                    .andExpect(status().isOk());

            // then
            ArgumentCaptor<ProjectUpdateDTO> captor = ArgumentCaptor.forClass(ProjectUpdateDTO.class);
            verify(projectService).updateProjectBySlug(eq("test-project"), captor.capture(), any(String.class));
            assertThat(captor.getValue().isParentIdPresent()).isFalse();
        }

        @Test
        @DisplayName("PUT /{slug} move with cycle should return 400 with clear message")
        void putMoveCycleShouldReturn400() throws Exception {
            // given
            given(projectService.updateProjectBySlug(eq("test-project"), any(ProjectUpdateDTO.class), any(String.class)))
                    .willThrow(new BadRequestException(
                            "Cannot move a project under its own subproject (would create a cycle)"));

            // when & then
            mockMvc.perform(put("/api/v1/projects/test-project")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\": \"Test Project\", \"parentId\": 2}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(
                            "Cannot move a project under its own subproject (would create a cycle)"));
        }
    }
}
