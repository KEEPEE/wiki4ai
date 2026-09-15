package com.wiki4ai.service;

import com.wiki4ai.config.EmbeddingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * HTTP client for the Qwen3-Embedding sidecar (WIKI4AI-35, epic WIKI4AI-26).
 *
 * <p>Contract (sidecar, see {@code embedding/app.py}):</p>
 * <ul>
 *   <li>{@code GET /health} → {@code {"status":"ok", ...}}</li>
 *   <li>{@code POST /embed} body {@code {"texts":[...], "type":"document"|"query"}}
 *       → {@code {"vectors":[[1024 floats]], "dim":1024, "model":..., "truncated":bool}}.
 *       The sidecar applies the Qwen3 query instruction prefix for {@code type=query}
 *       and embeds documents as-is; batch size is capped at 4 server-side.</li>
 * </ul>
 *
 * <p><b>Graceful degradation:</b> every method either succeeds or throws
 * {@link EmbeddingUnavailableException}; callers are expected to fall back to
 * text-only behaviour (never surface a 500 because the sidecar is down).
 * A failed probe marks the sidecar unavailable for a short TTL so a dead
 * sidecar does not add latency to every request.</p>
 */
@Component
public class EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingClient.class);

    /** Thrown when the sidecar cannot be reached or answers with an error. */
    public static class EmbeddingUnavailableException extends RuntimeException {
        public EmbeddingUnavailableException(String message) {
            super(message);
        }

        public EmbeddingUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public record EmbedRequest(List<String> texts, String type) {}

    public record EmbedResponse(List<List<Double>> vectors, int dim, String model, boolean truncated) {}

    private final RestClient restClient;
    private final EmbeddingProperties properties;
    /** Epoch millis until which the sidecar is considered unavailable (0 = not marked). */
    private volatile long unavailableUntil = 0L;

    public EmbeddingClient(RestClient embeddingRestClient, EmbeddingProperties properties) {
        this.restClient = embeddingRestClient;
        this.properties = properties;
    }

    /**
     * Probe the sidecar. Cheap when previously healthy (no HTTP round-trip is
     * skipped, but failures are cached); safe to call on every search.
     */
    public boolean isAvailable() {
        if (!properties.isEnabled()) {
            return false;
        }
        if (System.currentTimeMillis() < unavailableUntil) {
            return false;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = restClient.get()
                    .uri("/health")
                    .retrieve()
                    .body(Map.class);
            boolean ok = body != null && "ok".equals(body.get("status"));
            if (!ok) {
                markUnavailable("sidecar /health returned status=" + (body == null ? "null" : body.get("status")));
            }
            return ok;
        } catch (Exception e) {
            markUnavailable(e.getMessage());
            return false;
        }
    }

    /** Embed texts as documents (no instruction prefix). Returns one vector per text. */
    public List<float[]> embedDocuments(List<String> texts) {
        return embed(texts, "document");
    }

    /** Embed a search query (sidecar applies the Qwen3 retrieval instruction prefix). */
    public float[] embedQuery(String query) {
        return embed(List.of(query), "query").get(0);
    }

    private List<float[]> embed(List<String> texts, String type) {
        if (texts == null || texts.isEmpty()) {
            throw new EmbeddingUnavailableException("No texts to embed");
        }
        int batchSize = Math.max(1, properties.getBatchSize());
        List<float[]> all = new ArrayList<>(texts.size());
        try {
            for (int i = 0; i < texts.size(); i += batchSize) {
                List<String> chunk = texts.subList(i, Math.min(i + batchSize, texts.size()));
                EmbedResponse response = restClient.post()
                        .uri("/embed")
                        .body(new EmbedRequest(chunk, type))
                        .retrieve()
                        .body(EmbedResponse.class);
                if (response == null || response.vectors() == null
                        || response.vectors().size() != chunk.size()) {
                    throw new EmbeddingUnavailableException(
                            "sidecar /embed returned an unexpected response for " + chunk.size() + " texts");
                }
                for (List<Double> v : response.vectors()) {
                    float[] vec = new float[v.size()];
                    for (int j = 0; j < v.size(); j++) {
                        vec[j] = v.get(j).floatValue();
                    }
                    all.add(vec);
                }
            }
            return all;
        } catch (EmbeddingUnavailableException e) {
            markUnavailable(e.getMessage());
            throw e;
        } catch (Exception e) {
            markUnavailable(e.getMessage());
            throw new EmbeddingUnavailableException("embedding sidecar call failed: " + e.getMessage(), e);
        }
    }

    private void markUnavailable(String reason) {
        long until = System.currentTimeMillis() + properties.getUnavailableTtlMs();
        unavailableUntil = Math.max(unavailableUntil, until);
        log.warn("Embedding sidecar unavailable ({}); falling back to text-only search for {} ms",
                reason, properties.getUnavailableTtlMs());
    }

    /**
     * Render a vector as a pgvector literal, e.g. {@code [0.123456,-0.789012]}.
     * 6 decimal places preserve cosine similarity to far more precision than
     * float32 itself and keep the stored strings compact.
     */
    public static String toVectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 9 + 2);
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(String.format(Locale.ROOT, "%.6f", vector[i]));
        }
        return sb.append(']').toString();
    }
}
