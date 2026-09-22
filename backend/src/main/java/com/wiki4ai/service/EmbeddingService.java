package com.wiki4ai.service;

import com.wiki4ai.config.EmbeddingProperties;
import com.wiki4ai.model.Document;
import com.wiki4ai.repository.DocumentRepository;
import com.wiki4ai.repository.UserRepository;
import com.wiki4ai.model.Role;
import com.wiki4ai.model.User;
import jakarta.annotation.PreDestroy;
import jakarta.persistence.OptimisticLockException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Embedding orchestration for documents (WIKI4AI-35, epic WIKI4AI-26).
 *
 * <p>Responsibilities:</p>
 * <ul>
 *   <li>{@link #embedAndSave(Document)} — synchronous embed + persist after
 *       create/update. Never throws: a sidecar failure is logged and the
 *       document stays searchable via text LIKE until a backfill fills the gap.</li>
 *   <li>Admin backfill — async, single-flight, idempotent (skips documents that
 *       already have an embedding unless {@code force=true}), with live progress
 *       exposed via {@link #getStatus()} for the status endpoint.</li>
 * </ul>
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    /** Immutable snapshot of backfill progress (JSON-serialised by the status endpoint). */
    public record BackfillStatus(
            boolean running,
            boolean force,
            int total,
            int processed,
            int embedded,
            int skipped,
            int failed,
            String lastError,
            Instant startedAt,
            Instant finishedAt) {

        public static BackfillStatus idle() {
            return new BackfillStatus(false, false, 0, 0, 0, 0, 0, null, null, null);
        }
    }

    private final DocumentRepository documentRepository;
    private final UserRepository userRepository;
    private final EmbeddingClient embeddingClient;
    private final EmbeddingProperties properties;
    private final TransactionTemplate transactionTemplate;

    private final ExecutorService backfillExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "embedding-backfill");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean backfillRunning = new AtomicBoolean(false);
    private volatile BackfillStatus status = BackfillStatus.idle();

    public EmbeddingService(DocumentRepository documentRepository,
                            UserRepository userRepository,
                            EmbeddingClient embeddingClient,
                            EmbeddingProperties properties,
                            TransactionTemplate transactionTemplate) {
        this.documentRepository = documentRepository;
        this.userRepository = userRepository;
        this.embeddingClient = embeddingClient;
        this.properties = properties;
        this.transactionTemplate = transactionTemplate;
    }

    // ==================== per-document embed (create/update path) ====================

    /** Text representation of a document: title + newline + body (per research doc §9). */
    public String documentText(Document document) {
        String content = document.getContent() != null ? document.getContent() : "";
        return document.getTitle() + "\n" + content;
    }

    /**
     * Embed the document and store the vector. Synchronous (one small document,
     * ~100–900 ms). Failures are swallowed and logged — document CRUD must not
     * break when the sidecar is down.
     */
    public void embedAndSave(Document document) {
        try {
            float[] vector = embeddingClient.embedDocuments(List.of(documentText(document))).get(0);
            String literal = EmbeddingClient.toVectorLiteral(vector);
            transactionTemplate.executeWithoutResult(tx ->
                    documentRepository.updateEmbedding(document.getId(), literal));
            log.debug("Embedded document {} (id={})", document.getTitle(), document.getId());
        } catch (ObjectOptimisticLockingFailureException | OptimisticLockException e) {
            // WIKI4AI-72: a concurrent writer committed while we held a stale state —
            // this is a document conflict, NOT an embedding failure. It must surface
            // as 409 to the caller; swallowing it would leave the surrounding
            // transaction rollback-only and turn the conflict into a 500.
            throw e;
        } catch (Exception e) {
            log.warn("Embedding failed for document '{}' (id={}): {} — text search still works; "
                    + "run the backfill endpoint to fill the gap", document.getTitle(), document.getId(), e.getMessage());
        }
    }

    // ==================== admin backfill ====================

    /** Verify the caller has the ADMIN role (same pattern as AdminService). */
    public void verifyAdminRole() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null
                || "anonymousUser".equals(authentication.getName())) {
            throw new SecurityException("Authentication required. Please provide a valid JWT token.");
        }
        String username = authentication.getName();
        User currentUser = userRepository.findByUsername(username)
                .orElseThrow(() -> new SecurityException("Authenticated user not found in database"));
        if (currentUser.getRole() != Role.ADMIN) {
            throw new SecurityException("Admin access required. Current role: " + currentUser.getRole());
        }
    }

    /**
     * Start the backfill asynchronously.
     *
     * @param force when true, re-embed every document; otherwise documents that
     *              already have an embedding are skipped (idempotent)
     * @return true if a new backfill was started, false if one is already running
     */
    public boolean startBackfill(boolean force) {
        if (!backfillRunning.compareAndSet(false, true)) {
            return false;
        }
        status = new BackfillStatus(true, force, 0, 0, 0, 0, 0, null, Instant.now(), null);
        backfillExecutor.submit(() -> runBackfill(force));
        return true;
    }

    /** Package-private so unit tests can run the backfill synchronously. */
    void runBackfill(boolean force) {
        Instant startedAt = Instant.now();
        try {
            List<Document> documents = documentRepository.findAll();
            int total = documents.size();
            Set<Long> alreadyEmbedded = force ? Set.of() : Set.copyOf(documentRepository.findIdsWithEmbedding());

            List<Document> toEmbed = documents.stream()
                    .filter(d -> !alreadyEmbedded.contains(d.getId()))
                    .toList();
            int skipped = total - toEmbed.size();
            status = new BackfillStatus(true, force, total, 0, 0, skipped, 0, null, startedAt, null);

            int batchSize = Math.max(1, properties.getBatchSize());
            int embedded = 0;
            int failed = 0;
            String lastError = null;

            for (int i = 0; i < toEmbed.size(); i += batchSize) {
                List<Document> batch = toEmbed.subList(i, Math.min(i + batchSize, toEmbed.size()));
                try {
                    List<String> texts = batch.stream().map(this::documentText).toList();
                    List<float[]> vectors = embeddingClient.embedDocuments(texts);
                    String[] literals = vectors.stream().map(EmbeddingClient::toVectorLiteral).toArray(String[]::new);
                    transactionTemplate.executeWithoutResult(tx -> {
                        for (int j = 0; j < batch.size(); j++) {
                            documentRepository.updateEmbedding(batch.get(j).getId(), literals[j]);
                        }
                    });
                    embedded += batch.size();
                } catch (Exception e) {
                    failed += batch.size();
                    lastError = e.getMessage();
                    log.warn("Backfill batch at offset {} failed: {}", i, e.getMessage());
                }
                int processed = Math.min(i + batchSize, toEmbed.size()) + skipped;
                status = new BackfillStatus(true, force, total, processed, embedded, skipped, failed,
                        lastError, startedAt, null);
            }

            Instant finished = Instant.now();
            status = new BackfillStatus(false, force, total, total, embedded, skipped, failed,
                    lastError, startedAt, finished);
            log.info("Backfill finished: total={}, embedded={}, skipped={}, failed={} ({} ms)",
                    total, embedded, skipped, failed, Duration.between(startedAt, finished).toMillis());
        } catch (Exception e) {
            log.error("Backfill aborted unexpectedly", e);
            status = new BackfillStatus(false, force, 0, 0, 0, 0, 0, e.getMessage(),
                    startedAt, Instant.now());
        } finally {
            backfillRunning.set(false);
        }
    }

    public BackfillStatus getStatus() {
        return status;
    }

    /** Accessor for the underlying client (used by DocumentService's hybrid search). */
    public EmbeddingClient getClient() {
        return embeddingClient;
    }

    @PreDestroy
    void shutdown() {
        backfillExecutor.shutdownNow();
    }
}
