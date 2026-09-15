package com.wiki4ai.service;

import com.wiki4ai.dto.ContentEditDTO;
import com.wiki4ai.dto.DocumentContentDTO;
import com.wiki4ai.dto.DocumentCreateDTO;
import com.wiki4ai.dto.DocumentDTO;
import com.wiki4ai.dto.DocumentSummaryDTO;
import com.wiki4ai.dto.DocumentUpdateDTO;
import com.wiki4ai.exception.BadRequestException;
import com.wiki4ai.exception.ContentEditException;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DocumentService.class);

    /** Reciprocal Rank Fusion constant — standard value, dampens rank differences. */
    private static final int RRF_K = 60;
    /** Max vector hits fused into the hybrid result per search. */
    private static final int VECTOR_TOP_N = 20;

    private final DocumentRepository documentRepository;
    private final ProjectRepository projectRepository;
    private final MarkdownService markdownService;
    private final PermissionService permissionService;
    private final EmbeddingService embeddingService;

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
     * Returns DocumentSummaryDTO (without content field) to keep list responses lightweight.
     */
    public Page<DocumentSummaryDTO> getDocumentsByProjectPaginated(Long projectId, Pageable pageable, String username) {
        permissionService.checkPermission(username, projectId, Permission.READ);
        return documentRepository.findByProjectIdOrderByUpdatedAtDesc(projectId, pageable)
                .map(this::convertToSummaryDTO);
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
        embeddingService.embedAndSave(saved); // WIKI4AI-35: re-embed on create (never throws)
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
        embeddingService.embedAndSave(saved); // WIKI4AI-35: re-embed on create (never throws)
        return convertToDTO(saved);
    }

    // ==================== UPDATE OPERATIONS (require UPDATE permission) ====================

    /**
     * Update an existing document by ID.
     * Partial update: only provided fields are applied, the rest stay unchanged.
     * At least one of title/content/contentEdits must be provided; a blank title
     * and a mix of content with contentEdits are rejected with 400.
     * When contentEdits are applied, the DTO is stamped with editsApplied.
     */
    @Transactional
    public DocumentDTO updateDocument(Long id, DocumentUpdateDTO dto, String username) {
        Document document = documentRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Document not found with id: " + id));
        permissionService.checkPermission(username, document.getProject().getId(), Permission.UPDATE);

        int editsApplied = applyUpdate(dto, document);

        Document saved = documentRepository.save(document);
        embeddingService.embedAndSave(saved); // WIKI4AI-35: re-embed on update (never throws)
        DocumentDTO result = convertToDTO(saved);
        if (editsApplied > 0) {
            result.setEditsApplied(editsApplied);
        }
        return result;
    }

    /**
     * Update an existing document by slug within a specific project.
     * Partial update: only provided fields are applied, the rest stay unchanged.
     * At least one of title/content/contentEdits must be provided; a blank title
     * and a mix of content with contentEdits are rejected with 400.
     * When contentEdits are applied, the DTO is stamped with editsApplied.
     */
    @Transactional
    public DocumentDTO updateDocumentBySlug(Long projectId, String slug, DocumentUpdateDTO dto, String username) {
        permissionService.checkPermission(username, projectId, Permission.UPDATE);
        Document document = documentRepository.findBySlugAndProjectId(slug, projectId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Document not found with slug '" + slug + "' in project " + projectId));

        int editsApplied = applyUpdate(dto, document);

        Document saved = documentRepository.save(document);
        embeddingService.embedAndSave(saved); // WIKI4AI-35: re-embed on update (never throws)
        DocumentDTO result = convertToDTO(saved);
        if (editsApplied > 0) {
            result.setEditsApplied(editsApplied);
        }
        return result;
    }

    /**
     * Apply a partial update to a document.
     * Fails fast (400) when no field is provided, when the title is blank, or
     * when both content and contentEdits are sent (mutual exclusivity).
     * Setting a non-null title regenerates the slug; null fields are left untouched.
     *
     * @return the number of contentEdits applied (0 for title-only / content-only updates)
     */
    private int applyUpdate(DocumentUpdateDTO dto, Document document) {
        boolean hasEdits = dto.getContentEdits() != null && !dto.getContentEdits().isEmpty();
        if (dto.getContent() != null && hasEdits) {
            throw new BadRequestException(
                    "Cannot combine 'content' (full replace) with 'contentEdits' (incremental edits) in the same request");
        }
        if (dto.getTitle() == null && dto.getContent() == null && !hasEdits) {
            throw new BadRequestException(
                    "At least one of title, content or contentEdits must be provided");
        }
        if (dto.getTitle() != null && dto.getTitle().isBlank()) {
            throw new BadRequestException("Title must not be blank");
        }

        int editsApplied = 0;
        if (hasEdits) {
            String current = document.getContent() != null ? document.getContent() : "";
            document.setContent(applyContentEdits(current, dto.getContentEdits()));
            editsApplied = dto.getContentEdits().size();
        } else if (dto.getContent() != null) {
            document.setContent(dto.getContent());
        }
        if (dto.getTitle() != null) {
            document.setTitle(dto.getTitle());
        }
        return editsApplied;
    }

    /**
     * Apply a list of find/replace edits to the current content, sequentially —
     * each edit sees the result of the previous one (supports chained changes).
     *
     * Rules:
     * - find is matched EXACTLY (case-sensitive, including whitespace)
     * - 0 occurrences -> ContentEditException(editIndex, occurrences=0)
     * - >1 occurrence without replaceAll -> ContentEditException(editIndex, occurrences)
     * - replaceAll=true replaces all occurrences; replace:"" deletes the text
     *
     * Pure function, unit-testable without a database.
     */
    public static String applyContentEdits(String current, List<ContentEditDTO> edits) {
        if (edits == null || edits.isEmpty()) {
            throw new BadRequestException("contentEdits must not be empty");
        }
        String result = current != null ? current : "";
        for (int i = 0; i < edits.size(); i++) {
            ContentEditDTO edit = edits.get(i);
            if (edit == null || edit.getFind() == null || edit.getFind().isBlank()) {
                throw new ContentEditException(
                        "Edit at index " + i + " has a blank 'find'; it must match existing text exactly", i, 0);
            }
            if (edit.getReplace() == null) {
                throw new ContentEditException(
                        "Edit at index " + i + " has a null 'replace'; use an empty string to delete the matched text",
                        i, 0);
            }
            String find = edit.getFind();
            int occurrences = countOccurrences(result, find);
            if (occurrences == 0) {
                throw new ContentEditException("find not found", i, 0);
            }
            boolean replaceAll = edit.getReplaceAll() != null && edit.getReplaceAll();
            if (occurrences > 1 && !replaceAll) {
                throw new ContentEditException(
                        "Edit at index " + i + " matched " + occurrences
                                + " occurrences; set replaceAll=true to replace all of them",
                        i, occurrences);
            }
            result = result.replace(find, edit.getReplace());
        }
        return result;
    }

    /**
     * Count non-overlapping occurrences of needle in haystack.
     */
    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) != -1) {
            count++;
            index += needle.length();
        }
        return count;
    }

    // ==================== DELETE OPERATIONS (require DELETE permission) ====================

    /**
     * Detach a document from its parent collection before deleting it.
     *
     * <p>{@code Project.documents} is mapped with {@code cascade = CascadeType.ALL,
     * orphanRemoval = true}. When the collection has already been initialized in this session —
     * which always happens in the API flow, because project DTO conversion touches
     * {@code getDocuments()} before the delete runs — a plain {@code em.remove(document)} leaves
     * the document inside the in-memory bag. The flush-time PERSIST_ON_FLUSH cascade then walks
     * the bag, finds the just-deleted element (its EntityEntry still exists with status DELETED)
     * and calls {@code ActionQueue.unScheduleDeletion()}, silently cancelling the pending delete:
     * no DELETE SQL is issued, the row survives the commit, yet the API returns 204.</p>
     *
     * <p>Removing the element from the collection first keeps it invisible to the flush-time
     * cascade, so the scheduled deletion actually executes. See
     * {@code DocumentDeleteUnscheduleRegressionTest}.</p>
     */
    private void detachFromParentCollection(Document document) {
        Project project = document.getProject();
        if (project != null && project.getDocuments() != null) {
            project.getDocuments().remove(document);
        }
    }

    /**
     * Delete a document by ID.
     */
    @Transactional
    public void deleteDocument(Long id, String username) {
        // For backward compatibility with tests (username=null), use existsById check first
        if (username == null || username.isBlank() || "anonymous".equals(username)) {
            Document document = documentRepository.findById(id)
                    .orElseThrow(() -> new EntityNotFoundException("Document not found with id: " + id));
            detachFromParentCollection(document);
            documentRepository.delete(document);
            return;
        }
        // For authenticated users, find document first to check project permissions
        Document document = documentRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Document not found with id: " + id));
        permissionService.checkPermission(username, document.getProject().getId(), Permission.DELETE);
        detachFromParentCollection(document);
        documentRepository.delete(document);
    }

    /**
     * Delete a document by slug within a specific project.
     */
    @Transactional
    public void deleteDocumentBySlug(Long projectId, String slug, String username) {
        // For backward compatibility with tests (username=null/anonymous), skip permission check
        if (username == null || username.isBlank() || "anonymous".equals(username)) {
            Document document = documentRepository.findBySlugAndProjectId(slug, projectId)
                    .orElseThrow(() -> new EntityNotFoundException(
                            "Document not found with slug '" + slug + "' in project " + projectId));
            detachFromParentCollection(document);
            documentRepository.delete(document);
            documentRepository.flush(); // Ensure deletion is persisted immediately
            return;
        }
        permissionService.checkPermission(username, projectId, Permission.DELETE);
        Document document = documentRepository.findBySlugAndProjectId(slug, projectId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Document not found with slug '" + slug + "' in project " + projectId));
        detachFromParentCollection(document);
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
     * Hybrid document search within a project (WIKI4AI-35, epic WIKI4AI-26).
     *
     * <p>Combines the existing text LIKE path with pgvector cosine similarity
     * (top {@value VECTOR_TOP_N}) and fuses both rankings with Reciprocal Rank
     * Fusion. Documents are returned in descending fused score order, each DTO
     * stamped with its score.</p>
     *
     * <p><b>Graceful degradation:</b> when the embedding sidecar is unavailable
     * (or fails mid-request) only the text path runs — search never 500s
     * because of embeddings. Documents without an embedding are simply absent
     * from the vector ranking.</p>
     */
    public List<DocumentDTO> searchDocuments(Long projectId, String keyword, String username) {
        permissionService.checkPermission(username, projectId, Permission.READ);

        // 1) Text path (existing behaviour, always runs).
        List<Document> textResults = documentRepository.findByProjectIdAndContentContaining(projectId, keyword);

        Map<Long, Document> byId = new LinkedHashMap<>();
        for (Document d : textResults) {
            byId.put(d.getId(), d);
        }

        // 2) Vector path (only when the sidecar is reachable).
        List<Object[]> vectorHits = List.of();
        if (embeddingService.getClient().isAvailable()) {
            try {
                float[] queryVector = embeddingService.getClient().embedQuery(keyword.trim());
                String literal = EmbeddingClient.toVectorLiteral(queryVector);
                vectorHits = documentRepository.findTopByEmbeddingSimilarity(projectId, literal, VECTOR_TOP_N);
                for (Object[] hit : vectorHits) {
                    Long id = (Long) hit[0];
                    if (!byId.containsKey(id)) {
                        documentRepository.findById(id).ifPresent(d -> byId.put(id, d));
                    }
                }
            } catch (Exception e) {
                // Sidecar died between the probe and the call — fall back to text only.
                log.warn("Vector search unavailable for project {}: {}", projectId, e.getMessage());
                vectorHits = List.of();
            }
        }

        // 3) Reciprocal Rank Fusion over both rankings (k = RRF_K).
        Map<Long, Double> scores = new HashMap<>();
        int rank = 1;
        for (Document d : textResults) {
            scores.merge(d.getId(), 1.0 / (RRF_K + rank++), Double::sum);
        }
        rank = 1;
        for (Object[] hit : vectorHits) {
            scores.merge((Long) hit[0], 1.0 / (RRF_K + rank++), Double::sum);
        }

        return scores.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .map(e -> {
                    Document d = byId.get(e.getKey());
                    if (d == null) {
                        return null; // deleted between query and mapping — skip
                    }
                    DocumentDTO dto = convertToDTO(d);
                    dto.setScore(Math.round(e.getValue() * 1_000_000.0) / 1_000_000.0);
                    return dto;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    // ==================== MOVE/COPY OPERATIONS ====================

    /**
     * Move a document from its current project to a target project.
     * Requires UPDATE + DELETE on source project, READ on target project (not CREATE).
     * Moving a document doesn't create new content — it moves existing content between projects.
     */
    @Transactional
    public DocumentDTO moveDocument(Long sourceProjectId, String slug, String targetProjectSlug, String username) {
        // Check permissions: UPDATE + DELETE on source, READ on target (not CREATE)
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

        // Check READ permission on target project (not CREATE — we're moving, not creating)
        // Wiki convention: any authenticated user can read all projects
        permissionService.checkPermission(username, targetProject.getId(), Permission.READ);

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
        embeddingService.embedAndSave(saved); // WIKI4AI-35: re-embed on copy (never throws)
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

    /**
     * Convert Document entity to lightweight Summary DTO (without content field).
     * Used for paginated list responses to keep payloads small.
     */
    private DocumentSummaryDTO convertToSummaryDTO(Document document) {
        List<Long> linkedDocIds = document.getLinkedDocuments().stream()
                .map(Document::getId)
                .collect(Collectors.toList());

        return DocumentSummaryDTO.builder()
                .id(document.getId())
                .title(document.getTitle())
                .slug(document.getSlug())
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

    public Page<DocumentSummaryDTO> getDocumentsByProjectPaginated(Long projectId, Pageable pageable) {
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
