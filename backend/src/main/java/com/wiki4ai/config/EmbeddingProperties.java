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

    /** TCP connect timeout for sidecar calls (ms). Kept low so a dead sidecar does not stall requests. */
    private int connectTimeoutMs = 2000;

    /** Read timeout for sidecar calls (ms). Embedding a batch of 4 texts takes up to ~5 s on CPU. */
    private int readTimeoutMs = 120000;

    /** Max texts per /embed request — must stay <= the sidecar's MAX_BATCH (4, RAM budget). */
    private int batchSize = 4;

    /** How long to remember the sidecar as unavailable after a failed probe (ms). */
    private long unavailableTtlMs = 15000;
}
