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
     * Find paginated VISIBLE documents of a project for a given user (WIKI4AI-99),
     * ordered by update date (newest first). A document is visible when it is
     * public or owned by the given user; private documents of other users are
     * excluded at the SQL level so pagination totals stay correct. ADMIN callers
     * use {@link #findByProjectIdOrderByUpdatedAtDesc} instead (no filter).
     *
     * @param projectId the project ID
     * @param ownerId   the requesting user's id (null for anonymous callers —
     *                  only public documents match then)
     * @param pageable  pagination parameters (page number, page size, sort)
     * @return Page of visible documents with metadata
     */
    @Query("SELECT d FROM Document d WHERE d.project.id = :projectId "
            + "AND (d.visibility = 'public' OR d.owner.id = :ownerId) "
            + "ORDER BY d.updatedAt DESC")
    Page<Document> findVisibleByProjectIdOrderByUpdatedAtDesc(@Param("projectId") Long projectId,
                                                              @Param("ownerId") Long ownerId,
                                                              Pageable pageable);

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
     * Search documents by content keyword across ALL projects (global search, WIKI4AI-61).
     * Same text-LIKE semantics as {@link #findByProjectIdAndContentContaining} without the
     * project filter.
     *
     * @param keyword the search keyword
     * @return list of matching documents from every project
     */
    @Query("SELECT d FROM Document d WHERE LOWER(d.content) LIKE LOWER(CONCAT('%', :keyword, '%'))")
    List<Document> findByContentContaining(String keyword);

    /**
     * Count the number of documents in a specific project.
     *
     * @param projectId the project ID
     * @return document count
     */
    long countByProjectId(Long projectId);

    /**
     * One row of the batched document count: a project id and how many documents it
     * holds (WIKI4AI-197 commit 16). A projection instead of {@code Object[]} so the
     * caller cannot guess the column order.
     */
    interface ProjectDocumentCount {
        Long getProjectId();

        Long getDocumentCount();
    }

    /**
     * Document counts for many projects in ONE grouped query (WIKI4AI-197 commit 16).
     *
     * <p>The dashboard renders one card per project and each card shows a document count.
     * That count used to come from {@code project.getDocuments().size()}, which is a lazy
     * {@code @OneToMany}: for a list of N projects Hibernate issued N selects, each one
     * loading <i>every document row</i> of that project — titles, content and embeddings
     * included — and threw all but the count away. On a wiki with a few hundred documents
     * per project the dashboard was reading the whole document table on every request.</p>
     *
     * <p>Projects with no documents produce no row; the caller treats "absent" as zero.
     * An empty {@code projectIds} must not be passed — {@code IN ()} is not valid SQL —
     * so the service short-circuits that case instead of calling this.</p>
     *
     * @param projectIds the projects to count, non-empty
     * @return one row per project that has at least one document
     */
    @Query("SELECT d.project.id AS projectId, COUNT(d) AS documentCount "
            + "FROM Document d WHERE d.project.id IN :projectIds GROUP BY d.project.id")
    List<ProjectDocumentCount> countByProjectIdIn(@Param("projectIds") java.util.Collection<Long> projectIds);

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

    /** Total number of rows in the document_links join table (integrity reports, WIKI4AI-42). */
    @Query(value = "SELECT COUNT(*) FROM document_links", nativeQuery = true)
    long countLinks();

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

    /**
     * Vector similarity search across ALL projects (global search, WIKI4AI-61).
     *
     * <p>Same shape as {@link #findTopByEmbeddingSimilarity} minus the project filter.
     * The existing HNSW index ({@code idx_documents_embedding_hnsw}) already covers all
     * documents — no new index or migration is required.</p>
     */
    @Query(value = "SELECT d.id, 1 - (d.embedding <=> CAST(:qvec AS vector)) AS similarity "
            + "FROM documents d "
            + "WHERE d.embedding IS NOT NULL "
            + "ORDER BY d.embedding <=> CAST(:qvec AS vector) ASC "
            + "LIMIT :limit", nativeQuery = true)
    List<Object[]> findTopByEmbeddingSimilarityGlobal(@Param("qvec") String qvec,
                                                      @Param("limit") int limit);

    // ==================== WIKI4AI-184: pin to top + manual order ====================
    //
    // The writes are JPQL UPDATEs for a sharper reason than the Project case: this
    // entity carries @Version (WIKI4AI-72). Saving a managed Document to flip a
    // display flag would bump the version and make another user's in-flight content
    // edit fail with 409 — a conflict over a change that touched no text at all.
    // Hibernate does not apply @Version to JPQL UPDATE statements, so the version and
    // updatedAt of a pinned or reordered document stay exactly as they were.
    //
    // clearAutomatically is required because pinned/sortOrder ARE mapped on the
    // entity: without it the persistence context would still hold the old values and
    // the DTO built after the write would report them.

    /**
     * Set the pinned flag and the manual position of one document in a single UPDATE,
     * without touching {@code version} or {@code updated_at} (WIKI4AI-184).
     *
     * @param id        the document id
     * @param pinned    the new pinned flag
     * @param sortOrder the new manual position inside its project
     * @return number of updated rows (0 when the document is gone)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Document d SET d.pinned = :pinned, d.sortOrder = :sortOrder WHERE d.id = :id")
    int updatePinning(@Param("id") Long id,
                      @Param("pinned") boolean pinned,
                      @Param("sortOrder") int sortOrder);

    // The unconditional "set the position of one document" UPDATE that WIKI4AI-184 added
    // is gone (WIKI4AI-197 commit 11): an unguarded write to sort_order is exactly how a
    // reorder loses to a concurrent one. updateSortOrderIfCurrent is the only way to move
    // a document now, and the caller has to say what position it believes the row holds.

    /**
     * Lowest manual position in use inside one project, or 0 when the project has no
     * documents (WIKI4AI-184). A freshly pinned or freshly created document is placed
     * one slot above it so it lands at the top of a sort=manual list.
     *
     * @param projectId the project whose list is being extended
     * @return the smallest sort_order in the project, or 0
     */
    @Query("SELECT COALESCE(MIN(d.sortOrder), 0) FROM Document d WHERE d.project.id = :projectId")
    int minSortOrderForProject(@Param("projectId") Long projectId);

    /**
     * The ORDER BY shared by the two filtered document queries (WIKI4AI-184).
     *
     * <p>The sort key arrives as a parameter and each key owns one {@code CASE} branch:
     * for a given {@code :sort} every other branch is NULL for every row, so it cannot
     * affect the result. That keeps one query per visibility rule instead of six, and it
     * keeps the ordering in SQL, which a paginated list needs — a Pageable sort appended
     * to a derived {@code ...OrderByUpdatedAtDesc} method would have put updatedAt first
     * and made sort=manual shuffle rows between pages.</p>
     *
     * <p>{@code LOWER(d.title)} is deliberate. Ordering by the raw column leaves the
     * result to the database collation: H2 puts every uppercase name before every
     * lowercase one, a UTF-8 Postgres does not, so the same request would page
     * differently in test and in production. {@code pinned DESC} first and
     * {@code d.id ASC} last make the order total, which is what keeps page 2 consistent
     * with page 1.</p>
     */
    String FILTERED_ORDER_BY =
            "ORDER BY d.pinned DESC, "
            + "CASE WHEN :sort = 'manual' THEN d.sortOrder END ASC, "
            + "CASE WHEN :sort = 'name_asc' THEN LOWER(d.title) END ASC, "
            + "CASE WHEN :sort = 'name_desc' THEN LOWER(d.title) END DESC, "
            + "CASE WHEN :sort = 'created_asc' THEN d.createdAt END ASC, "
            + "CASE WHEN :sort = 'created_desc' THEN d.createdAt END DESC, "
            + "CASE WHEN :sort = 'updated_desc' THEN d.updatedAt END DESC, "
            + "d.id ASC";

    /**
     * Paginated documents of one project with an optional title/content filter, ordered
     * by the {@code sort} key (WIKI4AI-184). ADMIN callers use this variant (no
     * visibility filter).
     *
     * @param projectId the project
     * @param q         case-insensitive substring of title or content; {@code ""} = no filter
     * @param sort      a normalized key from {@code ListSort} (see {@link #FILTERED_ORDER_BY})
     * @param pageable  page and size; expected unsorted, the ordering lives in the query
     * @return page of matching documents
     */
    @Query("SELECT d FROM Document d WHERE d.project.id = :projectId "
            + "AND (:q = '' OR LOWER(d.title) LIKE LOWER(CONCAT('%', :q, '%')) "
            + "OR LOWER(COALESCE(d.content, '')) LIKE LOWER(CONCAT('%', :q, '%'))) "
            + FILTERED_ORDER_BY)
    Page<Document> findAllByProjectIdFiltered(@Param("projectId") Long projectId,
                                              @Param("q") String q,
                                              @Param("sort") String sort,
                                              Pageable pageable);

    /**
     * Same as {@link #findAllByProjectIdFiltered} but restricted to the documents the
     * caller may see (WIKI4AI-99 rule: public, or owned by the caller). The filter is
     * in the query, not in Java, so the page totals stay correct.
     *
     * @param projectId the project
     * @param ownerId   the caller's id, or null for anonymous (then only public match)
     * @param q         case-insensitive substring of title or content; {@code ""} = no filter
     * @param sort      a normalized key from {@code ListSort} (see {@link #FILTERED_ORDER_BY})
     * @param pageable  page and size; expected unsorted, the ordering lives in the query
     * @return page of visible matching documents
     */
    @Query("SELECT d FROM Document d WHERE d.project.id = :projectId "
            + "AND (d.visibility = 'public' OR d.owner.id = :ownerId) "
            + "AND (:q = '' OR LOWER(d.title) LIKE LOWER(CONCAT('%', :q, '%')) "
            + "OR LOWER(COALESCE(d.content, '')) LIKE LOWER(CONCAT('%', :q, '%'))) "
            + FILTERED_ORDER_BY)
    Page<Document> findVisibleByProjectIdFiltered(@Param("projectId") Long projectId,
                                                  @Param("ownerId") Long ownerId,
                                                  @Param("q") String q,
                                                  @Param("sort") String sort,
                                                  Pageable pageable);

    /**
     * Compare-and-set the manual position of one document (WIKI4AI-197 commit 11): the
     * UPDATE lands only if the row still holds the position the caller read.
     *
     * <p>The real serialisation is the pessimistic lock on the parent project row
     * ({@link ProjectRepository#findProjectByIdForUpdate}), taken before the list is read.
     * This condition is the second line of defence: a writer that forgets the lock fails
     * visibly with 0 rows instead of quietly writing a position derived from a stale
     * snapshot. It is deliberately not {@code @Version}-based — {@code Document} carries a
     * version for content edits (WIKI4AI-72) and a sidebar drag must not collide with an
     * editor's save.</p>
     *
     * @param id                the document id
     * @param sortOrder         the position to write
     * @param expectedSortOrder the position the caller read
     * @return 1 when the row was moved, 0 when somebody else moved it first
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Document d SET d.sortOrder = :sortOrder "
            + "WHERE d.id = :id AND d.sortOrder = :expectedSortOrder")
    int updateSortOrderIfCurrent(@Param("id") Long id,
                                 @Param("sortOrder") int sortOrder,
                                 @Param("expectedSortOrder") int expectedSortOrder);
}
