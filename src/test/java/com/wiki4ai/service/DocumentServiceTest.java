package com.wiki4ai.service;

import com.wiki4ai.dto.DocumentDTO;
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

    @InjectMocks
    private DocumentService documentService;

    private Project testProject;
    private Document sourceDocument;
    private Document targetDocument;
    private DocumentDTO validDocumentDto;

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

        validDocumentDto = DocumentDTO.builder()
                .title("New Document")
                .content("Some content here")
                .projectId(1L)
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

            DocumentDTO duplicateDto = DocumentDTO.builder()
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
            DocumentDTO updateDto = DocumentDTO.builder()
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
            DocumentDTO updateDto = DocumentDTO.builder().title("New").build();

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
            DocumentDTO updateDto = DocumentDTO.builder()
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
            DocumentDTO updateDto = DocumentDTO.builder().title("New").build();
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
            Document result = documentService.addLink(1L, 2L);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getLinkedDocuments()).contains(targetDocument);
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
}
