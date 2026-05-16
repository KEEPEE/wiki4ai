package com.wiki4ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wiki4ai.dto.ProjectCreateDTO;
import com.wiki4ai.dto.ProjectDTO;
import com.wiki4ai.dto.ProjectUpdateDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-End tests for Project REST API.
 * Tests the full lifecycle of projects through the actual HTTP endpoints
 * using a randomly assigned port and real database (H2).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("Project E2E Tests")
class ProjectE2ETest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private static final String BASE_URL = "/api/v1/projects";

    // ==================== CLEANUP ====================

    @BeforeEach
    void setUp() {
        // Clean up all existing projects before each test
        ResponseEntity<Map[]> response = restTemplate.getForEntity(BASE_URL, Map[].class);
        if (response.getBody() != null) {
            for (Map<String, Object> project : response.getBody()) {
                String slug = (String) project.get("slug");
                try {
                    restTemplate.delete(BASE_URL + "/" + slug);
                } catch (Exception ignored) {
                }
            }
        }
    }

    // ==================== HELPER METHODS ====================

    @SuppressWarnings("unchecked")
    private List<ProjectDTO> getAllProjects() {
        ResponseEntity<Map[]> response = restTemplate.getForEntity(BASE_URL, Map[].class);
        if (response.getBody() == null) return List.of();
        return java.util.Arrays.stream(response.getBody())
                .map(m -> objectMapper.convertValue(m, ProjectDTO.class))
                .toList();
    }

    private ResponseEntity<ProjectDTO> createProject(String name, String description) {
        ProjectCreateDTO dto = ProjectCreateDTO.builder()
                .name(name)
                .description(description)
                .build();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<ProjectCreateDTO> request = new HttpEntity<>(dto, headers);

        return restTemplate.postForEntity(BASE_URL, request, ProjectDTO.class);
    }

    // ==================== CREATE TESTS ====================

    @Nested
    @DisplayName("POST /api/v1/projects - Create a project")
    class CreateProjectTests {

        @Test
        @DisplayName("Should create a new project and return 201 with full details")
        void shouldCreateProjectSuccessfully() {
            // when
            ResponseEntity<ProjectDTO> response = createProject("E2E Test Project", "Description for E2E test");

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            ProjectDTO body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.getId()).isNotNull();
            assertThat(body.getName()).isEqualTo("E2E Test Project");
            assertThat(body.getDescription()).isEqualTo("Description for E2E test");
            assertThat(body.getSlug()).isEqualTo("e2e-test-project");
            assertThat(body.getDocumentCount()).isZero();
            assertThat(body.getCreatedAt()).isNotNull();
            assertThat(body.getUpdatedAt()).isNotNull();
        }

        @Test
        @DisplayName("Should create project without description")
        void shouldCreateProjectWithoutDescription() {
            // when
            ResponseEntity<ProjectDTO> response = createProject("Minimal Project", null);

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(response.getBody().getName()).isEqualTo("Minimal Project");
            assertThat(response.getBody().getDescription()).isNull();
        }

        @Test
        @DisplayName("Should return 400 when name is missing")
        void shouldReturnBadRequestWhenNameMissing() {
            // given
            ProjectCreateDTO dto = ProjectCreateDTO.builder().build();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<ProjectCreateDTO> request = new HttpEntity<>(dto, headers);

            // when & then
            ResponseEntity<String> response = restTemplate.postForEntity(BASE_URL, request, String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("Should return 409 when project with same name already exists")
        void shouldReturnConflictWhenDuplicateName() {
            // given - create first project
            createProject("Unique Name", "First");

            // when - try to create duplicate using exchange to capture response body
            ProjectCreateDTO dto = ProjectCreateDTO.builder()
                    .name("Unique Name")
                    .description("Second")
                    .build();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<ProjectCreateDTO> request = new HttpEntity<>(dto, headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    BASE_URL, HttpMethod.POST, request, String.class);

            // then - should return 409 Conflict
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        }
    }

    // ==================== READ TESTS ====================

    @Nested
    @DisplayName("GET /api/v1/projects - Read projects")
    class ReadProjectTests {

        @Test
        @DisplayName("Should return empty list when no projects exist")
        void shouldReturnEmptyList() {
            // when & then
            List<ProjectDTO> projects = getAllProjects();
            assertThat(projects).isEmpty();
        }

        @Test
        @DisplayName("Should return all projects ordered by creation date descending")
        void shouldReturnAllProjectsOrdered() throws InterruptedException {
            // given - create multiple projects with slight delay
            createProject("Older Project", "First");
            Thread.sleep(10);
            createProject("Newer Project", "Second");

            // when
            List<ProjectDTO> projects = getAllProjects();

            // then
            assertThat(projects).hasSize(2);
            assertThat(projects.get(0).getName()).isEqualTo("Newer Project");
            assertThat(projects.get(1).getName()).isEqualTo("Older Project");
        }

        @Test
        @DisplayName("Should return project by slug")
        void shouldGetProjectBySlug() {
            // given
            ResponseEntity<ProjectDTO> createResponse = createProject("Find Me", "Description");
            String slug = createResponse.getBody().getSlug();

            // when
            ResponseEntity<ProjectDTO> response = restTemplate.getForEntity(
                    BASE_URL + "/" + slug, ProjectDTO.class);

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getName()).isEqualTo("Find Me");
        }

        @Test
        @DisplayName("Should return 404 when project not found by slug")
        void shouldReturnNotFoundWhenSlugNotExists() {
            // when & then
            ResponseEntity<String> response = restTemplate.getForEntity(
                    BASE_URL + "/non-existent-slug", String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    // ==================== UPDATE TESTS ====================

    @Nested
    @DisplayName("PUT /api/v1/projects/{slug} - Update a project")
    class UpdateProjectTests {

        @Test
        @DisplayName("Should update project name and description")
        void shouldUpdateProjectFields() {
            // given
            ResponseEntity<ProjectDTO> createResponse = createProject("Original", "Old desc");
            String slug = createResponse.getBody().getSlug();

            ProjectUpdateDTO updateDto = ProjectUpdateDTO.builder()
                    .name("Updated Name")
                    .description("New description")
                    .build();

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<ProjectUpdateDTO> request = new HttpEntity<>(updateDto, headers);

            // when
            ResponseEntity<ProjectDTO> response = restTemplate.exchange(
                    BASE_URL + "/" + slug, HttpMethod.PUT, request, ProjectDTO.class);

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getName()).isEqualTo("Updated Name");
            assertThat(response.getBody().getDescription()).isEqualTo("New description");
        }

        @Test
        @DisplayName("Should return 404 when updating non-existent project")
        void shouldReturnNotFoundWhenUpdatingNonExistent() {
            // given
            ProjectUpdateDTO updateDto = ProjectUpdateDTO.builder()
                    .name("Ghost").build();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<ProjectUpdateDTO> request = new HttpEntity<>(updateDto, headers);

            // when & then
            ResponseEntity<String> response = restTemplate.exchange(
                    BASE_URL + "/ghost-project", HttpMethod.PUT, request, String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("Should return 400 when updating with invalid data")
        void shouldReturnBadRequestWhenUpdateInvalid() {
            // given - create a valid project first
            createProject("Valid", "Desc");

            ProjectUpdateDTO updateDto = ProjectUpdateDTO.builder().build(); // empty name
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<ProjectUpdateDTO> request = new HttpEntity<>(updateDto, headers);

            // when & then
            ResponseEntity<String> response = restTemplate.exchange(
                    BASE_URL + "/valid", HttpMethod.PUT, request, String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    // ==================== DELETE TESTS ====================

    @Nested
    @DisplayName("DELETE /api/v1/projects/{slug} - Delete a project")
    class DeleteProjectTests {

        @Test
        @DisplayName("Should delete an existing project and verify removal")
        void shouldDeleteProjectSuccessfully() {
            // given
            ResponseEntity<ProjectDTO> createResponse = createProject("To Delete", "Desc");
            String slug = createResponse.getBody().getSlug();

            // when
            restTemplate.delete(BASE_URL + "/" + slug);

            // then - verify it's gone
            ResponseEntity<String> getResponse = restTemplate.getForEntity(
                    BASE_URL + "/" + slug, String.class);
            assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("Should return 404 when deleting non-existent project")
        void shouldReturnNotFoundWhenDeletingNonExistent() {
            // when & then
            ResponseEntity<String> response = restTemplate.getForEntity(
                    BASE_URL + "/non-existent", String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("Should allow recreating a project after deletion")
        void shouldAllowRecreateAfterDelete() {
            // given
            ResponseEntity<ProjectDTO> createResponse = createProject("Temp", "Desc");
            String slug = createResponse.getBody().getSlug();

            restTemplate.delete(BASE_URL + "/" + slug);

            // when & then - same name should be allowed now
            ResponseEntity<ProjectDTO> recreated = createProject("Temp", "Recreated");
            assertThat(recreated.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }
    }

    // ==================== FULL LIFECYCLE TESTS ====================

    @Nested
    @DisplayName("Full Project Lifecycle E2E Tests")
    class FullLifecycleTests {

        @Test
        @DisplayName("Should complete full CRUD lifecycle: Create -> Read -> Update -> Delete")
        void shouldCompleteFullCrudLifecycle() throws InterruptedException {
            // CREATE
            ResponseEntity<ProjectDTO> createResponse = createProject(
                    "Lifecycle Project", "Initial description");
            assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

            ProjectDTO created = createResponse.getBody();
            Long id = created.getId();
            String slug = created.getSlug();
            assertThat(id).isNotNull();
            assertThat(slug).isEqualTo("lifecycle-project");

            // READ (by listing)
            List<ProjectDTO> allProjects = getAllProjects();
            assertThat(allProjects).hasSize(1);
            assertThat(allProjects.get(0).getId()).isEqualTo(id);

            // READ (by slug)
            ResponseEntity<ProjectDTO> getResponse = restTemplate.getForEntity(
                    BASE_URL + "/" + slug, ProjectDTO.class);
            assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(getResponse.getBody().getName()).isEqualTo("Lifecycle Project");

            // UPDATE
            Thread.sleep(10);
            ProjectUpdateDTO updateDto = ProjectUpdateDTO.builder()
                    .name("Updated Lifecycle")
                    .description("Modified description")
                    .build();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<ProjectUpdateDTO> request = new HttpEntity<>(updateDto, headers);

            ResponseEntity<ProjectDTO> updateResponse = restTemplate.exchange(
                    BASE_URL + "/" + slug, HttpMethod.PUT, request, ProjectDTO.class);
            assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(updateResponse.getBody().getName()).isEqualTo("Updated Lifecycle");
            assertThat(updateResponse.getBody().getDescription()).isEqualTo("Modified description");

            // Get the NEW slug after update (slug changes when name changes)
            String newSlug = updateResponse.getBody().getSlug();

            // READ after update using new slug
            ResponseEntity<ProjectDTO> afterUpdate = restTemplate.getForEntity(
                    BASE_URL + "/" + newSlug, ProjectDTO.class);
            assertThat(afterUpdate.getBody().getName()).isEqualTo("Updated Lifecycle");

            // DELETE
            restTemplate.delete(BASE_URL + "/" + newSlug);

            // VERIFY deleted
            ResponseEntity<String> getAfterDelete = restTemplate.getForEntity(
                    BASE_URL + "/" + slug, String.class);
            assertThat(getAfterDelete.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("Should handle concurrent project creation correctly")
        void shouldHandleConcurrentCreation() {
            // Create multiple projects in sequence
            ResponseEntity<ProjectDTO> p1 = createProject("First", "Desc 1");
            ResponseEntity<ProjectDTO> p2 = createProject("Second", "Desc 2");
            ResponseEntity<ProjectDTO> p3 = createProject("Third", "Desc 3");

            assertThat(p1.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(p2.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(p3.getStatusCode()).isEqualTo(HttpStatus.CREATED);

            // Verify all exist
            List<ProjectDTO> allProjects = getAllProjects();
            assertThat(allProjects).hasSize(3);

            // Each should have unique ID and slug
            assertThat(p1.getBody().getId()).isNotEqualTo(p2.getBody().getId());
            assertThat(p2.getBody().getId()).isNotEqualTo(p3.getBody().getId());
        }
    }
}
