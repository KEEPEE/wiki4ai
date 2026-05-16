package com.wiki4ai.service;

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
     * Convert Document entity to DTO.
     */
    private DocumentDTO convertToDTO(Document document) {
        List<Long> linkedDocIds = document.getLinkedDocuments().stream()
                .map(Document::getId)
                .collect(Collectors.toList());

        return DocumentDTO.builder()
                .id(document.getId())
                .title(document.getTitle())
                .content(document.getContent())
                .projectId(document.getProject().getId())
                .linkedDocuments(linkedDocIds)
                .createdAt(document.getCreatedAt())
                .updatedAt(document.getUpdatedAt())
                .build();
    }
}
