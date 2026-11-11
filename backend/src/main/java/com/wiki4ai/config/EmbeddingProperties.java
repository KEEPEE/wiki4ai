package com.wiki4ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the Qwen3-Embedding sidecar integration (WIKI4AI-35, epic WIKI4AI-26).
 *
 * <p>The sidecar runs as a separate container in the stack (service name
 * {@code embedding}, internal port 8030). When it is unreachable the backend
 * degrades gracefully to text-only search — these settings never cause startup
 * failures.</p>
 */
@Data
@ConfigurationProperties(prefix = "embedding")
public class EmbeddingProperties {

    /** Base URL of the embedding sidecar (no trailing slash). */
    private String baseUrl = "http://localhost:8030";

    /** Master switch. When false, semantic search is disabled entirely. */
    private boolean enabled = true;

    /**
     * TCP connect timeout for sidecar calls (ms). Kept low so a dead sidecar does not
     * stall requests. Two seconds is already tighter than the ten the review asked for,
     * and it is the right number: the sidecar is a container in the same Docker network,
     * so a connect that takes longer than this means it is not there at all, and every
     * extra second here is latency the caller pays before the graceful fallback kicks in.
     */
    private int connectTimeoutMs = 2000;

    /**
     * Read timeout for sidecar calls (ms). Was 120 000 — two minutes of a request thread
     * and, worse, of an open database transaction waiting on a sidecar that the same class
     * documents as taking "up to ~5 s on CPU" (WIKI4AI-197 commit 17).
     *
     * <p>Thirty seconds is a hundred times the stated typical worst case and still leaves
     * room for a cold model or a large batch. A call that exceeds it is not slow, it is
     * stuck, and the queue now retries it instead of holding a connection open while we
     * see whether it eventually answers.</p>
     */
    private int readTimeoutMs = 30000;

    /** Max texts per /embed request — must stay <= the sidecar's MAX_BATCH (4, RAM budget). */
    private int batchSize = 4;

    /** How long to remember the sidecar as unavailable after a failed probe (ms). */
    private long unavailableTtlMs = 15000;

    /**
     * The durable embedding queue of WIKI4AI-197 commit 17 — the work list that lets the
     * sidecar call happen after the document's transaction has committed.
     */
    private Queue queue = new Queue();

    @Data
    public static class Queue {

        /**
         * Master switch for the queue. When false, a document save embeds synchronously
         * the way it always did — the escape hatch if the queue itself misbehaves.
         */
        private boolean enabled = true;

        /**
         * Whether the after-commit listener runs the embed on its own thread.
         *
         * <p>Off in the test profile, for the reason every scheduler in this project is
         * off there: the suite shares one in-memory database and a background thread
         * writing to it moves rows between other tests' assertions. With this off the
         * listener does nothing and the queue row simply stays PENDING, which is exactly
         * what a test asserts on; {@code EmbeddingQueueServiceTest} calls the worker and
         * the drainer directly, on the test thread.</p>
         */
        private boolean asyncEnabled = true;

        /** Attempts before a row gives up and stays FAILED with its last error. */
        private int maxAttempts = 5;

        /** Base delay between attempts (ms); doubled per attempt, capped at {@link #maxBackoffMs}. */
        private long retryBackoffMs = 30_000;

        /** Ceiling for the exponential backoff (ms). */
        private long maxBackoffMs = 900_000;

        /** Rows one drainer pass will take, so a dead sidecar cannot monopolise the pool. */
        private int drainBatchSize = 20;
    }
}
