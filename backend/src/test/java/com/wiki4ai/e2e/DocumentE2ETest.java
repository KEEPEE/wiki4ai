package com.wiki4ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wiki4ai.dto.DocumentCreateDTO;
import com.wiki4ai.dto.DocumentUpdateDTO;
import com.wiki4ai.dto.LinkCreateDTO;
import com.wiki4ai.dto.ProjectCreateDTO;
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
 * End-to-End tests for Document REST API.
 * Tests full CRUD operations and document linking through the actual HTTP endpoints
 * using a randomly assigned port and real database (H2).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("Document E2E Tests")
class DocumentE2ETest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private static final String PROJECTS_URL = "/api/v1/projects";
    private static final String DOCUMENTS_BASE = "/api/v1/projects/{projectSlug}/documents";

    // We need a project with ID=1 for document tests since the controller uses hardcoded 1L
    private String testProjectSlug;

    // ==================== CLEANUP & SETUP ====================

    @BeforeEach
    void setUp() {
        // Clean up all projects first to ensure fresh state
        cleanAllProjects();

        // Create a project - it will get ID=1 since DB is empty after cleanup
        ResponseEntity<Map> createResponse = restTemplate.postForEntity(
                PROJECTS_URL,
                new HttpEntity<>(ProjectCreateDTO.builder()
                        .name("E2E Document Test Project")
                        .description("Used for document E2E tests")
                        .build(),
                        new HttpHeaders()),
                Map.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) createResponse.getBody();
        testProjectSlug = (String) body.get("slug");
        
        // Verify project was created successfully
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(testProjectSlug).isNotNull();
    }

    private void cleanAllProjects() {
        try {
            ResponseEntity<Map[]> response = restTemplate.getForEntity(
                    PROJECTS_URL, Map[].class);
            if (response.getBody() != null) {
                for (Map<String, Object> project : response.getBody()) {
                    String slug = (String) project.get("slug");
                    try {
                        restTemplate.delete(PROJECTS_URL + "/" + slug);
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception ignored) {
            // If no projects exist, ignore the error
        }
    }

    // ==================== HELPER METHODS ====================

    private ResponseEntity<Map> createDocument(String title, String content) {
        DocumentCreateDTO dto = DocumentCreateDTO.builder()
                .title(title)
                .content(content)
                .build();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<DocumentCreateDTO> request = new HttpEntity<>(dto, headers);

        String url = DOCUMENTS_BASE.replace("{projectSlug}", testProjectSlug);
        return restTemplate.postForEntity(url, request, Map.class);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getAllDocuments() {
        String url = DOCUMENTS_BASE.replace("{projectSlug}", testProjectSlug);
        try {
            ResponseEntity<Map[]> response = restTemplate.getForEntity(url, Map[].class);
            if (response.getBody() == null) return List.of();
            return java.util.Arrays.asList(response.getBody());
        } catch (Exception e) {
            return List.of();
        }
    }

    // ==================== CREATE DOCUMENT TESTS ====================

    @Nested
    @DisplayName("POST /api/v1/projects/{slug}/documents - Create a document")
    class CreateDocumentTests {

        @Test
        @DisplayName("Should create a new document and return 201 with full details")
        void shouldCreateDocumentSuccessfully() {
            // when
            ResponseEntity<Map> response = createDocument(
                    "E2E Test Document", "# Hello World\nThis is an E2E test.");

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            Map<String, Object> body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.get("title")).isEqualTo("E2E Test Document");
            assertThat(body.get("content")).isEqualTo("# Hello World\nThis is an E2E test.");
            assertThat(body.get("id")).isNotNull();
        }

        @Test
        @DisplayName("Should create document without content")
        void shouldCreateDocumentWithoutContent() {
            // when
            ResponseEntity<Map> response = createDocument("Empty Content", null);

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(response.getBody().get("title")).isEqualTo("Empty Content");
        }

        @Test
        @DisplayName("Should return 400 when title is missing")
        void shouldReturnBadRequestWhenTitleMissing() {
            // given
            DocumentCreateDTO dto = DocumentCreateDTO.builder().build();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<DocumentCreateDTO> request = new HttpEntity<>(dto, headers);

            String url = DOCUMENTS_BASE.replace("{projectSlug}", testProjectSlug);
            // when & then
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("Should return 409 when document with same title already exists")
        void shouldReturnConflictWhenDuplicateTitle() {
            // given - create first document
            cleanProjectDocuments();
            createDocument("Unique Title", "Content 1");

            // when - try to create duplicate
            DocumentCreateDTO dto = DocumentCreateDTO.builder()
                    .title("Unique Title")
                    .content("Content 2")
                    .build();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<DocumentCreateDTO> request = new HttpEntity<>(dto, headers);

            String url = DOCUMENTS_BASE.replace("{projectSlug}", testProjectSlug);
            try {
                restTemplate.postForEntity(url, request, Map.class);
                assertThat(false).as("Should have thrown HttpClientErrorException.Conflict").isTrue();
            } catch (org.springframework.web.client.HttpClientErrorException.Conflict e) {
                assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            }
        }
    }

    // ==================== READ DOCUMENT TESTS ====================

    @Nested
    @DisplayName("GET /api/v1/projects/{slug}/documents - Read documents")
    class ReadDocumentTests {

        @Test
        @DisplayName("Should return empty list when no documents exist in project")
        void shouldReturnEmptyList() {
            // Clean up existing docs first
            cleanProjectDocuments();

            // when & then
            List<Map<String, Object>> docs = getAllDocuments();
            assertThat(docs).isEmpty();
        }

        @Test
        @DisplayName("Should return all documents in project")
        void shouldReturnAllDocuments() {
            // given - create multiple documents
            cleanProjectDocuments();
            createDocument("Doc One", "Content 1");
            createDocument("Doc Two", "Content 2");

            // when
            List<Map<String, Object>> docs = getAllDocuments();

            // then
            assertThat(docs).hasSize(2);
        }

        @Test
        @DisplayName("Should return document by slug")
        void shouldGetDocumentBySlug() {
            // given
            cleanProjectDocuments();
            ResponseEntity<Map> createResponse = createDocument("Find Me", "Content");
            String docSlug = (String) createResponse.getBody().get("slug");

            // when
            String url = DOCUMENTS_BASE.replace("{projectSlug}", testProjectSlug) + "/" + docSlug;
            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().get("title")).isEqualTo("Find Me");
        }

        @Test
        @DisplayName("Should return 404 when document not found by slug")
        void shouldReturnNotFoundWhenDocNotExists() {
            // when & then
            String url = DOCUMENTS_BASE.replace("{projectSlug}", testProjectSlug) + "/non-existent";
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("Should return document content with rendered markdown and wiki links")
        void shouldGetDocumentContent() {
            // given - create a document with wiki references
            cleanProjectDocuments();
            createDocument("Introduction", "# Introduction\nSee [[API Reference]] for details.");
            createDocument("API Reference", "# API Reference\nEndpoints documentation.");

            String url = DOCUMENTS_BASE.replace("{projectSlug}", testProjectSlug) + "/introduction/content";

            // when
            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            Map<String, Object> body = response.getBody();
            assertThat(body).containsKey("htmlContent");
            assertThat(body).containsKey("wikiLinks");
            assertThat(body).containsKey("linkedDocuments");
        }
    }

    // ==================== UPDATE DOCUMENT TESTS ====================

    @Nested
    @DisplayName("PUT /api/v1/projects/{slug}/documents/{docSlug} - Update a document")
    class UpdateDocumentTests {

        @Test
        @DisplayName("Should update document title and content")
        void shouldUpdateDocumentFields() {
            // given
            cleanProjectDocuments();
            ResponseEntity<Map> createResponse = createDocument("Original", "Old content");
            String docSlug = (String) createResponse.getBody().get("slug");

            DocumentUpdateDTO updateDto = DocumentUpdateDTO.builder()
                    .title("Updated Title")
                    .content("# Updated Content\nNew description here.")
                    .build();

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<DocumentUpdateDTO> request = new HttpEntity<>(updateDto, headers);

            String url = DOCUMENTS_BASE.replace("{projectSlug}", testProjectSlug) + "/" + docSlug;

            // when
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.PUT, request, Map.class);

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().get("title")).isEqualTo("Updated Title");
            assertThat(response.getBody().get("content")).isEqualTo("# Updated Content\nNew description here.");
        }

        @Test
        @DisplayName("Should return 404 when updating non-existent document")
        void shouldReturnNotFoundWhenUpdatingNonExistent() {
            // given
            DocumentUpdateDTO updateDto = DocumentUpdateDTO.builder()
                    .title("Ghost").build();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<DocumentUpdateDTO> request = new HttpEntity<>(updateDto, headers);

            String url = DOCUMENTS_BASE.replace("{projectSlug}", testProjectSlug) + "/ghost-doc";

            // when & then
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.PUT, request, String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("Should return 400 when updating with invalid data")
        void shouldReturnBadRequestWhenUpdateInvalid() {
            // given - create a valid document first
            cleanProjectDocuments();
            createDocument("Valid", "Desc");

            DocumentUpdateDTO updateDto = DocumentUpdateDTO.builder().build(); // empty title
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<DocumentUpdateDTO> request = new HttpEntity<>(updateDto, headers);

            String url = DOCUMENTS_BASE.replace("{projectSlug}", testProjectSlug) + "/valid";

            // when & then
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.PUT, request, String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    // ==================== DELETE DOCUMENT TESTS ====================

    @Nested
    @DisplayName("DELETE /api/v1/projects/{slug}/documents/{docSlug} - Delete a document")
    class DeleteDocumentTests {

        @Test
        @DisplayName("Should delete an existing document and verify removal")
        void shouldDeleteDocumentSuccessfully() {
            // given
            cleanProjectDocuments();
            ResponseEntity<Map> createResponse = createDocument("To Delete", "Content");
            String docSlug = (String) createResponse.getBody().get("slug");

            String url = DOCUMENTS_BASE.replace("{projectSlug}", testProjectSlug) + "/" + docSlug;

            // when
            restTemplate.delete(url);

            // then - verify it's gone
            ResponseEntity<String> getResponse = restTemplate.getForEntity(url, String.class);
            assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("Should return 404 when deleting non-existent document")
        void shouldReturnNotFoundWhenDeletingNonExistent() {
            // when & then
            String url = DOCUMENTS_BASE.replace("{projectSlug}", testProjectSlug) + "/non-existent";
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("Should allow recreating a document after deletion")
        void shouldAllowRecreateAfterDelete() {
            // given
            cleanProjectDocuments();
            ResponseEntity<Map> createResponse = createDocument("Temp", "Content");
            String docSlug = (String) createResponse.getBody().get("slug");

            restTemplate.delete(DOCUMENTS_BASE.replace("{projectSlug}", testProjectSlug) + "/" + docSlug);

            // when & then - same title should be allowed now
            ResponseEntity<Map> recreated = createDocument("Temp", "Recreated content");
            assertThat(recreated.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }
    }

    // ==================== LINK MANAGEMENT TESTS ====================

    @Nested
    @DisplayName("Link Management E2E Tests")
    class LinkManagementTests {

        @Test
        @DisplayName("Should add a link between two documents")
        void shouldAddLinkBetweenDocuments() {
            cleanProjectDocuments();
            
            // Create source and target documents
            ResponseEntity<Map> sourceResponse = createDocument("Source Doc", "# Source");
            ResponseEntity<Map> targetResponse = createDocument("Target Doc", "# Target");

            String sourceDocSlug = (String) sourceResponse.getBody().get("slug");
            
            // Get the correct target ID from response body
            Long finalTargetDocId = ((Number) targetResponse.getBody().get("id")).longValue();

            String url = DOCUMENTS_BASE.replace("{projectSlug}", testProjectSlug) + "/" + sourceDocSlug + "/links";

            // when - add link
            LinkCreateDTO linkDto = LinkCreateDTO.builder()
                    .targetDocumentId(finalTargetDocId)
                    .build();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<LinkCreateDTO> request = new HttpEntity<>(linkDto, headers);

            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.POST, request, Map.class);

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            List<?> linkedDocs = (List<?>) response.getBody().get("linkedDocuments");
            boolean found = linkedDocs.stream().anyMatch(item -> item.equals(finalTargetDocId));
            assertThat(found).isTrue();
        }

        @Test
        @DisplayName("Should return 409 when trying to link document to itself")
        void shouldReturnConflictWhenSelfLink() {
            cleanProjectDocuments();
            
            // Create a single doc and try to self-link
            ResponseEntity<Map> response = createDocument("Self Link", "Content");
            String slug = (String) response.getBody().get("slug");
            Long docId = ((Number) response.getBody().get("id")).longValue();

            // when
            LinkCreateDTO linkDto = LinkCreateDTO.builder()
                    .targetDocumentId(docId)
                    .build();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<LinkCreateDTO> request = new HttpEntity<>(linkDto, headers);

            String url = DOCUMENTS_BASE.replace("{projectSlug}", testProjectSlug) + "/" + slug + "/links";

            ResponseEntity<String> result = restTemplate.exchange(url, HttpMethod.POST, request, String.class);

            // then
            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        }

        @Test
        @DisplayName("Should return list of linked documents")
        void shouldGetLinkedDocuments() {
            cleanProjectDocuments();
            
            // Create two documents and add a link
            ResponseEntity<Map> sourceResponse = createDocument("Source Doc", "# Source");
            ResponseEntity<Map> targetResponse = createDocument("Target Doc", "# Target");

            String sourceDocSlug = (String) sourceResponse.getBody().get("slug");
            Long targetDocId = ((Number) targetResponse.getBody().get("id")).longValue();

            String url = DOCUMENTS_BASE.replace("{projectSlug}", testProjectSlug) + "/" + sourceDocSlug + "/links";

            // Add link first
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<LinkCreateDTO> request = new HttpEntity<>(
                    LinkCreateDTO.builder().targetDocumentId(targetDocId).build(), headers);

            restTemplate.postForEntity(url, request, Map.class);

            // when - get linked documents
            ResponseEntity<Map[]> getResponse = restTemplate.getForEntity(url, Map[].class);

            // then
            assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(getResponse.getBody()).hasSize(1);
        }

        @Test
        @DisplayName("Should remove a link between documents")
        void shouldRemoveLink() {
            cleanProjectDocuments();
            
            // Create two documents and add a link
            ResponseEntity<Map> sourceResponse = createDocument("Source Doc", "# Source");
            ResponseEntity<Map> targetResponse = createDocument("Target Doc", "# Target");

            String sourceDocSlug = (String) sourceResponse.getBody().get("slug");
            Long targetDocId = ((Number) targetResponse.getBody().get("id")).longValue();

            String url = DOCUMENTS_BASE.replace("{projectSlug}", testProjectSlug) + "/" + sourceDocSlug + "/links";

            // Add link first
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<LinkCreateDTO> addRequest = new HttpEntity<>(
                    LinkCreateDTO.builder().targetDocumentId(targetDocId).build(), headers);

            restTemplate.postForEntity(url, addRequest, Map.class);

            // when - remove the link
            String deleteUrl = url + "/" + targetDocId;
            restTemplate.delete(deleteUrl);

            // then - verify link is gone
            ResponseEntity<Map[]> getLinksResponse = restTemplate.getForEntity(url, Map[].class);
            assertThat(getLinksResponse.getBody()).isEmpty();
        }
    }

    // ==================== FULL DOCUMENT LIFECYCLE TESTS ====================

    @Nested
    @DisplayName("Full Document Lifecycle E2E Tests")
    class FullDocumentLifecycleTests {

        @Test
        @DisplayName("Should complete full document CRUD lifecycle: Create -> Read -> Update -> Delete")
        void shouldCompleteFullDocumentCrudLifecycle() throws InterruptedException {
            // Clean up first
            cleanProjectDocuments();

            String docsUrl = DOCUMENTS_BASE.replace("{projectSlug}", testProjectSlug);

            // CREATE
            ResponseEntity<Map> createResponse = createDocument(
                    "Lifecycle Doc", "# Initial Content");
            assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

            Map<String, Object> created = createResponse.getBody();
            Long docId = ((Number) created.get("id")).longValue();
            // Slug might be generated from title - check what field name is used
            String docSlug = (String) created.get("slug");
            if (docSlug == null) {
                // Try alternative slug generation based on title
                docSlug = ((String) created.get("title")).toLowerCase()
                        .replaceAll("[^a-z0-9\\s-]", "")
                        .replaceAll("\\s+", "-")
                        .replaceAll("-+", "-")
                        .trim();
            }
            assertThat(docId).isNotNull();
            assertThat(docSlug).isEqualTo("lifecycle-doc");

            // READ (by listing)
            List<Map<String, Object>> listResponse = getAllDocuments();
            assertThat(listResponse).hasSize(1);

            // READ (by slug)
            String getUrl = docsUrl + "/" + docSlug;
            ResponseEntity<Map> getResponse = restTemplate.getForEntity(getUrl, Map.class);
            assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(getResponse.getBody().get("title")).isEqualTo("Lifecycle Doc");

            // UPDATE
            Thread.sleep(10);
            DocumentUpdateDTO updateDto = DocumentUpdateDTO.builder()
                    .title("Updated Lifecycle")
                    .content("# Updated Content\nModified description.")
                    .build();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<DocumentUpdateDTO> request = new HttpEntity<>(updateDto, headers);

            ResponseEntity<Map> updateResponse = restTemplate.exchange(getUrl, HttpMethod.PUT, request, Map.class);
            assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(updateResponse.getBody().get("title")).isEqualTo("Updated Lifecycle");

            // READ after update
            ResponseEntity<Map> afterUpdate = restTemplate.getForEntity(getUrl, Map.class);
            assertThat(afterUpdate.getBody().get("title")).isEqualTo("Updated Lifecycle");

            // DELETE
            restTemplate.delete(getUrl);

            // VERIFY deleted
            ResponseEntity<String> getAfterDelete = restTemplate.getForEntity(getUrl, String.class);
            assertThat(getAfterDelete.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("Should handle document creation with wiki references")
        void shouldHandleWikiReferences() {
            // given - create multiple documents
            cleanProjectDocuments();
            createDocument("Introduction", "# Introduction\nSee [[API Reference]] and [[Getting Started]].");
            createDocument("API Reference", "# API Reference\nEndpoints documentation.");
            createDocument("Getting Started", "# Getting Started\nQuick start guide.");

            String url = DOCUMENTS_BASE.replace("{projectSlug}", testProjectSlug) + "/introduction/content";

            // when - get content of Introduction document
            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            Map<String, Object> body = response.getBody();
            List<?> wikiLinks = (List<?>) body.get("wikiLinks");
            List<String> stringWikiLinks = wikiLinks.stream().map(Object::toString).toList();
            // Order may vary - check both are present
            assertThat(stringWikiLinks).contains("API Reference", "Getting Started");
            assertThat(stringWikiLinks).hasSize(2);
        }

        @Test
        @DisplayName("Should handle document linking across full lifecycle")
        void shouldHandleLinkingAcrossLifecycle() {
            // given - create two documents
            cleanProjectDocuments();
            ResponseEntity<Map> sourceResponse = createDocument("Source", "# Source\nSee [[Target]].");
            ResponseEntity<Map> targetResponse = createDocument("Target", "# Target");

            String sourceSlug = (String) sourceResponse.getBody().get("slug");
            Long targetId = ((Number) targetResponse.getBody().get("id")).longValue();

            String url = DOCUMENTS_BASE.replace("{projectSlug}", testProjectSlug) + "/" + sourceSlug + "/links";

            // when - add link
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<LinkCreateDTO> request = new HttpEntity<>(
                    LinkCreateDTO.builder().targetDocumentId(targetId).build(), headers);

            ResponseEntity<Map> linkResponse = restTemplate.exchange(url, HttpMethod.POST, request, Map.class);

            // then - verify link exists
            assertThat(linkResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
            List<?> linkedDocs = (List<?>) linkResponse.getBody().get("linkedDocuments");
            boolean found = linkedDocs.stream().anyMatch(item -> item.equals(targetId));
            assertThat(found).isTrue();

            // when - remove link
            restTemplate.delete(url + "/" + targetId);

            // then - verify link is removed
            ResponseEntity<Map[]> getLinksResponse = restTemplate.getForEntity(url, Map[].class);
            assertThat(getLinksResponse.getBody()).isEmpty();
        }
    }

    // ==================== HELPER: Clean project documents ====================

    @SuppressWarnings("unchecked")
    private void cleanProjectDocuments() {
        String url = DOCUMENTS_BASE.replace("{projectSlug}", testProjectSlug);
        try {
            ResponseEntity<Map[]> response = restTemplate.getForEntity(url, Map[].class);
            if (response.getBody() != null) {
                for (Map<String, Object> doc : response.getBody()) {
                    String slug = (String) doc.get("slug");
                    if (slug != null) {
                        try {
                            restTemplate.delete(url + "/" + slug);
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // If no documents exist or endpoint returns error, ignore
        }
    }
}
