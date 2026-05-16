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
}
