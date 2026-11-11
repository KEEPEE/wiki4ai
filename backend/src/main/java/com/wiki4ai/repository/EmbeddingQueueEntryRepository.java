package com.wiki4ai.repository;

import com.wiki4ai.model.EmbeddingQueueEntry;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Data access for the embedding queue (WIKI4AI-197 commit 17, migration V27).
 *
 * <p>Deliberately {@link Repository} rather than {@code JpaRepository}: only these
 * operations exist, so nothing in the application can quietly {@code delete} a queue row
 * or {@code save} one with an arbitrary status. The lifecycle is
 * {@code PENDING} → {@code DONE}/{@code FAILED} and the only way to move it is through a
 * row the caller already loaded.</p>
 */
public interface EmbeddingQueueEntryRepository extends Repository<EmbeddingQueueEntry, Long> {

    EmbeddingQueueEntry save(EmbeddingQueueEntry entry);

    Optional<EmbeddingQueueEntry> findById(Long id);

    /**
     * The work that is due, oldest first — the drainer's query.
     *
     * <p>{@code nextAttemptAt <= :now} is what makes the backoff between attempts work
     * without a timer per row: a failed attempt simply pushes its own row into the
     * future, and a drainer that runs every few seconds ignores it until then. Ordering
     * by {@code nextAttemptAt, id} gives the oldest work first and a stable tie-break.</p>
     *
     * @param now      the cutoff; rows scheduled after it are left alone
     * @param pageable the batch size, so one drainer pass cannot lock up the sidecar
     */
    @Query("SELECT e FROM EmbeddingQueueEntry e "
            + "WHERE e.status = 'PENDING' AND e.nextAttemptAt <= :now "
            + "ORDER BY e.nextAttemptAt ASC, e.id ASC")
    List<EmbeddingQueueEntry> findDueEntries(@Param("now") LocalDateTime now, Pageable pageable);

    /** Every row ever written for one document, oldest first — diagnosis, mostly. */
    List<EmbeddingQueueEntry> findByDocumentIdOrderByIdAsc(Long documentId);

    /** Open (not yet DONE) rows for one document, for diagnosis and for the status endpoint. */
    List<EmbeddingQueueEntry> findByDocumentIdAndStatusNot(Long documentId, String status);

    /** Rows in one status — the status endpoint reports how much work is outstanding. */
    long countByStatus(String status);

    /**
     * Rows that stopped being retried. They are the answer to "why does this document have
     * no vector": the queue did not lose the request, it gave up and said why.
     */
    List<EmbeddingQueueEntry> findByStatus(String status, Pageable pageable);
}
