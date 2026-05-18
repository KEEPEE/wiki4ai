package com.wiki4ai.repository;

import com.wiki4ai.model.Document;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for Document entity.
 * Provides CRUD operations and custom queries for document data access.
 */
@Repository
public interface DocumentRepository extends JpaRepository<Document, Long> {

    /**
     * Find a document by its slug within a specific project.
     *
     * @param slug      the document slug
     * @param projectId the project ID
     * @return Optional containing the document if found
     */
    Optional<Document> findBySlugAndProjectId(String slug, Long projectId);

    /**
     * Find all documents belonging to a specific project, ordered by update date (newest first).
     *
     * @param projectId the project ID
     * @return list of documents in the project sorted by updatedAt descending
     */
    List<Document> findByProjectIdOrderByUpdatedAtDesc(Long projectId);

    /**
     * Find all documents belonging to a specific project.
     *
     * @param projectId the project ID
     * @return list of documents in the project
     */
    List<Document> findByProjectId(Long projectId);

    /**
     * Find a document by its title within a specific project.
     *
     * @param projectId the project ID
     * @param title     the document title
     * @return Optional containing the document if found
     */
    Optional<Document> findByProjectIdAndTitle(Long projectId, String title);

    /**
     * Search documents by content keyword within a specific project.
     *
     * @param projectId the project ID
     * @param keyword   the search keyword
     * @return list of matching documents
     */
    @Query("SELECT d FROM Document d WHERE d.project.id = :projectId AND LOWER(d.content) LIKE LOWER(CONCAT('%', :keyword, '%'))")
    List<Document> findByProjectIdAndContentContaining(Long projectId, String keyword);

    /**
     * Count the number of documents in a specific project.
     *
     * @param projectId the project ID
     * @return document count
     */
    long countByProjectId(Long projectId);

    /**
     * Find all documents that link TO a specific target document (backlinks).
     * Returns documents where the given target document is in their linkedDocuments collection.
     *
     * @param targetDocId the ID of the target document to find backlinks for
     * @return list of documents that have a link pointing to the target document
     */
    @Query("SELECT d FROM Document d JOIN d.linkedDocuments ld WHERE ld.id = :targetDocId")
    List<Document> findByLinkedDocumentsId(Long targetDocId);
}
