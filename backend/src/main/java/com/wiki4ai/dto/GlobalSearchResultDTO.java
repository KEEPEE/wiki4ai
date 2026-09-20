package com.wiki4ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Data Transfer Object for a single hit of the global (cross-project) hybrid
 * document search (WIKI4AI-61).
 *
 * <p>Unlike {@link DocumentDTO} this DTO intentionally carries only an
 * {@code excerpt} (~200 characters around the first keyword occurrence) instead
 * of the full content — a global result list spans many projects and shipping
 * full documents would bloat the payload. Project attribution
 * ({@code projectSlug}/{@code projectName}) is included on every hit.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GlobalSearchResultDTO {

    private Long id;

    private String title;

    private String slug;

    private Long projectId;

    @Schema(description = "URL-friendly slug of the owning project")
    private String projectSlug;

    @Schema(description = "Display name of the owning project")
    private String projectName;

    @Schema(description = "Relevance score from hybrid search (Reciprocal Rank Fusion over text + vector "
            + "rankings; higher is better)")
    private Double score;

    private java.time.LocalDateTime updatedAt;

    @Schema(description = "Context snippet of ~200 characters around the first keyword occurrence "
            + "(or the beginning of the content when the keyword is not present literally)")
    private String excerpt;
}
