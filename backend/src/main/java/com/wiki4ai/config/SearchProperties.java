package com.wiki4ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for hybrid document search (WIKI4AI-90).
 *
 * <p>Controls the semantic (vector) path of the hybrid search pipeline. The text
 * LIKE path is always active and never affected by these settings; when every
 * vector hit falls below the threshold a query simply degrades to text-only,
 * which makes the "no results" empty state reachable.</p>
 */
@Data
@ConfigurationProperties(prefix = "wiki4ai.search")
public class SearchProperties {

    /**
     * Minimum cosine similarity ({@code 1 - cosine distance}) a vector hit must reach
     * to be fused into the hybrid result. Hits below the threshold are dropped from
     * the vector ranking; hits at or above it are kept (inclusive bound).
     *
     * <p><b>Data-driven default (WIKI4AI-90):</b> measured on the dev corpus
     * (17 embedded documents, Qwen3-Embedding-0.6B-int8) — the best score of a
     * genuinely unrelated query ("xyzzy quux flurb") was 0.3374, while relevant
     * hits for meaningful queries start at ~0.37 ("docker deployment" top-5:
     * 0.59/0.50/0.40/0.38/0.37; "mcp tools": 0.54/0.54). The default 0.35 sits in
     * that gap, above the noise ceiling and below the relevance floor. On larger
     * corpora unrelated scores trend lower, so the same default stays safe.</p>
     *
     * <p>Set to 0 (or negative) to disable filtering entirely (pre-WIKI4AI-90
     * behaviour).</p>
     */
    private double semanticMinSimilarity = 0.35;
}
