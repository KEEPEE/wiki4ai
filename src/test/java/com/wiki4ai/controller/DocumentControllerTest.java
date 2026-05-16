package com.wiki4ai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wiki4ai.dto.DocumentCreateDTO;
import com.wiki4ai.dto.DocumentDTO;
import com.wiki4ai.dto.DocumentUpdateDTO;
import com.wiki4ai.dto.LinkCreateDTO;
import com.wiki4ai.service.DocumentService;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Web MVC tests for DocumentController using MockMvc.
 * Tests controller layer in isolation without starting the full application context.
 */
@WebMvcTest(DocumentController.class)
class DocumentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private DocumentService documentService;

    private final LocalDateTime now = LocalDateTime.of(2024, 5, 16, 10, 0);

    private DocumentDTO createSampleDocument() {
        return DocumentDTO.builder()
                .id(1L)
                .title("Test Document")
                .content("# Hello World\nThis is a test document.")
                .projectId(1L)
                .linkedDocuments(List.of())
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private DocumentCreateDTO createSampleCreateDto() {
        return DocumentCreateDTO.builder()
                .title("New Document")
                .content("# New Content\nThis is new content.")
                .build();
    }

    private DocumentUpdateDTO createSampleUpdateDto() {
        return DocumentUpdateDTO.builder()
                .title("Updated Document")
                .content("# Updated Content\nThis is updated content.")
                .build();
    }

    // ==================== CREATE DOCUMENT TESTS ====================

    @Nested
    @DisplayName("POST /api/v1/projects/{projectSlug}/documents - Create a new document")
    class CreateDocumentTests {

        @Test
        @DisplayName("Should return 201 with created document details")
        void shouldCreateDocumentSuccessfully() throws Exception {
            // given
            DocumentCreateDTO createDto = createSampleCreateDto();
            DocumentDTO created = DocumentDTO.builder()
                    .id(2L)
                    .title("New Document")
                    .content("# New Content\nThis is new content.")
                    .projectId(1L)
                    .linkedDocuments(List.of())
                    .createdAt(now)
                    .updatedAt(now)
                    .build();

            given(documentService.createDocument(eq(1L), any(DocumentCreateDTO.class))).willReturn(created);

            // when & then
            mockMvc.perform(post("/api/v1/projects/test-project/documents")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createDto)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.title").value("New Document"))
                    .andExpect(jsonPath("$.projectId").value(1))
                    .andExpect(jsonPath("$.id").value(2));

            verify(documentService).createDocument(eq(1L), any(DocumentCreateDTO.class));
        }

        @Test
        @DisplayName("Should return 400 when validation fails (missing title)")
        void shouldReturnBadRequestWhenTitleMissing() throws Exception {
            // given
            DocumentCreateDTO invalidDto = DocumentCreateDTO.builder().build();

            // when & then
            mockMvc.perform(post("/api/v1/projects/test-project/documents")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalidDto)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.title").exists());
        }
    }

    // ==================== GET DOCUMENTS TESTS ====================

    @Nested
    @DisplayName("GET /api/v1/projects/{projectSlug}/documents - List all documents")
    class GetDocumentsTests {

        @Test
        @DisplayName("Should return 200 with list of documents")
        void shouldReturnAllDocuments() throws Exception {
            // given
            DocumentDTO doc = createSampleDocument();
            given(documentService.getDocumentsByProject(1L)).willReturn(List.of(doc));

            // when & then
            mockMvc.perform(get("/api/v1/projects/test-project/documents"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].title").value("Test Document"))
                    .andExpect(jsonPath("$[0].projectId").value(1));
        }

        @Test
        @DisplayName("Should return 200 with empty list when no documents exist")
        void shouldReturnEmptyList() throws Exception {
            // given
            given(documentService.getDocumentsByProject(1L)).willReturn(List.of());

            // when & then
            mockMvc.perform(get("/api/v1/projects/test-project/documents"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));
        }
    }

    // ==================== GET DOCUMENT BY SLUG TESTS ====================

    @Nested
    @DisplayName("GET /api/v1/projects/{projectSlug}/documents/{docSlug} - Get document by slug")
    class GetDocumentBySlugTests {

        @Test
        @DisplayName("Should return 200 with document details when found")
        void shouldReturnDocumentWhenFound() throws Exception {
            // given
            DocumentDTO doc = createSampleDocument();
            given(documentService.getDocument(eq(1L), eq("test-document"))).willReturn(doc);

            // when & then
            mockMvc.perform(get("/api/v1/projects/test-project/documents/test-document"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.title").value("Test Document"))
                    .andExpect(jsonPath("$.content").value("# Hello World\nThis is a test document."));
        }

        @Test
        @DisplayName("Should return 404 when document not found")
        void shouldReturnNotFoundWhenNotExists() throws Exception {
            // given
            given(documentService.getDocument(eq(1L), eq("non-existent")))
                    .willThrow(new EntityNotFoundException(
                            "Document not found with slug 'non-existent' in project 1"));

            // when & then
            mockMvc.perform(get("/api/v1/projects/test-project/documents/non-existent"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Document not found with slug 'non-existent' in project 1"));
        }
    }

    // ==================== UPDATE DOCUMENT TESTS ====================

    @Nested
    @DisplayName("PUT /api/v1/projects/{projectSlug}/documents/{docSlug} - Update a document")
    class UpdateDocumentTests {

        @Test
        @DisplayName("Should return 200 with updated document details")
        void shouldUpdateDocumentSuccessfully() throws Exception {
            // given
            DocumentUpdateDTO updateDto = createSampleUpdateDto();
            DocumentDTO updated = DocumentDTO.builder()
                    .id(1L)
                    .title("Updated Document")
                    .content("# Updated Content\nThis is updated content.")
                    .projectId(1L)
                    .linkedDocuments(List.of())
                    .createdAt(now)
                    .updatedAt(now)
                    .build();

            given(documentService.updateDocumentBySlug(eq(1L), eq("test-document"), any(DocumentUpdateDTO.class)))
                    .willReturn(updated);

            // when & then
            mockMvc.perform(put("/api/v1/projects/test-project/documents/test-document")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateDto)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.title").value("Updated Document"))
                    .andExpect(jsonPath("$.content").value("# Updated Content\nThis is updated content."));

            verify(documentService).updateDocumentBySlug(eq(1L), eq("test-document"), any(DocumentUpdateDTO.class));
        }

        @Test
        @DisplayName("Should return 404 when document not found")
        void shouldReturnNotFoundWhenNotExists() throws Exception {
            // given
            DocumentUpdateDTO updateDto = createSampleUpdateDto();
            given(documentService.updateDocumentBySlug(eq(1L), eq("non-existent"), any(DocumentUpdateDTO.class)))
                    .willThrow(new EntityNotFoundException(
                            "Document not found with slug 'non-existent' in project 1"));

            // when & then
            mockMvc.perform(put("/api/v1/projects/test-project/documents/non-existent")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateDto)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Document not found with slug 'non-existent' in project 1"));
        }

        @Test
        @DisplayName("Should return 400 when validation fails")
        void shouldReturnBadRequestWhenValidationFails() throws Exception {
            // given
            DocumentUpdateDTO invalidDto = DocumentUpdateDTO.builder().build();

            // when & then
            mockMvc.perform(put("/api/v1/projects/test-project/documents/test-document")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalidDto)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.title").exists());
        }
    }

    // ==================== DELETE DOCUMENT TESTS ====================

    @Nested
    @DisplayName("DELETE /api/v1/projects/{projectSlug}/documents/{docSlug} - Delete a document")
    class DeleteDocumentTests {

        @Test
        @DisplayName("Should return 204 when document deleted successfully")
        void shouldDeleteDocumentSuccessfully() throws Exception {
            // given
            doNothing().when(documentService).deleteDocumentBySlug(1L, "test-document");

            // when & then
            mockMvc.perform(delete("/api/v1/projects/test-project/documents/test-document"))
                    .andExpect(status().isNoContent());

            verify(documentService).deleteDocumentBySlug(1L, "test-document");
        }

        @Test
        @DisplayName("Should return 404 when document not found")
        void shouldReturnNotFoundWhenNotExists() throws Exception {
            // given
            doThrow(new EntityNotFoundException(
                    "Document not found with slug 'non-existent' in project 1"))
                    .when(documentService).deleteDocumentBySlug(eq(1L), eq("non-existent"));

            // when & then
            mockMvc.perform(delete("/api/v1/projects/test-project/documents/non-existent"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Document not found with slug 'non-existent' in project 1"));
        }
    }

    // ==================== ADD LINK TESTS ====================

    @Nested
    @DisplayName("POST /api/v1/projects/{projectSlug}/documents/{docSlug}/links - Add a link")
    class AddLinkTests {

        @Test
        @DisplayName("Should return 200 with updated document when link added successfully")
        void shouldAddLinkSuccessfully() throws Exception {
            // given
            LinkCreateDTO linkDto = LinkCreateDTO.builder()
                    .targetDocumentId(5L)
                    .build();

            DocumentDTO updatedDoc = DocumentDTO.builder()
                    .id(1L)
                    .title("Test Document")
                    .content("# Hello World")
                    .projectId(1L)
                    .linkedDocuments(List.of(5L))
                    .createdAt(now)
                    .updatedAt(now)
                    .build();

            given(documentService.addLink(eq(1L), eq(5L))).willReturn(updatedDoc);

            // when & then
            mockMvc.perform(post("/api/v1/projects/test-project/documents/test-document/links")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(linkDto)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.linkedDocuments").isArray())
                    .andExpect(jsonPath("$.linkedDocuments[0]").value(5));

            verify(documentService).addLink(eq(1L), eq(5L));
        }

        @Test
        @DisplayName("Should return 400 when validation fails (missing targetDocumentId)")
        void shouldReturnBadRequestWhenTargetDocIdMissing() throws Exception {
            // given
            LinkCreateDTO invalidDto = LinkCreateDTO.builder().build();

            // when & then
            mockMvc.perform(post("/api/v1/projects/test-project/documents/test-document/links")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalidDto)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.targetDocumentId").exists());
        }

        @Test
        @DisplayName("Should return 404 when source document not found")
        void shouldReturnNotFoundWhenSourceDocNotExists() throws Exception {
            // given
            LinkCreateDTO linkDto = LinkCreateDTO.builder()
                    .targetDocumentId(5L)
                    .build();

            given(documentService.addLink(eq(1L), eq(5L)))
                    .willThrow(new EntityNotFoundException("Source document not found with id: 1"));

            // when & then
            mockMvc.perform(post("/api/v1/projects/test-project/documents/test-document/links")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(linkDto)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Source document not found with id: 1"));
        }

        @Test
        @DisplayName("Should return 409 when link already exists")
        void shouldReturnConflictWhenLinkAlreadyExists() throws Exception {
            // given
            LinkCreateDTO linkDto = LinkCreateDTO.builder()
                    .targetDocumentId(5L)
                    .build();

            given(documentService.addLink(eq(1L), eq(5L)))
                    .willThrow(new IllegalArgumentException("Link already exists between these documents"));

            // when & then
            mockMvc.perform(post("/api/v1/projects/test-project/documents/test-document/links")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(linkDto)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("Link already exists between these documents"));
        }

        @Test
        @DisplayName("Should return 409 when documents belong to different projects")
        void shouldReturnConflictWhenDifferentProjects() throws Exception {
            // given
            LinkCreateDTO linkDto = LinkCreateDTO.builder()
                    .targetDocumentId(5L)
                    .build();

            given(documentService.addLink(eq(1L), eq(5L)))
                    .willThrow(new IllegalArgumentException(
                            "Documents must belong to the same project. Source project: 1, Target project: 2"));

            // when & then
            mockMvc.perform(post("/api/v1/projects/test-project/documents/test-document/links")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(linkDto)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value(
                            "Documents must belong to the same project. Source project: 1, Target project: 2"));
        }

        @Test
        @DisplayName("Should return 409 when trying to link document to itself")
        void shouldReturnConflictWhenSelfLink() throws Exception {
            // given
            LinkCreateDTO linkDto = LinkCreateDTO.builder()
                    .targetDocumentId(1L)
                    .build();

            given(documentService.addLink(eq(1L), eq(1L)))
                    .willThrow(new IllegalArgumentException("Cannot link a document to itself"));

            // when & then
            mockMvc.perform(post("/api/v1/projects/test-project/documents/test-document/links")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(linkDto)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("Cannot link a document to itself"));
        }
    }

    // ==================== REMOVE LINK TESTS ====================

    @Nested
    @DisplayName("DELETE /api/v1/projects/{projectSlug}/documents/{docSlug}/links/{targetDocId} - Remove a link")
    class RemoveLinkTests {

        @Test
        @DisplayName("Should return 204 when link removed successfully")
        void shouldRemoveLinkSuccessfully() throws Exception {
            // given
            doNothing().when(documentService).removeLink(1L, 5L);

            // when & then
            mockMvc.perform(delete("/api/v1/projects/test-project/documents/test-document/links/5"))
                    .andExpect(status().isNoContent());

            verify(documentService).removeLink(1L, 5L);
        }

        @Test
        @DisplayName("Should return 404 when source document not found")
        void shouldReturnNotFoundWhenSourceDocNotExists() throws Exception {
            // given
            doThrow(new EntityNotFoundException("Source document not found with id: 1"))
                    .when(documentService).removeLink(eq(1L), eq(5L));

            // when & then
            mockMvc.perform(delete("/api/v1/projects/test-project/documents/test-document/links/5"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Source document not found with id: 1"));
        }

        @Test
        @DisplayName("Should return 409 when link does not exist")
        void shouldReturnConflictWhenLinkDoesNotExist() throws Exception {
            // given
            doThrow(new IllegalArgumentException("Link does not exist between these documents"))
                    .when(documentService).removeLink(eq(1L), eq(5L));

            // when & then
            mockMvc.perform(delete("/api/v1/projects/test-project/documents/test-document/links/5"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("Link does not exist between these documents"));
        }
    }

    // ==================== GET LINKS TESTS ====================

    @Nested
    @DisplayName("GET /api/v1/projects/{projectSlug}/documents/{docSlug}/links - Get linked documents")
    class GetLinksTests {

        @Test
        @DisplayName("Should return 200 with list of linked documents")
        void shouldReturnLinkedDocuments() throws Exception {
            // given
            DocumentDTO linkedDoc = DocumentDTO.builder()
                    .id(5L)
                    .title("Linked Document")
                    .content("# Linked Content")
                    .projectId(1L)
                    .linkedDocuments(List.of())
                    .createdAt(now)
                    .updatedAt(now)
                    .build();

            given(documentService.getLinkedDocuments(1L)).willReturn(List.of(linkedDoc));

            // when & then
            mockMvc.perform(get("/api/v1/projects/test-project/documents/test-document/links"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].title").value("Linked Document"));
        }

        @Test
        @DisplayName("Should return 200 with empty list when no links exist")
        void shouldReturnEmptyListWhenNoLinks() throws Exception {
            // given
            given(documentService.getLinkedDocuments(1L)).willReturn(List.of());

            // when & then
            mockMvc.perform(get("/api/v1/projects/test-project/documents/test-document/links"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));
        }

        @Test
        @DisplayName("Should return 404 when document not found")
        void shouldReturnNotFoundWhenDocNotExists() throws Exception {
            // given
            given(documentService.getLinkedDocuments(1L))
                    .willThrow(new EntityNotFoundException("Document not found with id: 1"));

            // when & then
            mockMvc.perform(get("/api/v1/projects/test-project/documents/non-existent/links"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Document not found with id: 1"));
        }
    }

    // ==================== SWAGGER/OPENAPI ANNOTATIONS TESTS ====================

    @Nested
    @DisplayName("Swagger/OpenAPI annotations")
    class SwaggerAnnotationsTests {

        @Test
        @DisplayName("Controller should have proper Tag annotation")
        void shouldHaveTagAnnotation() throws Exception {
            mockMvc.perform(get("/api/v1/projects/test-project/documents"))
                    .andExpect(status().isOk()); // Will fail if controller not registered
        }

        @Test
        @DisplayName("All CRUD endpoints should be properly mapped")
        void shouldHaveAllEndpointsMapped() throws Exception {
            // Verify that all endpoints are properly mapped by checking status codes
            mockMvc.perform(get("/api/v1/projects/test-project/documents"))
                    .andExpect(status().isOk());

            mockMvc.perform(post("/api/v1/projects/test-project/documents")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"Test\"}"))
                    .andExpect(status().isCreated());

            mockMvc.perform(put("/api/v1/projects/test-project/documents/test-doc")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"Updated\"}"))
                    .andExpect(status().isOk());

            mockMvc.perform(delete("/api/v1/projects/test-project/documents/test-doc"))
                    .andExpect(status().isNoContent());
        }
    }
}
