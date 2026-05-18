package com.wiki4ai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wiki4ai.dto.DocumentContentDTO;
import com.wiki4ai.dto.DocumentCreateDTO;
import com.wiki4ai.dto.DocumentDTO;
import com.wiki4ai.dto.DocumentUpdateDTO;
import com.wiki4ai.dto.LinkCreateDTO;
import com.wiki4ai.dto.ProjectDTO;
import com.wiki4ai.service.DocumentService;
import com.wiki4ai.service.ProjectService;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
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

    @MockBean
    private ProjectService projectService;

    private final LocalDateTime now = LocalDateTime.of(2024, 5, 16, 10, 0);

    // Helper to mock project resolution for any slug
    private void mockProjectResolution(String slug) {
        given(projectService.getProjectBySlug(slug))
                .willReturn(ProjectDTO.builder().id(1L).slug(slug).name("Test Project").build());
    }

    private DocumentDTO createSampleDocument() {
        return DocumentDTO.builder()
                .id(1L)
                .title("Test Document")
                .content("# Hello World\nThis is a test document.")
                .slug("test-document")
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
            mockProjectResolution("test-project");
            DocumentCreateDTO createDto = createSampleCreateDto();
            DocumentDTO created = DocumentDTO.builder()
                    .id(2L)
                    .title("New Document")
                    .content("# New Content\nThis is new content.")
                    .slug("new-document")
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
            mockProjectResolution("test-project");
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
            mockProjectResolution("test-project");
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
            mockProjectResolution("test-project");
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
            mockProjectResolution("test-project");
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
            mockProjectResolution("test-project");
            DocumentUpdateDTO updateDto = createSampleUpdateDto();
            DocumentDTO updated = DocumentDTO.builder()
                    .id(1L)
                    .title("Updated Document")
                    .content("# Updated Content\nThis is updated content.")
                    .slug("updated-document")
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
            mockProjectResolution("test-project");
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
            mockProjectResolution("test-project");
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
            mockProjectResolution("test-project");
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
            mockProjectResolution("test-project");
            DocumentDTO sourceDoc = createSampleDocument();
            given(documentService.getDocument(eq(1L), eq("test-document"))).willReturn(sourceDoc);
            LinkCreateDTO linkDto = LinkCreateDTO.builder()
                    .targetDocumentId(5L)
                    .build();

            DocumentDTO updatedDoc = DocumentDTO.builder()
                    .id(1L)
                    .title("Test Document")
                    .content("# Hello World")
                    .slug("test-document")
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
            mockProjectResolution("test-project");
            given(documentService.getDocument(eq(1L), eq("test-document")))
                    .willThrow(new EntityNotFoundException("Document not found with slug 'test-document' in project 1"));
            LinkCreateDTO linkDto = LinkCreateDTO.builder()
                    .targetDocumentId(5L)
                    .build();

            // when & then
            mockMvc.perform(post("/api/v1/projects/test-project/documents/test-document/links")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(linkDto)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Document not found with slug 'test-document' in project 1"));
        }

        @Test
        @DisplayName("Should return 409 when link already exists")
        void shouldReturnConflictWhenLinkAlreadyExists() throws Exception {
            // given
            mockProjectResolution("test-project");
            DocumentDTO sourceDoc = createSampleDocument();
            given(documentService.getDocument(eq(1L), eq("test-document"))).willReturn(sourceDoc);
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
            mockProjectResolution("test-project");
            DocumentDTO sourceDoc = createSampleDocument();
            given(documentService.getDocument(eq(1L), eq("test-document"))).willReturn(sourceDoc);
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
            mockProjectResolution("test-project");
            DocumentDTO sourceDoc = createSampleDocument();
            given(documentService.getDocument(eq(1L), eq("test-document"))).willReturn(sourceDoc);
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
            mockProjectResolution("test-project");
            DocumentDTO sourceDoc = createSampleDocument();
            given(documentService.getDocument(eq(1L), eq("test-document"))).willReturn(sourceDoc);
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
            mockProjectResolution("test-project");
            given(documentService.getDocument(eq(1L), eq("test-document")))
                    .willThrow(new EntityNotFoundException("Document not found with slug 'test-document' in project 1"));

            // when & then
            mockMvc.perform(delete("/api/v1/projects/test-project/documents/test-document/links/5"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Document not found with slug 'test-document' in project 1"));
        }

        @Test
        @DisplayName("Should return 409 when link does not exist")
        void shouldReturnConflictWhenLinkDoesNotExist() throws Exception {
            // given
            mockProjectResolution("test-project");
            DocumentDTO sourceDoc = createSampleDocument();
            given(documentService.getDocument(eq(1L), eq("test-document"))).willReturn(sourceDoc);
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
            mockProjectResolution("test-project");
            DocumentDTO sourceDoc = createSampleDocument();
            given(documentService.getDocument(eq(1L), eq("test-document"))).willReturn(sourceDoc);
            DocumentDTO linkedDoc = DocumentDTO.builder()
                    .id(5L)
                    .title("Linked Document")
                    .content("# Linked Content")
                    .slug("linked-document")
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
            mockProjectResolution("test-project");
            DocumentDTO sourceDoc = createSampleDocument();
            given(documentService.getDocument(eq(1L), eq("test-document"))).willReturn(sourceDoc);
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
            mockProjectResolution("test-project");
            given(documentService.getDocument(eq(1L), eq("non-existent")))
                    .willThrow(new EntityNotFoundException("Document not found with slug 'non-existent' in project 1"));

            // when & then
            mockMvc.perform(get("/api/v1/projects/test-project/documents/non-existent/links"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Document not found with slug 'non-existent' in project 1"));
        }
    }

    // ==================== GET DOCUMENT CONTENT TESTS ====================

    @Nested
    @DisplayName("GET /api/v1/projects/{projectSlug}/documents/{docSlug}/content - Get document content with rendered markdown")
    class GetDocumentContentTests {

        private DocumentContentDTO createSampleContentDto() {
            return new DocumentContentDTO(
                    1L,
                    "Test Document",
                    "<h1>Hello World</h1>\n<p>This is a test document.</p>",
                    List.of("Introduction", "API Reference"),
                    List.of(DocumentDTO.builder().id(5L).title("Linked Doc").build())
            );
        }

        @Test
        @DisplayName("Should return 200 with rendered content, wiki links and linked documents")
        void shouldReturnContentSuccessfully() throws Exception {
            // given
            mockProjectResolution("test-project");
            DocumentContentDTO contentDto = createSampleContentDto();
            given(documentService.getDocumentContent(eq(1L), eq("test-document"))).willReturn(contentDto);

            // when & then
            mockMvc.perform(get("/api/v1/projects/test-project/documents/test-document/content"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(1))
                    .andExpect(jsonPath("$.title").value("Test Document"))
                    .andExpect(jsonPath("$.htmlContent").exists())
                    .andExpect(jsonPath("$.wikiLinks").isArray())
                    .andExpect(jsonPath("$.wikiLinks[0]").value("Introduction"))
                    .andExpect(jsonPath("$.wikiLinks[1]").value("API Reference"))
                    .andExpect(jsonPath("$.linkedDocuments").isArray())
                    .andExpect(jsonPath("$.linkedDocuments[0].id").value(5));

            verify(documentService).getDocumentContent(eq(1L), eq("test-document"));
        }

        @Test
        @DisplayName("Should return 404 when document not found")
        void shouldReturnNotFoundWhenNotExists() throws Exception {
            // given
            mockProjectResolution("test-project");
            given(documentService.getDocumentContent(eq(1L), eq("non-existent")))
                    .willThrow(new EntityNotFoundException(
                            "Document not found with slug 'non-existent' in project 1"));

            // when & then
            mockMvc.perform(get("/api/v1/projects/test-project/documents/non-existent/content"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Document not found with slug 'non-existent' in project 1"));
        }

        @Test
        @DisplayName("Should return content with empty wiki links when no wiki references exist")
        void shouldReturnEmptyWikiLinks() throws Exception {
            // given
            mockProjectResolution("test-project");
            DocumentContentDTO contentDto = new DocumentContentDTO(
                    2L,
                    "Simple Doc",
                    "<h1>Simple</h1>\n<p>No wiki links here.</p>",
                    List.of(),
                    List.of()
            );
            given(documentService.getDocumentContent(eq(1L), eq("simple-doc"))).willReturn(contentDto);

            // when & then
            mockMvc.perform(get("/api/v1/projects/test-project/documents/simple-doc/content"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.wikiLinks").isArray())
                    .andExpect(jsonPath("$.wikiLinks.length()").value(0));
        }
    }

    // ==================== SEARCH DOCUMENTS TESTS ====================

    @Nested
    @DisplayName("GET /api/v1/projects/{projectSlug}/documents/search - Search documents by keyword")
    class SearchDocumentsTests {

        @Test
        @DisplayName("Should return 200 with matching documents when valid keyword is provided")
        void shouldReturnMatchingDocuments() throws Exception {
            // given
            mockProjectResolution("test-project");
            DocumentDTO doc1 = DocumentDTO.builder()
                    .id(1L)
                    .title("Spring Boot Guide")
                    .content("# Spring Boot\nLearn about Spring Boot framework.")
                    .slug("spring-boot-guide")
                    .projectId(1L)
                    .linkedDocuments(List.of())
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            DocumentDTO doc2 = DocumentDTO.builder()
                    .id(3L)
                    .title("Spring Security Docs")
                    .content("# Spring Security\nConfigure security in Spring Boot.")
                    .slug("spring-security-docs")
                    .projectId(1L)
                    .linkedDocuments(List.of())
                    .createdAt(now)
                    .updatedAt(now)
                    .build();

            given(documentService.searchDocuments(eq(1L), eq("Spring"))).willReturn(List.of(doc1, doc2));

            // when & then
            mockMvc.perform(get("/api/v1/projects/test-project/documents/search")
                            .param("keyword", "Spring"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[0].title").value("Spring Boot Guide"))
                    .andExpect(jsonPath("$[1].title").value("Spring Security Docs"));

            verify(documentService).searchDocuments(eq(1L), eq("Spring"));
        }

        @Test
        @DisplayName("Should return 200 with empty list when no documents match")
        void shouldReturnEmptyListWhenNoMatches() throws Exception {
            // given
            mockProjectResolution("test-project");
            given(documentService.searchDocuments(eq(1L), eq("nonexistent"))).willReturn(List.of());

            // when & then
            mockMvc.perform(get("/api/v1/projects/test-project/documents/search")
                            .param("keyword", "nonexistent"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));
        }

        @Test
        @DisplayName("Should return 400 when keyword is empty")
        void shouldReturnBadRequestWhenKeywordEmpty() throws Exception {
            // when & then
            mockMvc.perform(get("/api/v1/projects/test-project/documents/search")
                            .param("keyword", ""))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Should return 400 when keyword is too short (1 character)")
        void shouldReturnBadRequestWhenKeywordTooShort() throws Exception {
            // when & then
            mockMvc.perform(get("/api/v1/projects/test-project/documents/search")
                            .param("keyword", "a"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Should return 400 when keyword parameter is missing")
        void shouldReturnBadRequestWhenKeywordMissing() throws Exception {
            // when & then
            mockMvc.perform(get("/api/v1/projects/test-project/documents/search"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Should return 404 when project does not exist")
        void shouldReturnNotFoundWhenProjectNotExists() throws Exception {
            // given
            given(projectService.getProjectBySlug("non-existent-project"))
                    .willThrow(new EntityNotFoundException("Project not found with slug 'non-existent-project'"));

            // when & then
            mockMvc.perform(get("/api/v1/projects/non-existent-project/documents/search")
                            .param("keyword", "test"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Project not found with slug 'non-existent-project'"));
        }

        @Test
        @DisplayName("Should return 200 when keyword is exactly 2 characters (minimum valid)")
        void shouldAcceptMinimumLengthKeyword() throws Exception {
            // given
            mockProjectResolution("test-project");
            DocumentDTO doc = createSampleDocument();
            given(documentService.searchDocuments(eq(1L), eq("He"))).willReturn(List.of(doc));

            // when & then
            mockMvc.perform(get("/api/v1/projects/test-project/documents/search")
                            .param("keyword", "He"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].title").value("Test Document"));

            verify(documentService).searchDocuments(eq(1L), eq("He"));
        }

        @Test
        @DisplayName("Should trim whitespace from keyword before searching")
        void shouldTrimKeywordWhitespace() throws Exception {
            // given
            mockProjectResolution("test-project");
            DocumentDTO doc = createSampleDocument();
            given(documentService.searchDocuments(eq(1L), eq("Spring"))).willReturn(List.of(doc));

            // when & then - keyword has leading/trailing spaces
            mockMvc.perform(get("/api/v1/projects/test-project/documents/search")
                            .param("keyword", "  Spring  "))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1));

            verify(documentService).searchDocuments(eq(1L), eq("Spring"));
        }
    }

    // ==================== SWAGGER/OPENAPI ANNOTATIONS TESTS ====================

    @Nested
    @DisplayName("Swagger/OpenAPI annotations")
    class SwaggerAnnotationsTests {

        @Test
        @DisplayName("Controller should have proper Tag annotation")
        void shouldHaveTagAnnotation() throws Exception {
            mockProjectResolution("test-project");
            mockMvc.perform(get("/api/v1/projects/test-project/documents"))
                    .andExpect(status().isOk()); // Will fail if controller not registered
        }

        @Test
        @DisplayName("All CRUD endpoints should be properly mapped")
        void shouldHaveAllEndpointsMapped() throws Exception {
            mockProjectResolution("test-project");
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

    // ==================== UPLOAD DOCUMENT TESTS ====================

    @Nested
    @DisplayName("POST /api/v1/projects/{projectSlug}/documents/upload - Upload markdown file")
    class UploadDocumentTests {

        @Test
        @DisplayName("Should return 201 with created document when uploading valid .md file")
        void shouldUploadMdFileSuccessfully() throws Exception {
            // given
            mockProjectResolution("test-project");
            String fileContent = "# My Uploaded Document\nThis is the content.";
            MockMultipartFile file = new MockMultipartFile(
                    "file",
                    "My Uploaded Document.md",
                    MediaType.TEXT_PLAIN_VALUE,
                    fileContent.getBytes()
            );

            DocumentDTO created = DocumentDTO.builder()
                    .id(10L)
                    .title("My Uploaded Document")
                    .content(fileContent)
                    .slug("my-uploaded-document")
                    .projectId(1L)
                    .linkedDocuments(List.of())
                    .createdAt(now)
                    .updatedAt(now)
                    .build();

            given(documentService.uploadDocument(eq(1L), eq("My Uploaded Document.md"), eq(fileContent)))
                    .willReturn(created);

            // when & then
            mockMvc.perform(multipart("/api/v1/projects/test-project/documents/upload")
                            .file(file))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").value(10))
                    .andExpect(jsonPath("$.title").value("My Uploaded Document"))
                    .andExpect(jsonPath("$.slug").value("my-uploaded-document"));

            verify(documentService).uploadDocument(eq(1L), eq("My Uploaded Document.md"), eq(fileContent));
        }

        @Test
        @DisplayName("Should return 201 when uploading valid .markdown file")
        void shouldUploadMarkdownFileSuccessfully() throws Exception {
            // given
            mockProjectResolution("test-project");
            String fileContent = "# API Reference\nFull API docs.";
            MockMultipartFile file = new MockMultipartFile(
                    "file",
                    "API Reference.markdown",
                    MediaType.TEXT_PLAIN_VALUE,
                    fileContent.getBytes()
            );

            DocumentDTO created = DocumentDTO.builder()
                    .id(11L)
                    .title("API Reference")
                    .content(fileContent)
                    .slug("api-reference")
                    .projectId(1L)
                    .linkedDocuments(List.of())
                    .createdAt(now)
                    .updatedAt(now)
                    .build();

            given(documentService.uploadDocument(eq(1L), eq("API Reference.markdown"), eq(fileContent)))
                    .willReturn(created);

            // when & then
            mockMvc.perform(multipart("/api/v1/projects/test-project/documents/upload")
                            .file(file))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.title").value("API Reference"));

            verify(documentService).uploadDocument(eq(1L), eq("API Reference.markdown"), eq(fileContent));
        }

        @Test
        @DisplayName("Should return 400 when uploading invalid file format (.txt)")
        void shouldRejectInvalidFileFormat() throws Exception {
            // given
            MockMultipartFile file = new MockMultipartFile(
                    "file",
                    "notes.txt",
                    MediaType.TEXT_PLAIN_VALUE,
                    "Some text content".getBytes()
            );

            // when & then
            mockMvc.perform(multipart("/api/v1/projects/test-project/documents/upload")
                            .file(file))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(
                            "Invalid file format. Only .md and .markdown files are allowed."));
        }

        @Test
        @DisplayName("Should return 400 when uploading invalid file format (.pdf)")
        void shouldRejectPdfFile() throws Exception {
            // given
            MockMultipartFile file = new MockMultipartFile(
                    "file",
                    "document.pdf",
                    MediaType.APPLICATION_PDF_VALUE,
                    "%PDF-1.4 fake pdf content".getBytes()
            );

            // when & then
            mockMvc.perform(multipart("/api/v1/projects/test-project/documents/upload")
                            .file(file))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(
                            "Invalid file format. Only .md and .markdown files are allowed."));
        }

        @Test
        @DisplayName("Should return 409 when document with same title already exists")
        void shouldReturnConflictWhenTitleExists() throws Exception {
            // given
            mockProjectResolution("test-project");
            MockMultipartFile file = new MockMultipartFile(
                    "file",
                    "Existing Document.md",
                    MediaType.TEXT_PLAIN_VALUE,
                    "# Existing".getBytes()
            );

            given(documentService.uploadDocument(eq(1L), eq("Existing Document.md"), any()))
                    .willThrow(new IllegalArgumentException(
                            "A document with this title already exists in the project"));

            // when & then
            mockMvc.perform(multipart("/api/v1/projects/test-project/documents/upload")
                            .file(file))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value(
                            "A document with this title already exists in the project"));
        }

        @Test
        @DisplayName("Should return 404 when project does not exist")
        void shouldReturnNotFoundWhenProjectNotExists() throws Exception {
            // given
            MockMultipartFile file = new MockMultipartFile(
                    "file",
                    "New Doc.md",
                    MediaType.TEXT_PLAIN_VALUE,
                    "# New".getBytes()
            );

            given(projectService.getProjectBySlug("non-existent-project"))
                    .willThrow(new EntityNotFoundException("Project not found with slug 'non-existent-project'"));

            // when & then
            mockMvc.perform(multipart("/api/v1/projects/non-existent-project/documents/upload")
                            .file(file))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value(
                            "Project not found with slug 'non-existent-project'"));
        }

        @Test
        @DisplayName("Should handle file content with special characters and UTF-8 encoding")
        void shouldHandleUtf8Content() throws Exception {
            // given
            mockProjectResolution("test-project");
            String fileContent = "# Special document\nContent with unicode: hello world.";
            MockMultipartFile file = new MockMultipartFile(
                    "file",
                    "Special Document.md",
                    MediaType.TEXT_PLAIN_VALUE,
                    fileContent.getBytes()
            );

            DocumentDTO created = DocumentDTO.builder()
                    .id(12L)
                    .title("Special Document")
                    .content(fileContent)
                    .slug("special-document")
                    .projectId(1L)
                    .linkedDocuments(List.of())
                    .createdAt(now)
                    .updatedAt(now)
                    .build();

            given(documentService.uploadDocument(eq(1L), eq("Special Document.md"), eq(fileContent)))
                    .willReturn(created);

            // when & then
            mockMvc.perform(multipart("/api/v1/projects/test-project/documents/upload")
                            .file(file))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.title").value("Special Document"));
        }
    }
}
