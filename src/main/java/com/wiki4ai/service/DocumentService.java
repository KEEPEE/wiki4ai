package com.wiki4ai.service;

import com.wiki4ai.dto.DocumentDTO;
import com.wiki4ai.model.Document;
import com.wiki4ai.model.Project;
import com.wiki4ai.repository.DocumentRepository;
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
     * Create a new document in a project.
     *
     * @param projectId the parent project ID
     * @param dto       the document data transfer object
     * @return created DocumentDTO
     */
    @Transactional
    public DocumentDTO createDocument(Long projectId, DocumentDTO dto) {
        // Validate title uniqueness within project
        if (documentRepository.findByProjectIdAndTitle(projectId, dto.getTitle()).isPresent()) {
            throw new IllegalArgumentException("A document with this title already exists in the project");
        }

        Document document = Document.builder()
                .title(dto.getTitle())
                .content(dto.getContent())
                .project(Project.builder().id(projectId).build())
                .build();

        Document saved = documentRepository.save(document);
        return convertToDTO(saved);
    }

    /**
     * Update an existing document.
     *
     * @param id  the document ID
     * @param dto the updated document data
     * @return updated DocumentDTO
     * @throws EntityNotFoundException if document not found
     */
    @Transactional
    public DocumentDTO updateDocument(Long id, DocumentDTO dto) {
        Document document = documentRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Document not found with id: " + id));

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
     * Convert Document entity to DTO.
     */
    private DocumentDTO convertToDTO(Document document) {
        return DocumentDTO.builder()
                .id(document.getId())
                .title(document.getTitle())
                .content(document.getContent())
                .projectId(document.getProject().getId())
                .createdAt(document.getCreatedAt())
                .updatedAt(document.getUpdatedAt())
                .build();
    }
}
