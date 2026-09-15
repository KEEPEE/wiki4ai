package com.wiki4ai.repository;

import com.wiki4ai.model.Document;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

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
     * Find paginated documents belonging to a specific project, ordered by update date (newest first).
     * Used for paginated API responses when a project has many documents.
     *
     * @param projectId the project ID
     * @param pageable  pagination parameters (page number, page size, sort)
     * @return Page of documents with metadata (totalElements, totalPages, etc.)
     */
    Page<Document> findByProjectIdOrderByUpdatedAtDesc(Long projectId, Pageable pageable);

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

    // ==================== pgvector (WIKI4AI-35, epic WIKI4AI-26) ====================
    // The embedding column is intentionally NOT mapped on the Document entity:
    // Hibernate has no native vector type, so all vector I/O goes through these
    // native queries. This also keeps `ddl-auto=update` from touching the column.

    /**
     * Store a pgvector literal (e.g. {@code [0.1,-0.2,...]}) for one document.
     *
     * <p>Deliberately WITHOUT {@code clearAutomatically}: the native UPDATE bypasses the
     * persistence context, but the embedding column is not mapped on the entity, so
     * Hibernate can never overwrite it with a stale in-memory value. Clearing would
     * instead detach every managed entity of the surrounding CRUD transaction and break
     * lazy loading (e.g. {@code linkedDocuments}) when the DTO is built afterwards.</p>
     */
    @Modifying
    @Query(value = "UPDATE documents SET embedding = CAST(:vec AS vector) WHERE id = :id", nativeQuery = true)
    int updateEmbedding(@Param("id") Long id, @Param("vec") String vec);

    /** IDs of all documents that already have an embedding (backfill skip set). */
    @Query(value = "SELECT id FROM documents WHERE embedding IS NOT NULL", nativeQuery = true)
    List<Long> findIdsWithEmbedding();

    /** Count of documents with a non-null embedding (progress/verification). */
    @Query(value = "SELECT COUNT(*) FROM documents WHERE embedding IS NOT NULL", nativeQuery = true)
    long countWithEmbedding();

    /**
     * Vector similarity search within one project.
     *
     * <p>Returns rows of {@code [Long id, Double similarity]} where similarity is
     * {@code 1 - cosine_distance} (1.0 = identical direction), ordered by
     * ascending cosine distance (HNSW index) and limited to {@code limit} hits.</p>
     */
    @Query(value = "SELECT d.id, 1 - (d.embedding <=> CAST(:qvec AS vector)) AS similarity "
            + "FROM documents d "
            + "WHERE d.project_id = :projectId AND d.embedding IS NOT NULL "
            + "ORDER BY d.embedding <=> CAST(:qvec AS vector) ASC "
            + "LIMIT :limit", nativeQuery = true)
    List<Object[]> findTopByEmbeddingSimilarity(@Param("projectId") Long projectId,
                                                @Param("qvec") String qvec,
                                                @Param("limit") int limit);
}
