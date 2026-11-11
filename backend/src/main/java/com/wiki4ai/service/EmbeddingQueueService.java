package com.wiki4ai.service;

import com.wiki4ai.config.EmbeddingProperties;
import com.wiki4ai.model.Document;
import com.wiki4ai.model.EmbeddingQueueEntry;
import com.wiki4ai.repository.DocumentRepository;
import com.wiki4ai.repository.EmbeddingQueueEntryRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.annotation.PreDestroy;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Durable queue behind document embeddings (WIKI4AI-197 commit 17).
 *
 * <h2>The problem</h2>
 * A document save used to embed the document <i>inside its own database transaction</i>:
 * {@code DocumentService} saved the row, called the embedding sidecar over HTTP, and only
 * then committed. Two things were wrong with that, and the second is the dangerous one.
 * <ul>
 *   <li>The save waited for the sidecar. With a 120 second read timeout, a slow sidecar
 *       made a document save take two minutes.</li>
 *   <li>While it waited, the transaction stayed open — holding a connection from a pool of
 *       ten and the write lock on the row it had just inserted. A handful of concurrent
 *       saves against a stalled sidecar therefore did not slow down saves; it starved the
 *       pool and stalled <b>every</b> request in the instance, including reads that had
 *       nothing to do with embeddings.</li>
 * </ul>
 *
 * <h2>The fix, in two halves</h2>
 * <ol>
 *   <li><b>The call moves after the commit.</b> The transaction closes as soon as the
 *       document is written; the HTTP call happens later, on its own thread, with no
 *       transaction and no connection held.</li>
 *   <li><b>The intent is written down first.</b> Moving work after a commit normally loses
 *       it: a crash between the commit and the work, or a failed call, and the document
 *       simply never gets a vector. So the request is persisted as an
 *       {@link EmbeddingQueueEntry} in the <i>same transaction as the document</i>. It
 *       commits or rolls back with the document, and anything still PENDING when the
 *       process comes back is picked up by {@link #drainDueEntries()}.</li>
 * </ol>
 *
 * <h2>Why a hand-managed executor instead of {@code @Async}</h2>
 * The plan said {@code @Async}. This class uses a single daemon thread it owns, for two
 * reasons that are properties of this codebase rather than of the idea. {@code @Async}
 * needs {@code @EnableAsync} application-wide, which proxies every bean and puts work on
 * another thread inside the test suite — the same nondeterminism this project already
 * decided against for its schedulers, and switched off in the test profile for exactly
 * that reason ({@code SchedulingConfig}). And {@code EmbeddingService} already runs its
 * backfill on a hand-built single-thread daemon executor, so this is the established
 * pattern here, not a new one. One thread is also the right concurrency for this sidecar:
 * its batch size is capped at 4 by a RAM budget, so a pool would only queue requests
 * twice — once in the pool and once in the sidecar.
 *
 * <h2>Why an attempts column instead of {@code @Retryable}</h2>
 * Same reasoning: {@code @Retryable} needs a new dependency and retries in memory, so a
 * restart discards them. Here an attempt is a change to a row — the count, the error and
 * the next time to try — so the retry survives a restart, which is the entire reason the
 * queue exists.
 */
@Service
@RequiredArgsConstructor
public class EmbeddingQueueService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingQueueService.class);

    /**
     * Published by {@link #enqueueFor(Document)} inside the document's transaction, and
     * consumed after that transaction commits.
     *
     * @param entryId the queue row that carries the request, not the document id: one
     *                document can have several requests over time and each must be closed
     *                on its own terms.
     */
    public record EmbedRequested(Long entryId) {
    }

    private final EmbeddingQueueEntryRepository queueRepository;
    private final DocumentRepository documentRepository;
    private final EmbeddingClient embeddingClient;
    private final EmbeddingService embeddingService;
    private final EmbeddingProperties properties;
    private final TransactionTemplate transactionTemplate;

    /**
     * Injected by Spring (the application context is itself a publisher). A unit test
     * passes a publisher that records or ignores, so the queue can be exercised without
     * an application context at all.
     */
    private final ApplicationEventPublisher eventPublisher;

    /**
     * One daemon thread. The sidecar caps a batch at four texts by its own RAM budget, so
     * serialising requests is what keeps it fed rather than overwhelmed; and a queue that
     * is briefly behind is fine, which is the point of having it.
     */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "embedding-queue");
        t.setDaemon(true);
        return t;
    });

    /**
     * The text representation the sidecar is trained on: title, newline, body.
     *
     * <p>Static and shared because both this worker and the backfill in
     * {@code EmbeddingService} must produce byte-identical input for the same document —
     * if they differed, a backfill would silently rewrite vectors that the per-document
     * path had produced with different input, and the two would never converge.</p>
     */
    public static String documentTextOf(Document document) {
        String content = document.getContent() != null ? document.getContent() : "";
        return document.getTitle() + "\n" + content;
    }

    // ==================== enqueue (inside the document's transaction) ====================

    /**
     * Record that this document needs embedding, and ask for it after the commit.
     *
     * <p>Called from inside the caller's transaction on purpose: the row must become
     * visible exactly when the document it describes does, and must disappear if that
     * document save is rolled back. No new transaction is started.</p>
     *
     * <p>Never throws. An embedding request is not allowed to fail a document save; if the
     * queue itself cannot be written, the caller falls back to the old synchronous path
     * rather than losing the user's edit.</p>
     *
     * @param document the saved document (must already have an id)
     */
    public void enqueueFor(Document document) {
        if (document == null || document.getId() == null) {
            return;
        }
        EmbeddingProperties.Queue queue = properties.getQueue();
        if (!queue.isEnabled()) {
            // The escape hatch: with the queue switched off the instance behaves exactly as
            // it did before this commit, synchronous embed and all.
            embeddingService.embedAndSave(document);
            return;
        }
        try {
            EmbeddingQueueEntry entry = queueRepository.save(EmbeddingQueueEntry.builder()
                    .documentId(document.getId())
                    .status(EmbeddingQueueEntry.STATUS_PENDING)
                    .attempts(0)
                    .nextAttemptAt(LocalDateTime.now())
                    .build());
            // Published inside the transaction, so the listener runs only if this commit
            // happens. A rolled-back save leaves no phantom request behind.
            publish(new EmbedRequested(entry.getId()));
        } catch (Exception e) {
            log.warn("Could not queue embedding for document {}: {} — falling back to a "
                    + "synchronous embed", document.getId(), e.getMessage());
            embeddingService.embedAndSave(document);
        }
    }

    private void publish(EmbedRequested event) {
        eventPublisher.publishEvent(event);
    }

    // ==================== after-commit trigger ====================

    /**
     * Run the embed once the transaction that requested it has committed.
     *
     * <p>{@code AFTER_COMMIT} is the whole mechanism: at this point the document is already
     * durable and its transaction — connection, locks — is gone, so the sidecar call can
     * take as long as it likes without holding anything from anyone. The row was committed
     * with the document, so if this listener never runs (a crash, a restart, a rejected
     * task) the work is not lost; {@link #drainDueEntries()} finds the row.</p>
     *
     * <p>{@code fallbackExecution = true} covers a request published with no transaction
     * at all — there is nothing to wait for in that case, so the listener runs immediately
     * instead of being silently dropped, which is Spring's default behaviour.</p>
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onEmbedRequested(EmbedRequested event) {
        if (event == null || event.entryId() == null) {
            return;
        }
        if (!properties.getQueue().isAsyncEnabled()) {
            // Test profile: no background thread touching the shared in-memory database.
            // The row stays PENDING, which is what the tests assert on, and they call
            // processEntry / drainDueEntries themselves on the test thread.
            return;
        }
        try {
            executor.submit(() -> {
                try {
                    processEntry(event.entryId());
                } catch (Exception e) {
                    log.warn("Embedding worker failed for queue entry {}: {}", event.entryId(), e.getMessage());
                }
            });
        } catch (Exception e) {
            // A rejected task is not an error here: the row is still PENDING and the
            // scheduled drainer will take it. That is the difference between a queue and
            // a bare thread pool.
            log.debug("Could not hand queue entry {} to the worker; the drainer will pick it up: {}",
                    event.entryId(), e.getMessage());
        }
    }

    // ==================== the unit of work ====================

    /**
     * Embed one queued document and close its row.
     *
     * <p>Runs with <b>no transaction open</b> across the HTTP call. The two writes that
     * follow — the vector and the row's state — happen in one short transaction, so a
     * document either has its vector and a closed row, or has neither and the row stays
     * PENDING for the next attempt.</p>
     *
     * @param entryId the queue row to work on
     * @return true when the vector was written
     */
    public boolean processEntry(Long entryId) {
        if (entryId == null) {
            return false;
        }
        EmbeddingQueueEntry entry = queueRepository.findById(entryId).orElse(null);
        if (entry == null || !entry.isPending()) {
            return false;   // already closed by another attempt, or by the drainer
        }

        Document document = documentRepository.findById(entry.getDocumentId()).orElse(null);
        if (document == null) {
            // The document was deleted between the commit and the embed. That is a normal
            // state for a queue row, not a failure: there is nothing left to embed, so the
            // request is closed rather than retried forever.
            closeRow(entryId);
            return false;
        }

        try {
            float[] vector = embeddingClient
                    .embedDocuments(List.of(documentTextOf(document)))
                    .get(0);
            String literal = EmbeddingClient.toVectorLiteral(vector);
            writeEmbedding(entryId, document.getId(), literal);
            log.debug("Embedded document {} (id={}) from queue entry {}",
                    document.getTitle(), document.getId(), entryId);
            return true;
        } catch (Exception e) {
            failRow(entryId, e);
            return false;
        }
    }

    /** The vector and the closed row, in one transaction. */
    private void writeEmbedding(Long entryId, Long documentId, String literal) {
        transactionTemplate.executeWithoutResult(tx -> {
            documentRepository.updateEmbedding(documentId, literal);
            queueRepository.findById(entryId).ifPresent(row -> {
                row.markDone();
                queueRepository.save(row);
            });
        });
    }

    /** Close a row with no vector, for a document that no longer exists. */
    private void closeRow(Long entryId) {
        transactionTemplate.executeWithoutResult(tx ->
                queueRepository.findById(entryId).ifPresent(row -> {
                    row.markDone();
                    queueRepository.save(row);
                }));
    }

    /**
     * Record a failed attempt: count it, keep the message, push the next attempt into the
     * future, and stop retrying once the row has tried enough times.
     *
     * <p>The backoff is exponential and capped. Without the cap a row that outlives a long
     * sidecar outage would be retried at its original interval forever; with it the queue
     * keeps a steady, low rate of probes and gives up at {@code maxAttempts} with the last
     * error kept on the row.</p>
     */
    private void failRow(Long entryId, Exception cause) {
        EmbeddingProperties.Queue queue = properties.getQueue();
        transactionTemplate.executeWithoutResult(tx ->
                queueRepository.findById(entryId).ifPresent(row -> {
                    LocalDateTime next = LocalDateTime.now().plusNanos(nextBackoffNanos(row.getAttempts()));
                    row.recordFailure(cause.getMessage(), next, queue.getMaxAttempts());
                    queueRepository.save(row);
                    if (EmbeddingQueueEntry.STATUS_FAILED.equals(row.getStatus())) {
                        log.warn("Giving up on embedding document {} after {} attempts: {} — "
                                        + "text search still works; the row is FAILED with this error",
                                row.getDocumentId(), row.getAttempts(), cause.getMessage());
                    } else {
                        log.debug("Embedding attempt {} for document {} failed: {}; next attempt at {}",
                                row.getAttempts(), row.getDocumentId(), cause.getMessage(), next);
                    }
                }));
    }

    /** {@code base * 2^attempts}, capped at {@code maxBackoffMs}. */
    private long nextBackoffNanos(int attempts) {
        EmbeddingProperties.Queue queue = properties.getQueue();
        long base = Math.max(1000L, queue.getRetryBackoffMs());
        long cap = Math.max(base, queue.getMaxBackoffMs());
        int shift = Math.min(attempts, 20);   // keeps the shift below 63 bits
        long delay = Math.min(cap, base << shift);
        return TimeUnit.MILLISECONDS.toNanos(delay);
    }

    // ==================== the drainer ====================

    /**
     * Pick up queued work that nobody triggered: a failed attempt whose backoff has
     * elapsed, a request left PENDING by a restart, or one the executor refused.
     *
     * <p>This is what makes the queue durable rather than merely asynchronous. An
     * after-commit listener alone loses the work if the process dies between the commit
     * and the call; the row survives, and this method finds it.</p>
     *
     * <p>The batch is capped so one pass cannot hold the sidecar (and a database
     * connection per write) for minutes while an outage drags on.</p>
     *
     * @return how many rows were embedded
     */
    public int drainDueEntries() {
        EmbeddingProperties.Queue queue = properties.getQueue();
        if (!queue.isEnabled()) {
            return 0;
        }
        List<EmbeddingQueueEntry> due = queueRepository.findDueEntries(
                LocalDateTime.now(), PageRequest.of(0, Math.max(1, queue.getDrainBatchSize())));
        if (due.isEmpty()) {
            return 0;
        }
        log.debug("Draining {} queued embedding(s)", due.size());
        int embedded = 0;
        for (EmbeddingQueueEntry entry : due) {
            if (processEntry(entry.getId())) {
                embedded++;
            }
        }
        return embedded;
    }

    /** How many requests are still waiting to be embedded. Reported by the status endpoint. */
    public long pendingCount() {
        return queueRepository.countByStatus(EmbeddingQueueEntry.STATUS_PENDING);
    }

    /** How many requests gave up, oldest first. */
    public List<EmbeddingQueueEntry> failedEntries(int limit) {
        return queueRepository.findByStatus(EmbeddingQueueEntry.STATUS_FAILED,
                PageRequest.of(0, Math.max(1, limit)));
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
