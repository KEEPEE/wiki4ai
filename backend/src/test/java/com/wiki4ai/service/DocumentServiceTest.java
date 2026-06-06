package com.wiki4ai.service;

import com.wiki4ai.dto.DocumentCreateDTO;
import com.wiki4ai.dto.DocumentDTO;
import com.wiki4ai.dto.DocumentSummaryDTO;
import com.wiki4ai.dto.DocumentUpdateDTO;
import com.wiki4ai.dto.MoveRequestDTO;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for DocumentService using Mockito.
 * Tests pure service logic without database interaction.
 */
@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private PermissionService permissionService;

    @InjectMocks
    private DocumentService documentService;

    private Project testProject;
    private Document sourceDocument;
    private Document targetDocument;
    private DocumentCreateDTO validDocumentDto;

    @BeforeEach
    void setUp() {
        testProject = Project.builder()
                .id(1L)
                .name("Test Project")
                .description("A test project")
                .slug("test-project")
                .createdAt(LocalDateTime.of(2024, 1, 1, 0, 0))
                .updatedAt(LocalDateTime.of(2024, 1, 1, 0, 0))
                .build();

        sourceDocument = Document.builder()
                .id(1L)
                .title("Source Document")
                .content("Source content")
                .slug("source-document")
                .project(testProject)
                .linkedDocuments(new ArrayList<>())
                .createdAt(LocalDateTime.of(2024, 1, 1, 0, 0))
                .updatedAt(LocalDateTime.of(2024, 1, 1, 0, 0))
                .build();

        targetDocument = Document.builder()
                .id(2L)
                .title("Target Document")
                .content("Target content")
                .slug("target-document")
                .project(testProject)
                .linkedDocuments(new ArrayList<>())
                .createdAt(LocalDateTime.of(2024, 1, 1, 0, 0))
                .updatedAt(LocalDateTime.of(2024, 1, 1, 0, 0))
                .build();

        validDocumentDto = DocumentCreateDTO.builder()
                .title("New Document")
                .content("Some content here")
                .build();
    }

    @Nested
    @DisplayName("getDocumentsByProject")
    class GetDocumentsByProjectTests {

        @Test
        @DisplayName("Should return all documents in a project")
        void shouldReturnAllDocumentsInProject() {
            // given
            when(documentRepository.findByProjectId(1L)).thenReturn(List.of(sourceDocument, targetDocument));

            // when
            List<DocumentDTO> result = documentService.getDocumentsByProject(1L);

            // then
            assertThat(result).hasSize(2);
            assertThat(result.get(0).getTitle()).isEqualTo("Source Document");
            assertThat(result.get(1).getTitle()).isEqualTo("Target Document");
        }

        @Test
        @DisplayName("Should return empty list when project has no documents")
        void shouldReturnEmptyListWhenNoDocuments() {
            // given
            when(documentRepository.findByProjectId(1L)).thenReturn(List.of());

            // when
            List<DocumentDTO> result = documentService.getDocumentsByProject(1L);

            // then
            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("getDocumentById")
    class GetDocumentByIdTests {

        @Test
        @DisplayName("Should return document DTO when found by ID")
        void shouldReturnDtoWhenFound() {
            // given
            when(documentRepository.findById(1L)).thenReturn(Optional.of(sourceDocument));

            // when
            DocumentDTO result = documentService.getDocumentById(1L);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(1L);
            assertThat(result.getTitle()).isEqualTo("Source Document");
        }

        @Test
        @DisplayName("Should throw EntityNotFoundException when document not found by ID")
        void shouldThrowWhenNotFoundById() {
            // given
            when(documentRepository.findById(99L)).thenReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> documentService.getDocumentById(99L))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining("Document not found with id: 99");
        }
    }

    @Nested
    @DisplayName("getDocument (by slug)")
    class GetDocumentBySlugTests {

        @Test
        @DisplayName("Should return document DTO when found by slug within project")
        void shouldReturnDtoWhenFoundBySlug() {
            // given
            when(documentRepository.findBySlugAndProjectId("source-document", 1L))
                    .thenReturn(Optional.of(sourceDocument));

            // when
            DocumentDTO result = documentService.getDocument(1L, "source-document");

            // then
            assertThat(result).isNotNull();
            assertThat(result.getTitle()).isEqualTo("Source Document");
        }

        @Test
        @DisplayName("Should throw EntityNotFoundException when document not found by slug")
        void shouldThrowWhenNotFoundBySlug() {
            // given
            when(documentRepository.findBySlugAndProjectId("non-existent", 1L))
                    .thenReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> documentService.getDocument(1L, "non-existent"))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining("Document not found with slug 'non-existent' in project 1");
        }

        @Test
        @DisplayName("Should throw when document exists but belongs to different project")
        void shouldThrowWhenDocumentBelongsToDifferentProject() {
            // given — same slug, different project ID
            when(documentRepository.findBySlugAndProjectId("source-document", 99L))
                    .thenReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> documentService.getDocument(99L, "source-document"))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining("Document not found with slug 'source-document' in project 99");
        }
    }

    @Nested
    @DisplayName("createDocument")
    class CreateDocumentTests {

        @Test
        @DisplayName("Should create a new document and return DTO")
        void shouldCreateNewDocument() {
            // given
            when(projectRepository.findById(1L)).thenReturn(Optional.of(testProject));
            when(documentRepository.findByProjectIdAndTitle(1L, "New Document")).thenReturn(Optional.empty());
            when(documentRepository.save(any(Document.class))).thenAnswer(invocation -> {
                Document doc = invocation.getArgument(0);
                return Document.builder()
                        .id(3L)
                        .title(doc.getTitle())
                        .content(doc.getContent())
                        .slug("new-document")
                        .project(testProject)
                        .createdAt(LocalDateTime.now())
                        .updatedAt(LocalDateTime.now())
                        .build();
            });

            // when
            DocumentDTO result = documentService.createDocument(1L, validDocumentDto);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(3L);
            assertThat(result.getTitle()).isEqualTo("New Document");
            assertThat(result.getContent()).isEqualTo("Some content here");
            assertThat(result.getProjectId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("Should throw EntityNotFoundException when project does not exist")
        void shouldThrowWhenProjectNotFound() {
            // given
            when(projectRepository.findById(99L)).thenReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> documentService.createDocument(99L, validDocumentDto))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining("Project not found with id: 99");

            verify(documentRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should throw IllegalArgumentException when title already exists in project")
        void shouldThrowWhenTitleExists() {
            // given
            when(projectRepository.findById(1L)).thenReturn(Optional.of(testProject));
            when(documentRepository.findByProjectIdAndTitle(1L, "Existing Title"))
                    .thenReturn(Optional.of(sourceDocument));

            DocumentCreateDTO duplicateDto = DocumentCreateDTO.builder()
                    .title("Existing Title")
                    .content("Content")
                    .build();

            // when & then
            assertThatThrownBy(() -> documentService.createDocument(1L, duplicateDto))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("A document with this title already exists in the project");

            verify(documentRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("updateDocument (by ID)")
    class UpdateDocumentByIdTests {

        @Test
        @DisplayName("Should update an existing document and return updated DTO")
        void shouldUpdateExistingDocument() {
            // given
            DocumentUpdateDTO updateDto = DocumentUpdateDTO.builder()
                    .title("Updated Title")
                    .content("Updated content")
                    .build();

            when(documentRepository.findById(1L)).thenReturn(Optional.of(sourceDocument));
            when(documentRepository.save(any(Document.class))).thenAnswer(invocation -> {
                Document doc = invocation.getArgument(0);
                return Document.builder()
                        .id(doc.getId())
                        .title(doc.getTitle())
                        .content(doc.getContent())
                        .slug("updated-title")
                        .project(testProject)
                        .createdAt(sourceDocument.getCreatedAt())
                        .updatedAt(LocalDateTime.now())
                        .build();
            });

            // when
            DocumentDTO result = documentService.updateDocument(1L, updateDto);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getTitle()).isEqualTo("Updated Title");
            assertThat(result.getContent()).isEqualTo("Updated content");
        }

        @Test
        @DisplayName("Should throw EntityNotFoundException when updating non-existent document")
        void shouldThrowWhenUpdatingNonExistent() {
            // given
            when(documentRepository.findById(99L)).thenReturn(Optional.empty());
            DocumentUpdateDTO updateDto = DocumentUpdateDTO.builder().title("New").build();

            // when & then
            assertThatThrownBy(() -> documentService.updateDocument(99L, updateDto))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining("Document not found with id: 99");
        }
    }

    @Nested
    @DisplayName("updateDocument (by slug)")
    class UpdateDocumentBySlugTests {

        @Test
        @DisplayName("Should update document by slug within project")
        void shouldUpdateDocumentBySlug() {
            // given
            DocumentUpdateDTO updateDto = DocumentUpdateDTO.builder()
                    .title("Updated Title")
                    .content("New content here")
                    .build();

            when(documentRepository.findBySlugAndProjectId("source-document", 1L))
                    .thenReturn(Optional.of(sourceDocument));
            when(documentRepository.save(any(Document.class))).thenAnswer(invocation -> {
                Document doc = invocation.getArgument(0);
                return Document.builder()
                        .id(doc.getId())
                        .title(doc.getTitle())
                        .content(doc.getContent())
                        .slug("updated-title")
                        .project(testProject)
                        .createdAt(sourceDocument.getCreatedAt())
                        .updatedAt(LocalDateTime.now())
                        .build();
            });

            // when
            DocumentDTO result = documentService.updateDocumentBySlug(1L, "source-document", updateDto);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getTitle()).isEqualTo("Updated Title");
        }

        @Test
        @DisplayName("Should throw EntityNotFoundException when document not found by slug")
        void shouldThrowWhenNotFoundBySlug() {
            // given
            DocumentUpdateDTO updateDto = DocumentUpdateDTO.builder().title("New").build();
            when(documentRepository.findBySlugAndProjectId("non-existent", 1L))
                    .thenReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> documentService.updateDocumentBySlug(1L, "non-existent", updateDto))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining("Document not found with slug 'non-existent' in project 1");
        }
    }

    @Nested
    @DisplayName("deleteDocument (by ID)")
    class DeleteDocumentByIdTests {

        @Test
        @DisplayName("Should delete an existing document by ID")
        void shouldDeleteExistingDocument() {
            // given
            when(documentRepository.existsById(1L)).thenReturn(true);

            // when
            documentService.deleteDocument(1L);

            // then
            verify(documentRepository, times(1)).deleteById(1L);
        }

        @Test
        @DisplayName("Should throw EntityNotFoundException when deleting non-existent document")
        void shouldThrowWhenDeletingNonExistent() {
            // given
            when(documentRepository.existsById(99L)).thenReturn(false);

            // when & then
            assertThatThrownBy(() -> documentService.deleteDocument(99L))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining("Document not found with id: 99");

            verify(documentRepository, never()).deleteById(any());
        }
    }

    @Nested
    @DisplayName("deleteDocument (by slug)")
    class DeleteDocumentBySlugTests {

        @Test
        @DisplayName("Should delete document by slug within project")
        void shouldDeleteDocumentBySlug() {
            // given
            when(documentRepository.findBySlugAndProjectId("source-document", 1L))
                    .thenReturn(Optional.of(sourceDocument));

            // when
            documentService.deleteDocumentBySlug(1L, "source-document");

            // then
            verify(documentRepository, times(1)).delete(sourceDocument);
        }

        @Test
        @DisplayName("Should throw EntityNotFoundException when document not found by slug")
        void shouldThrowWhenNotFoundBySlug() {
            // given
            when(documentRepository.findBySlugAndProjectId("non-existent", 1L))
                    .thenReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> documentService.deleteDocumentBySlug(1L, "non-existent"))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining("Document not found with slug 'non-existent' in project 1");
        }

        @Test
        @DisplayName("Should throw when document belongs to different project")
        void shouldThrowWhenDocumentBelongsToDifferentProject() {
            // given — same slug, different project ID
            when(documentRepository.findBySlugAndProjectId("source-document", 99L))
                    .thenReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> documentService.deleteDocumentBySlug(99L, "source-document"))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining("Document not found with slug 'source-document' in project 99");
        }
    }

    @Nested
    @DisplayName("searchDocuments")
    class SearchDocumentsTests {

        @Test
        @DisplayName("Should return matching documents by keyword within project")
        void shouldReturnMatchingDocuments() {
            // given
            when(documentRepository.findByProjectIdAndContentContaining(1L, "content"))
                    .thenReturn(List.of(sourceDocument));

            // when
            List<DocumentDTO> result = documentService.searchDocuments(1L, "content");

            // then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getTitle()).isEqualTo("Source Document");
        }

        @Test
        @DisplayName("Should return empty list when no documents match")
        void shouldReturnEmptyListWhenNoMatch() {
            // given
            when(documentRepository.findByProjectIdAndContentContaining(1L, "nonexistent"))
                    .thenReturn(List.of());

            // when
            List<DocumentDTO> result = documentService.searchDocuments(1L, "nonexistent");

            // then
            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("addLink")
    class AddLinkTests {

        @Test
        @DisplayName("Should add a link between two documents in the same project")
        void shouldAddLinkBetweenDocuments() {
            // given
            when(documentRepository.findById(1L)).thenReturn(Optional.of(sourceDocument));
            when(documentRepository.findById(2L)).thenReturn(Optional.of(targetDocument));
            when(documentRepository.save(any(Document.class))).thenAnswer(invocation -> {
                Document doc = invocation.getArgument(0);
                return doc; // Return the same document with updated links
            });

            // when
            DocumentDTO result = documentService.addLink(1L, 2L);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getLinkedDocuments()).containsExactly(2L);
        }

        @Test
        @DisplayName("Should throw EntityNotFoundException when source document not found")
        void shouldThrowWhenSourceNotFound() {
            // given
            when(documentRepository.findById(99L)).thenReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> documentService.addLink(99L, 2L))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining("Source document not found with id: 99");
        }

        @Test
        @DisplayName("Should throw EntityNotFoundException when target document not found")
        void shouldThrowWhenTargetNotFound() {
            // given
            when(documentRepository.findById(1L)).thenReturn(Optional.of(sourceDocument));
            when(documentRepository.findById(99L)).thenReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> documentService.addLink(1L, 99L))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining("Target document not found with id: 99");
        }

        @Test
        @DisplayName("Should throw IllegalArgumentException when linking a document to itself")
        void shouldThrowWhenSelfLinking() {
            // given
            when(documentRepository.findById(1L)).thenReturn(Optional.of(sourceDocument));

            // when & then
            assertThatThrownBy(() -> documentService.addLink(1L, 1L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Cannot link a document to itself");
        }

        @Test
        @DisplayName("Should throw IllegalArgumentException when documents belong to different projects")
        void shouldThrowWhenDifferentProjects() {
            // given — target in different project
            Document otherProjectDoc = Document.builder()
                    .id(2L)
                    .title("Other Doc")
                    .content("Content")
                    .slug("other-doc")
                    .project(Project.builder().id(99L).name("Other").slug("other").build())
                    .linkedDocuments(new ArrayList<>())
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();

            when(documentRepository.findById(1L)).thenReturn(Optional.of(sourceDocument));
            when(documentRepository.findById(2L)).thenReturn(Optional.of(otherProjectDoc));

            // when & then
            assertThatThrownBy(() -> documentService.addLink(1L, 2L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Documents must belong to the same project");
        }

        @Test
        @DisplayName("Should throw IllegalArgumentException when link already exists")
        void shouldThrowWhenLinkExists() {
            // given — pre-existing link
            sourceDocument.addLinkedDocument(targetDocument);
            when(documentRepository.findById(1L)).thenReturn(Optional.of(sourceDocument));
            when(documentRepository.findById(2L)).thenReturn(Optional.of(targetDocument));

            // when & then
            assertThatThrownBy(() -> documentService.addLink(1L, 2L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Link already exists between these documents");
        }
    }

    @Nested
    @DisplayName("removeLink")
    class RemoveLinkTests {

        @Test
        @DisplayName("Should remove a link between two documents")
        void shouldRemoveExistingLink() {
            // given — pre-existing link
            sourceDocument.addLinkedDocument(targetDocument);

            when(documentRepository.findById(1L)).thenReturn(Optional.of(sourceDocument));
            when(documentRepository.findById(2L)).thenReturn(Optional.of(targetDocument));
            when(documentRepository.save(any(Document.class))).thenAnswer(invocation -> invocation.getArgument(0));

            // when
            documentService.removeLink(1L, 2L);

            // then
            assertThat(sourceDocument.getLinkedDocuments()).doesNotContain(targetDocument);
        }

        @Test
        @DisplayName("Should throw EntityNotFoundException when source document not found")
        void shouldThrowWhenSourceNotFound() {
            // given
            when(documentRepository.findById(99L)).thenReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> documentService.removeLink(99L, 2L))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining("Source document not found with id: 99");
        }

        @Test
        @DisplayName("Should throw EntityNotFoundException when target document not found")
        void shouldThrowWhenTargetNotFound() {
            // given
            when(documentRepository.findById(1L)).thenReturn(Optional.of(sourceDocument));
            when(documentRepository.findById(99L)).thenReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> documentService.removeLink(1L, 99L))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining("Target document not found with id: 99");
        }

        @Test
        @DisplayName("Should throw IllegalArgumentException when link does not exist")
        void shouldThrowWhenLinkDoesNotExist() {
            // given — no pre-existing link
            when(documentRepository.findById(1L)).thenReturn(Optional.of(sourceDocument));
            when(documentRepository.findById(2L)).thenReturn(Optional.of(targetDocument));

            // when & then
            assertThatThrownBy(() -> documentService.removeLink(1L, 2L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Link does not exist between these documents");
        }
    }

    @Nested
    @DisplayName("getLinkedDocuments")
    class GetLinkedDocumentsTests {

        @Test
        @DisplayName("Should return linked documents as DTOs")
        void shouldReturnLinkedDocuments() {
            // given — pre-existing links
            sourceDocument.addLinkedDocument(targetDocument);

            when(documentRepository.findById(1L)).thenReturn(Optional.of(sourceDocument));

            // when
            List<DocumentDTO> result = documentService.getLinkedDocuments(1L);

            // then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getId()).isEqualTo(2L);
            assertThat(result.get(0).getTitle()).isEqualTo("Target Document");
        }

        @Test
        @DisplayName("Should return empty list when no links exist")
        void shouldReturnEmptyListWhenNoLinks() {
            // given
            when(documentRepository.findById(1L)).thenReturn(Optional.of(sourceDocument));

            // when
            List<DocumentDTO> result = documentService.getLinkedDocuments(1L);

            // then
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Should throw EntityNotFoundException when document not found")
        void shouldThrowWhenNotFound() {
            // given
            when(documentRepository.findById(99L)).thenReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> documentService.getLinkedDocuments(99L))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining("Document not found with id: 99");
        }
    }

    @Nested
    @DisplayName("getBacklinks")
    class GetBacklinksTests {

        @Test
        @DisplayName("Should return documents that link to the target document (backlinks)")
        void shouldReturnBacklinks() {
            // given — docA and docB both link to sourceDocument (target)
            Document docA = Document.builder()
                    .id(3L)
                    .title("Doc A")
                    .content("Content A")
                    .slug("doc-a")
                    .project(testProject)
                    .linkedDocuments(new ArrayList<>())
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            docA.addLinkedDocument(sourceDocument);

            Document docB = Document.builder()
                    .id(4L)
                    .title("Doc B")
                    .content("Content B")
                    .slug("doc-b")
                    .project(testProject)
                    .linkedDocuments(new ArrayList<>())
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            docB.addLinkedDocument(sourceDocument);

            when(documentRepository.findById(1L)).thenReturn(Optional.of(sourceDocument));
            when(documentRepository.findByLinkedDocumentsId(1L)).thenReturn(List.of(docA, docB));

            // when
            List<DocumentDTO> result = documentService.getBacklinks(1L);

            // then
            assertThat(result).hasSize(2);
            assertThat(result.get(0).getTitle()).isEqualTo("Doc A");
            assertThat(result.get(1).getTitle()).isEqualTo("Doc B");
        }

        @Test
        @DisplayName("Should return empty list when no documents link to the target")
        void shouldReturnEmptyListWhenNoBacklinks() {
            // given
            when(documentRepository.findById(1L)).thenReturn(Optional.of(sourceDocument));
            when(documentRepository.findByLinkedDocumentsId(1L)).thenReturn(List.of());

            // when
            List<DocumentDTO> result = documentService.getBacklinks(1L);

            // then
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Should throw EntityNotFoundException when target document not found")
        void shouldThrowWhenTargetNotFound() {
            // given
            when(documentRepository.findById(99L)).thenReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> documentService.getBacklinks(99L))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining("Document not found with id: 99");

            // Repository backlink query should NOT be called if target doesn't exist
            verify(documentRepository, never()).findByLinkedDocumentsId(any());
        }
    }

    @Nested
    @DisplayName("convertToDTO")
    class ConvertToDtoTests {

        @Test
        @DisplayName("Should correctly convert Document entity to DTO including linked document IDs")
        void shouldConvertEntityToDtoWithLinks() {
            // given — pre-existing links
            sourceDocument.addLinkedDocument(targetDocument);

            when(documentRepository.findById(1L)).thenReturn(Optional.of(sourceDocument));

            // when
            DocumentDTO result = documentService.getDocumentById(1L);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(1L);
            assertThat(result.getTitle()).isEqualTo("Source Document");
            assertThat(result.getContent()).isEqualTo("Source content");
            assertThat(result.getProjectId()).isEqualTo(1L);
            assertThat(result.getLinkedDocuments()).containsExactly(2L);
        }

        @Test
        @DisplayName("Should convert document without links correctly")
        void shouldConvertEntityWithoutLinks() {
            // given — no links
            when(documentRepository.findById(1L)).thenReturn(Optional.of(sourceDocument));

            // when
            DocumentDTO result = documentService.getDocumentById(1L);

            // then
            assertThat(result.getLinkedDocuments()).isEmpty();
        }
    }

    @Nested
    @DisplayName("extractTitleFromFilename (static)")
    class ExtractTitleFromFilenameTests {

        @Test
        @DisplayName("Should extract title from .md filename")
        void shouldExtractTitleFromMdFile() {
            assertThat(DocumentService.extractTitleFromFilename("My Document.md"))
                    .isEqualTo("My Document");
        }

        @Test
        @DisplayName("Should extract title from .markdown filename")
        void shouldExtractTitleFromMarkdownFile() {
            assertThat(DocumentService.extractTitleFromFilename("API Reference.markdown"))
                    .isEqualTo("API Reference");
        }

        @Test
        @DisplayName("Should return full filename when extension is not recognized")
        void shouldReturnFullFilenameForUnknownExtension() {
            assertThat(DocumentService.extractTitleFromFilename("notes.txt"))
                    .isEqualTo("notes.txt");
        }

        @Test
        @DisplayName("Should handle filename with no extension")
        void shouldHandleNoExtension() {
            assertThat(DocumentService.extractTitleFromFilename("README"))
                    .isEqualTo("README");
        }

        @Test
        @DisplayName("Should trim whitespace from extracted title")
        void shouldTrimWhitespace() {
            // trim() removes leading and trailing whitespace from the final result
            assertThat(DocumentService.extractTitleFromFilename("  My Doc  .md"))
                    .isEqualTo("My Doc");
        }

        @Test
        @DisplayName("Should return empty string for null filename")
        void shouldReturnEmptyForNull() {
            assertThat(DocumentService.extractTitleFromFilename(null))
                    .isEmpty();
        }

        @Test
        @DisplayName("Should return empty string for blank filename")
        void shouldReturnEmptyForBlank() {
            assertThat(DocumentService.extractTitleFromFilename("   "))
                    .isEmpty();
        }

        @Test
        @DisplayName("Should handle uppercase .MD extension")
        void shouldHandleUppercaseExtension() {
            assertThat(DocumentService.extractTitleFromFilename("My Document.MD"))
                    .isEqualTo("My Document");
        }

        @Test
        @DisplayName("Should handle mixed case .Markdown extension")
        void shouldHandleMixedCaseExtension() {
            assertThat(DocumentService.extractTitleFromFilename("Notes.Markdown"))
                    .isEqualTo("Notes");
        }
    }

    @Nested
    @DisplayName("uploadDocument")
    class UploadDocumentTests {

        @Test
        @DisplayName("Should create document from uploaded file with title extracted from filename")
        void shouldCreateDocumentFromUpload() {
            // given
            when(projectRepository.findById(1L)).thenReturn(Optional.of(testProject));
            when(documentRepository.findByProjectIdAndTitle(1L, "My Guide"))
                    .thenReturn(Optional.empty());
            when(documentRepository.save(any(Document.class))).thenAnswer(invocation -> {
                Document doc = invocation.getArgument(0);
                return Document.builder()
                        .id(5L)
                        .title(doc.getTitle())
                        .content(doc.getContent())
                        .slug("my-guide")
                        .project(testProject)
                        .createdAt(LocalDateTime.now())
                        .updatedAt(LocalDateTime.now())
                        .build();
            });

            // when
            DocumentDTO result = documentService.uploadDocument(1L, "My Guide.md", "# My Guide\nContent here");

            // then
            assertThat(result).isNotNull();
            assertThat(result.getTitle()).isEqualTo("My Guide");
            assertThat(result.getContent()).isEqualTo("# My Guide\nContent here");
            assertThat(result.getProjectId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("Should throw EntityNotFoundException when project does not exist")
        void shouldThrowWhenProjectNotFound() {
            // given
            when(projectRepository.findById(99L)).thenReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> documentService.uploadDocument(99L, "test.md", "content"))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining("Project not found with id: 99");

            verify(documentRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should throw IllegalArgumentException when title already exists")
        void shouldThrowWhenTitleExists() {
            // given
            when(projectRepository.findById(1L)).thenReturn(Optional.of(testProject));
            when(documentRepository.findByProjectIdAndTitle(1L, "Existing"))
                    .thenReturn(Optional.of(sourceDocument));

            // when & then
            assertThatThrownBy(() -> documentService.uploadDocument(1L, "Existing.md", "content"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("A document with this title already exists in the project");

            verify(documentRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should handle .markdown extension correctly")
        void shouldHandleMarkdownExtension() {
            // given
            when(projectRepository.findById(1L)).thenReturn(Optional.of(testProject));
            when(documentRepository.findByProjectIdAndTitle(1L, "Full Guide"))
                    .thenReturn(Optional.empty());
            when(documentRepository.save(any(Document.class))).thenAnswer(invocation -> {
                Document doc = invocation.getArgument(0);
                return Document.builder()
                        .id(6L)
                        .title(doc.getTitle())
                        .content(doc.getContent())
                        .slug("full-guide")
                        .project(testProject)
                        .createdAt(LocalDateTime.now())
                        .updatedAt(LocalDateTime.now())
                        .build();
            });

            // when
            DocumentDTO result = documentService.uploadDocument(1L, "Full Guide.markdown", "# Full");

            // then
            assertThat(result.getTitle()).isEqualTo("Full Guide");
        }
    }

    @Nested
    @DisplayName("moveDocument")
    class MoveDocumentTests {

        private Project targetProject;

        @BeforeEach
        void setUpTargetProject() {
            targetProject = Project.builder()
                    .id(2L)
                    .name("Target Project")
                    .description("A target project for moving documents")
                    .slug("target-project")
                    .createdAt(LocalDateTime.of(2024, 1, 1, 0, 0))
                    .updatedAt(LocalDateTime.of(2024, 1, 1, 0, 0))
                    .build();
        }

        @Test
        @DisplayName("Should move document to target project successfully")
        void shouldMoveDocumentToTargetProject() {
            // given
            when(documentRepository.findBySlugAndProjectId("source-document", 1L))
                    .thenReturn(Optional.of(sourceDocument));
            when(projectRepository.findBySlug("target-project"))
                    .thenReturn(Optional.of(targetProject));
            when(documentRepository.findByProjectIdAndTitle(2L, "Source Document"))
                    .thenReturn(Optional.empty());
            when(documentRepository.findByLinkedDocumentsId(1L)).thenReturn(List.of());
            when(documentRepository.save(any(Document.class))).thenAnswer(invocation -> {
                Document doc = invocation.getArgument(0);
                return Document.builder()
                        .id(doc.getId())
                        .title(doc.getTitle())
                        .content(doc.getContent())
                        .slug(doc.getSlug())
                        .project(targetProject)
                        .linkedDocuments(new ArrayList<>())
                        .createdAt(doc.getCreatedAt())
                        .updatedAt(LocalDateTime.now())
                        .build();
            });

            // when
            DocumentDTO result = documentService.moveDocument(1L, "source-document", "target-project");

            // then
            assertThat(result).isNotNull();
            assertThat(result.getProjectId()).isEqualTo(2L);
            assertThat(result.getLinkedDocuments()).isEmpty();
        }

        @Test
        @DisplayName("Should throw EntityNotFoundException when source document not found")
        void shouldThrowWhenSourceDocumentNotFound() {
            // given
            when(documentRepository.findBySlugAndProjectId("non-existent", 1L))
                    .thenReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> documentService.moveDocument(1L, "non-existent", "target-project"))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining("Document not found with slug 'non-existent' in project 1");

            verify(projectRepository, never()).findBySlug(any());
        }

        @Test
        @DisplayName("Should throw EntityNotFoundException when target project not found")
        void shouldThrowWhenTargetProjectNotFound() {
            // given
            when(documentRepository.findBySlugAndProjectId("source-document", 1L))
                    .thenReturn(Optional.of(sourceDocument));
            when(projectRepository.findBySlug("non-existent-project"))
                    .thenReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> documentService.moveDocument(1L, "source-document", "non-existent-project"))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining("Target project not found with slug: non-existent-project");
        }

        @Test
        @DisplayName("Should throw IllegalArgumentException when moving to the same project")
        void shouldThrowWhenSameProject() {
            // given
            when(documentRepository.findBySlugAndProjectId("source-document", 1L))
                    .thenReturn(Optional.of(sourceDocument));
            when(projectRepository.findBySlug("test-project"))
                    .thenReturn(Optional.of(testProject));

            // when & then
            assertThatThrownBy(() -> documentService.moveDocument(1L, "source-document", "test-project"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Cannot move document to the same project");
        }

        @Test
        @DisplayName("Should throw IllegalArgumentException when title already exists in target project")
        void shouldThrowWhenTitleExistsInTarget() {
            // given
            when(documentRepository.findBySlugAndProjectId("source-document", 1L))
                    .thenReturn(Optional.of(sourceDocument));
            when(projectRepository.findBySlug("target-project"))
                    .thenReturn(Optional.of(targetProject));
            when(documentRepository.findByProjectIdAndTitle(2L, "Source Document"))
                    .thenReturn(Optional.of(targetDocument));

            // when & then
            assertThatThrownBy(() -> documentService.moveDocument(1L, "source-document", "target-project"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("A document with this title already exists in the target project");
        }

        @Test
        @DisplayName("Should remove cross-project backlinks from source project documents")
        void shouldRemoveCrossProjectBacklinks() {
            // given — a document in source project that links to our moving document
            Document backlinker = Document.builder()
                    .id(3L)
                    .title("Backlinker Doc")
                    .content("Content [[Source Document]]")
                    .slug("backlinker-doc")
                    .project(testProject) // same as source project (1L)
                    .linkedDocuments(new ArrayList<>())
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            backlinker.addLinkedDocument(sourceDocument);

            when(documentRepository.findBySlugAndProjectId("source-document", 1L))
                    .thenReturn(Optional.of(sourceDocument));
            when(projectRepository.findBySlug("target-project"))
                    .thenReturn(Optional.of(targetProject));
            when(documentRepository.findByProjectIdAndTitle(2L, "Source Document"))
                    .thenReturn(Optional.empty());
            when(documentRepository.findByLinkedDocumentsId(1L)).thenReturn(List.of(backlinker));
            when(documentRepository.save(any(Document.class))).thenAnswer(invocation -> invocation.getArgument(0));

            // when
            documentService.moveDocument(1L, "source-document", "target-project");

            // then — backlinker should no longer link to sourceDocument
            assertThat(backlinker.getLinkedDocuments()).doesNotContain(sourceDocument);
        }

        @Test
        @DisplayName("Should preserve backlinks from documents in the target project")
        void shouldPreserveBacklinksFromTargetProject() {
            // given — a document in target project that links to our moving document
            Document targetBacklinker = Document.builder()
                    .id(4L)
                    .title("Target Backlinker")
                    .content("Content [[Source Document]]")
                    .slug("target-backlinker")
                    .project(targetProject) // target project (2L)
                    .linkedDocuments(new ArrayList<>())
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            targetBacklinker.addLinkedDocument(sourceDocument);

            when(documentRepository.findBySlugAndProjectId("source-document", 1L))
                    .thenReturn(Optional.of(sourceDocument));
            when(projectRepository.findBySlug("target-project"))
                    .thenReturn(Optional.of(targetProject));
            when(documentRepository.findByProjectIdAndTitle(2L, "Source Document"))
                    .thenReturn(Optional.empty());
            when(documentRepository.findByLinkedDocumentsId(1L)).thenReturn(List.of(targetBacklinker));
            when(documentRepository.save(any(Document.class))).thenAnswer(invocation -> invocation.getArgument(0));

            // when
            documentService.moveDocument(1L, "source-document", "target-project");

            // then — target backlinker should still link to sourceDocument (same project after move)
            assertThat(targetBacklinker.getLinkedDocuments()).contains(sourceDocument);
        }
    }

    @Nested
    @DisplayName("copyDocument")
    class CopyDocumentTests {

        private Project targetProject;

        @BeforeEach
        void setUpTargetProject() {
            targetProject = Project.builder()
                    .id(2L)
                    .name("Target Project")
                    .description("A target project for copying documents")
                    .slug("target-project")
                    .createdAt(LocalDateTime.of(2024, 1, 1, 0, 0))
                    .updatedAt(LocalDateTime.of(2024, 1, 1, 0, 0))
                    .build();
        }

        @Test
        @DisplayName("Should copy document to target project with '(copy)' suffix")
        void shouldCopyDocumentToTargetProject() {
            // given
            when(documentRepository.findBySlugAndProjectId("source-document", 1L))
                    .thenReturn(Optional.of(sourceDocument));
            when(projectRepository.findBySlug("target-project"))
                    .thenReturn(Optional.of(targetProject));
            when(documentRepository.findByProjectIdAndTitle(2L, "Source Document (copy)"))
                    .thenReturn(Optional.empty());
            when(documentRepository.save(any(Document.class))).thenAnswer(invocation -> {
                Document doc = invocation.getArgument(0);
                return Document.builder()
                        .id(10L)
                        .title(doc.getTitle())
                        .content(doc.getContent())
                        .slug("source-document-copy")
                        .project(targetProject)
                        .linkedDocuments(new ArrayList<>())
                        .createdAt(LocalDateTime.now())
                        .updatedAt(LocalDateTime.now())
                        .build();
            });

            // when
            DocumentDTO result = documentService.copyDocument(1L, "source-document", "target-project");

            // then
            assertThat(result).isNotNull();
            assertThat(result.getTitle()).isEqualTo("Source Document (copy)");
            assertThat(result.getContent()).isEqualTo("Source content");
            assertThat(result.getProjectId()).isEqualTo(2L);
            assertThat(result.getLinkedDocuments()).isEmpty();
        }

        @Test
        @DisplayName("Should copy document to same project when targetProjectSlug is null")
        void shouldCopyToSameProjectWhenTargetIsNull() {
            // given
            when(documentRepository.findBySlugAndProjectId("source-document", 1L))
                    .thenReturn(Optional.of(sourceDocument));
            when(documentRepository.findByProjectIdAndTitle(1L, "Source Document (copy)"))
                    .thenReturn(Optional.empty());
            when(documentRepository.save(any(Document.class))).thenAnswer(invocation -> {
                Document doc = invocation.getArgument(0);
                return Document.builder()
                        .id(10L)
                        .title(doc.getTitle())
                        .content(doc.getContent())
                        .slug("source-document-copy")
                        .project(testProject)
                        .linkedDocuments(new ArrayList<>())
                        .createdAt(LocalDateTime.now())
                        .updatedAt(LocalDateTime.now())
                        .build();
            });

            // when
            DocumentDTO result = documentService.copyDocument(1L, "source-document", null);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getTitle()).isEqualTo("Source Document (copy)");
            assertThat(result.getProjectId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("Should copy document to same project when targetProjectSlug is blank")
        void shouldCopyToSameProjectWhenTargetIsBlank() {
            // given
            when(documentRepository.findBySlugAndProjectId("source-document", 1L))
                    .thenReturn(Optional.of(sourceDocument));
            when(documentRepository.findByProjectIdAndTitle(1L, "Source Document (copy)"))
                    .thenReturn(Optional.empty());
            when(documentRepository.save(any(Document.class))).thenAnswer(invocation -> {
                Document doc = invocation.getArgument(0);
                return Document.builder()
                        .id(10L)
                        .title(doc.getTitle())
                        .content(doc.getContent())
                        .slug("source-document-copy")
                        .project(testProject)
                        .linkedDocuments(new ArrayList<>())
                        .createdAt(LocalDateTime.now())
                        .updatedAt(LocalDateTime.now())
                        .build();
            });

            // when
            DocumentDTO result = documentService.copyDocument(1L, "source-document", "  ");

            // then
            assertThat(result).isNotNull();
            assertThat(result.getProjectId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("Should increment suffix when '(copy)' already exists")
        void shouldIncrementSuffixWhenCopyExists() {
            // given — "Source Document (copy)" already exists, so it should try "(copy 2)"
            when(documentRepository.findBySlugAndProjectId("source-document", 1L))
                    .thenReturn(Optional.of(sourceDocument));
            when(projectRepository.findBySlug("target-project"))
                    .thenReturn(Optional.of(targetProject));
            when(documentRepository.findByProjectIdAndTitle(2L, "Source Document (copy)"))
                    .thenReturn(Optional.of(targetDocument)); // conflict
            when(documentRepository.findByProjectIdAndTitle(2L, "Source Document (copy 2)"))
                    .thenReturn(Optional.empty()); // free
            when(documentRepository.save(any(Document.class))).thenAnswer(invocation -> {
                Document doc = invocation.getArgument(0);
                return Document.builder()
                        .id(10L)
                        .title(doc.getTitle())
                        .content(doc.getContent())
                        .slug("source-document-copy-2")
                        .project(targetProject)
                        .linkedDocuments(new ArrayList<>())
                        .createdAt(LocalDateTime.now())
                        .updatedAt(LocalDateTime.now())
                        .build();
            });

            // when
            DocumentDTO result = documentService.copyDocument(1L, "source-document", "target-project");

            // then
            assertThat(result.getTitle()).isEqualTo("Source Document (copy 2)");
        }

        @Test
        @DisplayName("Should throw EntityNotFoundException when source document not found")
        void shouldThrowWhenSourceNotFound() {
            // given
            when(documentRepository.findBySlugAndProjectId("non-existent", 1L))
                    .thenReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> documentService.copyDocument(1L, "non-existent", "target-project"))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining("Document not found with slug 'non-existent' in project 1");

            verify(projectRepository, never()).findBySlug(any());
        }

        @Test
        @DisplayName("Should throw EntityNotFoundException when target project not found")
        void shouldThrowWhenTargetProjectNotFound() {
            // given
            when(documentRepository.findBySlugAndProjectId("source-document", 1L))
                    .thenReturn(Optional.of(sourceDocument));
            when(projectRepository.findBySlug("non-existent-project"))
                    .thenReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> documentService.copyDocument(1L, "source-document", "non-existent-project"))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining("Target project not found with slug: non-existent-project");
        }

        @Test
        @DisplayName("Should NOT copy links from source document")
        void shouldNotCopyLinks() {
            // given — source has a link to targetDocument
            sourceDocument.addLinkedDocument(targetDocument);

            when(documentRepository.findBySlugAndProjectId("source-document", 1L))
                    .thenReturn(Optional.of(sourceDocument));
            when(projectRepository.findBySlug("target-project"))
                    .thenReturn(Optional.of(targetProject));
            when(documentRepository.findByProjectIdAndTitle(2L, "Source Document (copy)"))
                    .thenReturn(Optional.empty());
            when(documentRepository.save(any(Document.class))).thenAnswer(invocation -> {
                Document doc = invocation.getArgument(0);
                return Document.builder()
                        .id(10L)
                        .title(doc.getTitle())
                        .content(doc.getContent())
                        .slug("source-document-copy")
                        .project(targetProject)
                        .linkedDocuments(new ArrayList<>()) // no links
                        .createdAt(LocalDateTime.now())
                        .updatedAt(LocalDateTime.now())
                        .build();
            });

            // when
            DocumentDTO result = documentService.copyDocument(1L, "source-document", "target-project");

            // then
            assertThat(result.getLinkedDocuments()).isEmpty();
        }
    }

    @Nested
    @DisplayName("getDocumentsByProjectPaginated")
    class GetDocumentsByProjectPaginatedTests {

        @Test
        @DisplayName("Should return paginated results with correct page metadata")
        void shouldReturnPaginatedResults() {
            // given
            Document doc1 = Document.builder()
                    .id(1L).title("Doc 1").content("Content 1").slug("doc-1")
                    .project(testProject).linkedDocuments(new ArrayList<>())
                    .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                    .build();
            Document doc2 = Document.builder()
                    .id(2L).title("Doc 2").content("Content 2").slug("doc-2")
                    .project(testProject).linkedDocuments(new ArrayList<>())
                    .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                    .build();
            Page<Document> docPage = new PageImpl<>(List.of(doc1, doc2), PageRequest.of(0, 10), 25);

            when(documentRepository.findByProjectIdOrderByUpdatedAtDesc(eq(1L), any(Pageable.class)))
                    .thenReturn(docPage);

            // when
            Page<DocumentSummaryDTO> result = documentService.getDocumentsByProjectPaginated(1L, PageRequest.of(0, 10));

            // then
            assertThat(result).isNotNull();
            assertThat(result.getContent()).hasSize(2);
            assertThat(result.getTotalElements()).isEqualTo(25);
            assertThat(result.getTotalPages()).isEqualTo(3);
            assertThat(result.getNumber()).isEqualTo(0);
            assertThat(result.getSize()).isEqualTo(10);
            assertThat(result.getContent().get(0).getTitle()).isEqualTo("Doc 1");
            assertThat(result.getContent().get(1).getTitle()).isEqualTo("Doc 2");
        }

        @Test
        @DisplayName("Should return empty page when project has no documents")
        void shouldReturnEmptyPageWhenNoDocuments() {
            // given
            Page<Document> emptyPage = new PageImpl<>(List.of(), PageRequest.of(0, 50), 0);
            when(documentRepository.findByProjectIdOrderByUpdatedAtDesc(eq(1L), any(Pageable.class)))
                    .thenReturn(emptyPage);

            // when
            Page<DocumentSummaryDTO> result = documentService.getDocumentsByProjectPaginated(1L, PageRequest.of(0, 50));

            // then
            assertThat(result.getContent()).isEmpty();
            assertThat(result.getTotalElements()).isEqualTo(0);
            assertThat(result.getTotalPages()).isEqualTo(0);
        }

        @Test
        @DisplayName("Should return correct page when requesting page 1 with size 10")
        void shouldReturnSecondPageWithCorrectSize() {
            // given
            Document doc = Document.builder()
                    .id(11L).title("Doc 11").content("Content 11").slug("doc-11")
                    .project(testProject).linkedDocuments(new ArrayList<>())
                    .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                    .build();
            Page<Document> docPage = new PageImpl<>(List.of(doc), PageRequest.of(1, 10), 23);

            when(documentRepository.findByProjectIdOrderByUpdatedAtDesc(eq(1L), any(Pageable.class)))
                    .thenReturn(docPage);

            // when
            Page<DocumentSummaryDTO> result = documentService.getDocumentsByProjectPaginated(1L, PageRequest.of(1, 10));

            // then
            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getNumber()).isEqualTo(1);
            assertThat(result.getSize()).isEqualTo(10);
            assertThat(result.getTotalElements()).isEqualTo(23);
            assertThat(result.isFirst()).isFalse();
        }

        @Test
        @DisplayName("Should NOT include content field in summary DTO")
        void shouldNotIncludeContentFieldInSummary() {
            // given
            Document doc = Document.builder()
                    .id(1L).title("Doc 1").content("This is the full markdown content that should not appear").slug("doc-1")
                    .project(testProject).linkedDocuments(new ArrayList<>())
                    .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                    .build();
            Page<Document> docPage = new PageImpl<>(List.of(doc), PageRequest.of(0, 10), 1);

            when(documentRepository.findByProjectIdOrderByUpdatedAtDesc(eq(1L), any(Pageable.class)))
                    .thenReturn(docPage);

            // when
            Page<DocumentSummaryDTO> result = documentService.getDocumentsByProjectPaginated(1L, PageRequest.of(0, 10));

            // then - verify summary has all fields except content
            DocumentSummaryDTO summary = result.getContent().get(0);
            assertThat(summary.getId()).isEqualTo(1L);
            assertThat(summary.getTitle()).isEqualTo("Doc 1");
            assertThat(summary.getSlug()).isEqualTo("doc-1");
            assertThat(summary.getProjectId()).isEqualTo(1L);
            assertThat(summary.getCreatedAt()).isNotNull();
            assertThat(summary.getUpdatedAt()).isNotNull();
            // The summary DTO simply doesn't have a getContent() method, so content is excluded by design
        }
    }
}
