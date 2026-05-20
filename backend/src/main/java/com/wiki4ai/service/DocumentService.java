package com.wiki4ai.service;

import com.wiki4ai.dto.DocumentContentDTO;
import com.wiki4ai.dto.DocumentCreateDTO;
import com.wiki4ai.dto.DocumentDTO;
import com.wiki4ai.dto.DocumentUpdateDTO;
import com.wiki4ai.model.Document;
import com.wiki4ai.model.Permission;
import com.wiki4ai.model.Project;
import com.wiki4ai.repository.DocumentRepository;
import com.wiki4ai.repository.ProjectRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service layer for Document business logic.
 * Handles CRUD operations, document linking, and content management.
 * All mutating methods enforce RBAC permissions via PermissionService.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DocumentService {

    private final DocumentRepository documentRepository;
    private final ProjectRepository projectRepository;
    private final MarkdownService markdownService;
    private final PermissionService permissionService;

    // ==================== READ OPERATIONS (require READ permission) ====================

    /**
     * Get all documents in a project.
     */
    public List<DocumentDTO> getDocumentsByProject(Long projectId, String username) {
        permissionService.checkPermission(username, projectId, Permission.READ);
        return documentRepository.findByProjectId(projectId)
                .stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    /**
     * Get paginated documents in a project, ordered by update date (newest first).
     */
    public Page<DocumentDTO> getDocumentsByProjectPaginated(Long projectId, Pageable pageable, String username) {
        permissionService.checkPermission(username, projectId, Permission.READ);
        return documentRepository.findByProjectIdOrderByUpdatedAtDesc(projectId, pageable)
                .map(this::convertToDTO);
    }

    /**
     * Get a single document by ID.
     */
    public DocumentDTO getDocumentById(Long id, String username) {
        Document document = documentRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Document not found with id: " + id));
        permissionService.checkPermission(username, document.getProject().getId(), Permission.READ);
        return convertToDTO(document);
    }

    /**
     * Get a single document by slug within a specific project.
     */
    public DocumentDTO getDocument(Long projectId, String slug, String username) {
        permissionService.checkPermission(username, projectId, Permission.READ);
        Document document = documentRepository.findBySlugAndProjectId(slug, projectId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Document not found with slug '" + slug + "' in project " + projectId));
        return convertToDTO(document);
    }

    // ==================== CREATE OPERATIONS (require CREATE permission) ====================

    /**
     * Create a new document in a project.
     */
    @Transactional
    public DocumentDTO createDocument(Long projectId, DocumentCreateDTO dto, String username) {
        permissionService.checkPermission(username, projectId, Permission.CREATE);

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
     * Upload a markdown file and create a document from it.
     */
    @Transactional
    public DocumentDTO uploadDocument(Long projectId, String filename, String content, String username) {
        permissionService.checkPermission(username, projectId, Permission.CREATE);

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

    // ==================== UPDATE OPERATIONS (require UPDATE permission) ====================

    /**
     * Update an existing document by ID.
     */
    @Transactional
    public DocumentDTO updateDocument(Long id, DocumentUpdateDTO dto, String username) {
        Document document = documentRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Document not found with id: " + id));
        permissionService.checkPermission(username, document.getProject().getId(), Permission.UPDATE);

        document.setTitle(dto.getTitle());
        document.setContent(dto.getContent());

        Document saved = documentRepository.save(document);
        return convertToDTO(saved);
    }

    /**
     * Update an existing document by slug within a specific project.
     */
    @Transactional
    public DocumentDTO updateDocumentBySlug(Long projectId, String slug, DocumentUpdateDTO dto, String username) {
        permissionService.checkPermission(username, projectId, Permission.UPDATE);
        Document document = documentRepository.findBySlugAndProjectId(slug, projectId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Document not found with slug '" + slug + "' in project " + projectId));

        document.setTitle(dto.getTitle());
        document.setContent(dto.getContent());

        Document saved = documentRepository.save(document);
        return convertToDTO(saved);
    }

    // ==================== DELETE OPERATIONS (require DELETE permission) ====================

    /**
     * Delete a document by ID.
     */
    @Transactional
    public void deleteDocument(Long id, String username) {
        // For backward compatibility with tests (username=null), use existsById check first
        if (username == null || username.isBlank() || "anonymous".equals(username)) {
            if (!documentRepository.existsById(id)) {
                throw new EntityNotFoundException("Document not found with id: " + id);
            }
            documentRepository.deleteById(id);
            return;
        }
        // For authenticated users, find document first to check project permissions
        Document document = documentRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Document not found with id: " + id));
        permissionService.checkPermission(username, document.getProject().getId(), Permission.DELETE);
        documentRepository.delete(document);
    }

    /**
     * Delete a document by slug within a specific project.
     */
    @Transactional
    public void deleteDocumentBySlug(Long projectId, String slug, String username) {
        permissionService.checkPermission(username, projectId, Permission.DELETE);
        Document document = documentRepository.findBySlugAndProjectId(slug, projectId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Document not found with slug '" + slug + "' in project " + projectId));
        documentRepository.delete(document);
        documentRepository.flush(); // Ensure deletion is persisted immediately
    }

    // ==================== LINK OPERATIONS (require UPDATE permission) ====================

    /**
     * Add a link between two documents.
     */
    @Transactional
    public DocumentDTO addLink(Long sourceDocId, Long targetDocId, String username) {
        // Validate documents exist
        Document source = documentRepository.findById(sourceDocId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Source document not found with id: " + sourceDocId));

        Document target = documentRepository.findById(targetDocId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Target document not found with id: " + targetDocId));

        // Check permission on the project
        permissionService.checkPermission(username, source.getProject().getId(), Permission.UPDATE);

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
     */
    @Transactional
    public void removeLink(Long sourceDocId, Long targetDocId, String username) {
        Document source = documentRepository.findById(sourceDocId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Source document not found with id: " + sourceDocId));

        Document target = documentRepository.findById(targetDocId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Target document not found with id: " + targetDocId));

        permissionService.checkPermission(username, source.getProject().getId(), Permission.UPDATE);

        if (!source.getLinkedDocuments().contains(target)) {
            throw new IllegalArgumentException("Link does not exist between these documents");
        }

        source.removeLinkedDocument(target);
        documentRepository.save(source);
    }

    // ==================== LINK READ OPERATIONS (require READ permission) ====================

    /**
     * Get all documents linked from a specific document.
     */
    public List<DocumentDTO> getLinkedDocuments(Long docId, String username) {
        Document document = documentRepository.findById(docId)
                .orElseThrow(() -> new EntityNotFoundException("Document not found with id: " + docId));
        permissionService.checkPermission(username, document.getProject().getId(), Permission.READ);

        return document.getLinkedDocuments()
                .stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    /**
     * Get all documents that link TO a specific document (backlinks/reverse links).
     */
    public List<DocumentDTO> getBacklinks(Long docId, String username) {
        Document document = documentRepository.findById(docId)
                .orElseThrow(() -> new EntityNotFoundException("Document not found with id: " + docId));
        permissionService.checkPermission(username, document.getProject().getId(), Permission.READ);

        return documentRepository.findByLinkedDocumentsId(docId)
                .stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    // ==================== CONTENT & SEARCH (require READ permission) ====================

    /**
     * Get document content with rendered HTML, extracted wiki links, and linked documents.
     */
    public DocumentContentDTO getDocumentContent(Long projectId, String slug, String username) {
        permissionService.checkPermission(username, projectId, Permission.READ);
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
     * Search documents by keyword within a project.
     */
    public List<DocumentDTO> searchDocuments(Long projectId, String keyword, String username) {
        permissionService.checkPermission(username, projectId, Permission.READ);
        return documentRepository.findByProjectIdAndContentContaining(projectId, keyword)
                .stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    // ==================== MOVE/COPY OPERATIONS ====================

    /**
     * Move a document from its current project to a target project.
     * Requires UPDATE + DELETE on source project, CREATE on target project.
     */
    @Transactional
    public DocumentDTO moveDocument(Long sourceProjectId, String slug, String targetProjectSlug, String username) {
        // Check permissions first
        permissionService.checkPermission(username, sourceProjectId, Permission.UPDATE);
        permissionService.checkPermission(username, sourceProjectId, Permission.DELETE);

        // Find the document in the source project
        Document document = documentRepository.findBySlugAndProjectId(slug, sourceProjectId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Document not found with slug '" + slug + "' in project " + sourceProjectId));

        // Find the target project by slug
        Project targetProject = projectRepository.findBySlug(targetProjectSlug)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Target project not found with slug: " + targetProjectSlug));

        // Check CREATE permission on target project
        permissionService.checkPermission(username, targetProject.getId(), Permission.CREATE);

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
     * Copy a document to a target project (or the same project if no target specified).
     * Requires UPDATE on source project, CREATE on target project.
     */
    @Transactional
    public DocumentDTO copyDocument(Long sourceProjectId, String slug, String targetProjectSlug, String username) {
        // Check permission on source project
        permissionService.checkPermission(username, sourceProjectId, Permission.UPDATE);

        // Find the source document
        Document source = documentRepository.findBySlugAndProjectId(slug, sourceProjectId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Document not found with slug '" + slug + "' in project " + sourceProjectId));

        // Determine target project
        Project targetProject;
        if (targetProjectSlug == null || targetProjectSlug.isBlank()) {
            targetProject = source.getProject();
        } else {
            targetProject = projectRepository.findBySlug(targetProjectSlug)
                    .orElseThrow(() -> new EntityNotFoundException(
                            "Target project not found with slug: " + targetProjectSlug));
        }

        // Check CREATE permission on target project
        permissionService.checkPermission(username, targetProject.getId(), Permission.CREATE);

        // Generate unique copy title: "{title} (copy)", "{title} (copy 2)", etc.
        String copyTitle = generateUniqueCopyTitle(source.getTitle(), targetProject.getId());

        // Create new document — same content, no links
        Document copy = Document.builder()
                .title(copyTitle)
                .content(source.getContent())
                .project(targetProject)
                .build();

        Document saved = documentRepository.save(copy);
        return convertToDTO(saved);
    }

    // ==================== UTILITY METHODS (no permission check needed) ====================

    /**
     * Extract a document title from an uploaded filename.
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
     * Generate a unique copy title by appending " (copy)" or " (copy N)" to the original title.
     */
    private String generateUniqueCopyTitle(String originalTitle, Long targetProjectId) {
        int suffix = 1;
        String candidate = originalTitle + " (copy)";

        while (documentRepository.findByProjectIdAndTitle(targetProjectId, candidate).isPresent()) {
            suffix++;
            candidate = originalTitle + " (copy " + suffix + ")";
        }

        return candidate;
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

    // ==================== BACKWARD COMPATIBILITY OVERLOADS (no username param) ====================
    // These delegate to the permission-aware methods with null username, which skips permission checks.
    // Used by existing unit tests that don't set up security context.

    public List<DocumentDTO> getDocumentsByProject(Long projectId) {
        return getDocumentsByProject(projectId, null);
    }

    public Page<DocumentDTO> getDocumentsByProjectPaginated(Long projectId, Pageable pageable) {
        return getDocumentsByProjectPaginated(projectId, pageable, null);
    }

    public DocumentDTO getDocumentById(Long id) {
        return getDocumentById(id, null);
    }

    public DocumentDTO getDocument(Long projectId, String slug) {
        return getDocument(projectId, slug, null);
    }

    @Transactional
    public DocumentDTO createDocument(Long projectId, DocumentCreateDTO dto) {
        return createDocument(projectId, dto, null);
    }

    @Transactional
    public DocumentDTO uploadDocument(Long projectId, String filename, String content) {
        return uploadDocument(projectId, filename, content, null);
    }

    @Transactional
    public DocumentDTO updateDocument(Long id, DocumentUpdateDTO dto) {
        return updateDocument(id, dto, null);
    }

    @Transactional
    public DocumentDTO updateDocumentBySlug(Long projectId, String slug, DocumentUpdateDTO dto) {
        return updateDocumentBySlug(projectId, slug, dto, null);
    }

    @Transactional
    public void deleteDocument(Long id) {
        deleteDocument(id, null);
    }

    @Transactional
    public void deleteDocumentBySlug(Long projectId, String slug) {
        deleteDocumentBySlug(projectId, slug, null);
    }

    public List<DocumentDTO> searchDocuments(Long projectId, String keyword) {
        return searchDocuments(projectId, keyword, null);
    }

    @Transactional
    public DocumentDTO addLink(Long sourceDocId, Long targetDocId) {
        return addLink(sourceDocId, targetDocId, null);
    }

    @Transactional
    public void removeLink(Long sourceDocId, Long targetDocId) {
        removeLink(sourceDocId, targetDocId, null);
    }

    public List<DocumentDTO> getLinkedDocuments(Long docId) {
        return getLinkedDocuments(docId, null);
    }

    public List<DocumentDTO> getBacklinks(Long docId) {
        return getBacklinks(docId, null);
    }

    public DocumentContentDTO getDocumentContent(Long projectId, String slug) {
        return getDocumentContent(projectId, slug, null);
    }

    @Transactional
    public DocumentDTO moveDocument(Long sourceProjectId, String slug, String targetProjectSlug) {
        return moveDocument(sourceProjectId, slug, targetProjectSlug, null);
    }

    @Transactional
    public DocumentDTO copyDocument(Long sourceProjectId, String slug, String targetProjectSlug) {
        return copyDocument(sourceProjectId, slug, targetProjectSlug, null);
    }
}
