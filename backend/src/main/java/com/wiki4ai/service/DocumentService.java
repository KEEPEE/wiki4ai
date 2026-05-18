package com.wiki4ai.service;

import com.wiki4ai.dto.DocumentContentDTO;
import com.wiki4ai.dto.DocumentCreateDTO;
import com.wiki4ai.dto.DocumentDTO;
import com.wiki4ai.dto.DocumentUpdateDTO;
import com.wiki4ai.model.Document;
import com.wiki4ai.model.Project;
import com.wiki4ai.repository.DocumentRepository;
import com.wiki4ai.repository.ProjectRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service layer for Document business logic.
 * Handles CRUD operations, document linking, and content management.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DocumentService {

    private final DocumentRepository documentRepository;
    private final ProjectRepository projectRepository;
    private final MarkdownService markdownService;

    /**
     * Get all documents in a project.
     *
     * @param projectId the project ID
     * @return list of DocumentDTOs
     */
    public List<DocumentDTO> getDocumentsByProject(Long projectId) {
        return documentRepository.findByProjectId(projectId)
                .stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    /**
     * Get a single document by ID.
     *
     * @param id the document ID
     * @return DocumentDTO
     * @throws EntityNotFoundException if document not found
     */
    public DocumentDTO getDocumentById(Long id) {
        Document document = documentRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Document not found with id: " + id));
        return convertToDTO(document);
    }

    /**
     * Get a single document by slug within a specific project.
     * Validates that the document belongs to the specified project.
     *
     * @param projectId the project ID
     * @param slug      the document slug
     * @return DocumentDTO
     * @throws EntityNotFoundException if document not found or doesn't belong to project
     */
    public DocumentDTO getDocument(Long projectId, String slug) {
        Document document = documentRepository.findBySlugAndProjectId(slug, projectId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Document not found with slug '" + slug + "' in project " + projectId));
        return convertToDTO(document);
    }

    /**
     * Create a new document in a project.
     * Validates that the project exists and title is unique within the project.
     *
     * @param projectId the parent project ID
     * @param dto       the document data transfer object
     * @return created DocumentDTO
     */
    @Transactional
    public DocumentDTO createDocument(Long projectId, DocumentCreateDTO dto) {
        // Validate that the project exists
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new EntityNotFoundException("Project not found with id: " + projectId));

        // Validate title uniqueness within project
        if (documentRepository.findByProjectIdAndTitle(projectId, dto.getTitle()).isPresent()) {
            throw new IllegalArgumentException(
                    "A document with this title already exists in the project");
        }

        Document document = Document.builder()
                .title(dto.getTitle())
                .content(dto.getContent())
                .project(project)
                .build();

        Document saved = documentRepository.save(document);
        return convertToDTO(saved);
    }

    /**
     * Update an existing document by ID.
     *
     * @param id  the document ID
     * @param dto the updated document data
     * @return updated DocumentDTO
     * @throws EntityNotFoundException if document not found
     */
    @Transactional
    public DocumentDTO updateDocument(Long id, DocumentUpdateDTO dto) {
        Document document = documentRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Document not found with id: " + id));

        document.setTitle(dto.getTitle());
        document.setContent(dto.getContent());

        Document saved = documentRepository.save(document);
        return convertToDTO(saved);
    }

    /**
     * Update an existing document by slug within a specific project.
     * Validates that the document belongs to the specified project.
     *
     * @param projectId the project ID
     * @param slug      the document slug
     * @param dto       the updated document data
     * @return updated DocumentDTO
     * @throws EntityNotFoundException if document not found or doesn't belong to project
     */
    @Transactional
    public DocumentDTO updateDocumentBySlug(Long projectId, String slug, DocumentUpdateDTO dto) {
        Document document = documentRepository.findBySlugAndProjectId(slug, projectId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Document not found with slug '" + slug + "' in project " + projectId));

        document.setTitle(dto.getTitle());
        document.setContent(dto.getContent());

        Document saved = documentRepository.save(document);
        return convertToDTO(saved);
    }

    /**
     * Delete a document by ID.
     *
     * @param id the document ID
     * @throws EntityNotFoundException if document not found
     */
    @Transactional
    public void deleteDocument(Long id) {
        if (!documentRepository.existsById(id)) {
            throw new EntityNotFoundException("Document not found with id: " + id);
        }
        documentRepository.deleteById(id);
    }

    /**
     * Delete a document by slug within a specific project.
     * Validates that the document belongs to the specified project.
     *
     * @param projectId the project ID
     * @param slug      the document slug
     * @throws EntityNotFoundException if document not found or doesn't belong to project
     */
    @Transactional
    public void deleteDocumentBySlug(Long projectId, String slug) {
        Document document = documentRepository.findBySlugAndProjectId(slug, projectId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Document not found with slug '" + slug + "' in project " + projectId));
        documentRepository.delete(document);
        documentRepository.flush(); // Ensure deletion is persisted immediately
    }

    /**
     * Search documents by keyword within a project.
     *
     * @param projectId the project ID
     * @param keyword   the search keyword
     * @return list of matching DocumentDTOs
     */
    public List<DocumentDTO> searchDocuments(Long projectId, String keyword) {
        return documentRepository.findByProjectIdAndContentContaining(projectId, keyword)
                .stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    /**
     * Upload a markdown file and create a document from it.
     * The filename (without extension) is used as the document title.
     * The file content is used as the document content.
     *
     * @param projectId the parent project ID
     * @param filename  the original filename of the uploaded file
     * @param content   the raw text content of the file
     * @return created DocumentDTO
     * @throws IllegalArgumentException if title already exists in the project
     */
    @Transactional
    public DocumentDTO uploadDocument(Long projectId, String filename, String content) {
        // Extract title from filename (remove .md or .markdown extension)
        String title = extractTitleFromFilename(filename);

        // Validate that the project exists
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new EntityNotFoundException("Project not found with id: " + projectId));

        // Validate title uniqueness within project
        if (documentRepository.findByProjectIdAndTitle(projectId, title).isPresent()) {
            throw new IllegalArgumentException(
                    "A document with this title already exists in the project");
        }

        Document document = Document.builder()
                .title(title)
                .content(content)
                .project(project)
                .build();

        Document saved = documentRepository.save(document);
        return convertToDTO(saved);
    }

    /**
     * Extract a document title from an uploaded filename.
     * Removes the file extension (.md or .markdown) and returns the base name.
     * If the filename has no recognized extension, the full filename is returned as-is.
     *
     * @param filename the original filename (e.g. "My Document.md")
     * @return the extracted title (e.g. "My Document")
     */
    public static String extractTitleFromFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "";
        }

        String name = filename;
        int lastDot = name.lastIndexOf('.');
        if (lastDot > 0) {
            String extension = name.substring(lastDot + 1).toLowerCase();
            if ("md".equals(extension) || "markdown".equals(extension)) {
                name = name.substring(0, lastDot);
            }
        }

        // Trim whitespace from the result
        return name.trim();
    }

    /**
     * Add a link between two documents.
     * Validates that both documents exist and belong to the same project.
     * Prevents self-linking and duplicate links.
     *
     * @param sourceDocId the source document ID
     * @param targetDocId the target document ID
     * @return the updated source Document with the link added
     * @throws EntityNotFoundException if either document not found or they don't belong to same project
     */
    @Transactional
    public DocumentDTO addLink(Long sourceDocId, Long targetDocId) {
        // Validate documents exist
        Document source = documentRepository.findById(sourceDocId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Source document not found with id: " + sourceDocId));

        Document target = documentRepository.findById(targetDocId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Target document not found with id: " + targetDocId));

        // Prevent self-linking
        if (sourceDocId.equals(targetDocId)) {
            throw new IllegalArgumentException("Cannot link a document to itself");
        }

        // Validate both documents belong to the same project
        Long sourceProjectId = source.getProject().getId();
        Long targetProjectId = target.getProject().getId();
        if (!sourceProjectId.equals(targetProjectId)) {
            throw new IllegalArgumentException(
                    "Documents must belong to the same project. Source project: " + sourceProjectId
                            + ", Target project: " + targetProjectId);
        }

        // Prevent duplicate links
        if (source.getLinkedDocuments().contains(target)) {
            throw new IllegalArgumentException("Link already exists between these documents");
        }

        source.addLinkedDocument(target);
        Document saved = documentRepository.save(source);
        return convertToDTO(saved);
    }

    /**
     * Remove a link between two documents.
     * Validates that both documents exist and the link exists.
     *
     * @param sourceDocId the source document ID
     * @param targetDocId the target document ID
     * @throws EntityNotFoundException if either document not found or link doesn't exist
     */
    @Transactional
    public void removeLink(Long sourceDocId, Long targetDocId) {
        Document source = documentRepository.findById(sourceDocId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Source document not found with id: " + sourceDocId));

        Document target = documentRepository.findById(targetDocId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Target document not found with id: " + targetDocId));

        if (!source.getLinkedDocuments().contains(target)) {
            throw new IllegalArgumentException("Link does not exist between these documents");
        }

        source.removeLinkedDocument(target);
        documentRepository.save(source);
    }

    /**
     * Get all documents linked from a specific document.
     *
     * @param docId the document ID
     * @return list of linked DocumentDTOs
     * @throws EntityNotFoundException if document not found
     */
    public List<DocumentDTO> getLinkedDocuments(Long docId) {
        Document document = documentRepository.findById(docId)
                .orElseThrow(() -> new EntityNotFoundException("Document not found with id: " + docId));

        return document.getLinkedDocuments()
                .stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    /**
     * Get all documents that link TO a specific document (backlinks/reverse links).
     * Returns documents where the given document appears in their linkedDocuments collection.
     * Validates that the target document exists before querying backlinks.
     *
     * @param docId the document ID to find backlinks for
     * @return list of DocumentDTOs that have a link pointing to this document
     * @throws EntityNotFoundException if the target document not found
     */
    public List<DocumentDTO> getBacklinks(Long docId) {
        // Validate that the target document exists
        documentRepository.findById(docId)
                .orElseThrow(() -> new EntityNotFoundException("Document not found with id: " + docId));

        return documentRepository.findByLinkedDocumentsId(docId)
                .stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    /**
     * Get document content with rendered HTML, extracted wiki links, and linked documents.
     * Returns the full document content processed through markdown rendering with wiki link replacement.
     *
     * @param projectId the project ID
     * @param slug      the document slug
     * @return DocumentContentDTO with rendered HTML and link information
     * @throws EntityNotFoundException if document not found or doesn't belong to project
     */
    public DocumentContentDTO getDocumentContent(Long projectId, String slug) {
        Document document = documentRepository.findBySlugAndProjectId(slug, projectId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Document not found with slug '" + slug + "' in project " + projectId));

        // Extract wiki links from raw content
        List<String> wikiLinks = markdownService.extractWikiLinks(document.getContent());

        // Build a map of document titles to slugs for wiki link URL resolution
        Map<String, String> titleToSlug = documentRepository.findByProjectId(projectId).stream()
                .collect(Collectors.toMap(
                        d -> d.getTitle().toLowerCase(),
                        Document::getSlug,
                        (existing, replacement) -> existing
                ));

        // Render markdown with wiki links replaced by proper anchor tags
        String htmlContent = markdownService.renderWithWikiLinks(document.getContent(), "", titleToSlug);

        // Get linked documents as DTOs
        List<DocumentDTO> linkedDocuments = document.getLinkedDocuments().stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());

        return new DocumentContentDTO(
                document.getId(),
                document.getTitle(),
                htmlContent,
                wikiLinks,
                linkedDocuments
        );
    }

    /**
     * Move a document from its current project to a target project.
     * Changes the document's project reference and removes all cross-project links.
     * Validates that both projects exist, they are different, and title is unique in the target project.
     *
     * @param sourceProjectId   the ID of the source project (where the document currently lives)
     * @param slug              the document slug
     * @param targetProjectSlug the slug of the target project to move the document into
     * @return updated DocumentDTO with new project assignment
     * @throws EntityNotFoundException if source document, source project, or target project not found
     * @throws IllegalArgumentException if source and target are the same project
     *                                  or title already exists in target project
     */
    @Transactional
    public DocumentDTO moveDocument(Long sourceProjectId, String slug, String targetProjectSlug) {
        // Find the document in the source project
        Document document = documentRepository.findBySlugAndProjectId(slug, sourceProjectId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Document not found with slug '" + slug + "' in project " + sourceProjectId));

        // Find the target project by slug
        Project targetProject = projectRepository.findBySlug(targetProjectSlug)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Target project not found with slug: " + targetProjectSlug));

        // Cannot move to the same project
        if (sourceProjectId.equals(targetProject.getId())) {
            throw new IllegalArgumentException(
                    "Cannot move document to the same project it already belongs to");
        }

        // Check title uniqueness in target project
        if (documentRepository.findByProjectIdAndTitle(targetProject.getId(), document.getTitle()).isPresent()) {
            throw new IllegalArgumentException(
                    "A document with this title already exists in the target project");
        }

        // Remove all links — they become cross-project after the move
        document.getLinkedDocuments().clear();

        // Also remove incoming links from documents still in the source project
        List<Document> backlinkers = documentRepository.findByLinkedDocumentsId(document.getId());
        for (Document backlinker : backlinkers) {
            if (backlinker.getProject().getId().equals(sourceProjectId)) {
                backlinker.removeLinkedDocument(document);
                documentRepository.save(backlinker);
            }
        }

        // Assign to target project
        document.setProject(targetProject);
        Document saved = documentRepository.save(document);
        return convertToDTO(saved);
    }

    /**
     * Convert Document entity to DTO.
     */
    private DocumentDTO convertToDTO(Document document) {
        List<Long> linkedDocIds = document.getLinkedDocuments().stream()
                .map(Document::getId)
                .collect(Collectors.toList());

        return DocumentDTO.builder()
                .id(document.getId())
                .title(document.getTitle())
                .slug(document.getSlug())
                .content(document.getContent())
                .projectId(document.getProject().getId())
                .linkedDocuments(linkedDocIds)
                .createdAt(document.getCreatedAt())
                .updatedAt(document.getUpdatedAt())
                .build();
    }
}
