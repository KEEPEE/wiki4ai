package com.wiki4ai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Data Transfer Object for Document entity.
 * Used for API request/response payloads.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocumentDTO {

    private Long id;

    @NotBlank(message = "Document title is required")
    private String title;

    private String slug;

    private String content;

    private Long projectId;

    private List<Long> linkedDocuments;

    private java.time.LocalDateTime createdAt;

    private java.time.LocalDateTime updatedAt;

    /**
     * WIKI4AI-72: optimistic locking version. Clients read this and may send it
     * back as expectedVersion on update to detect concurrent modifications
     * (stale value -> 409 Conflict). Always present: existing rows were
     * backfilled to 0 by migration V11, new documents start at 0.
     */
    private Long version;

    @Schema(description = "Number of contentEdits applied in this update. "
            + "Only non-null when the update used contentEdits; null on reads and full-replace updates.")
    private Integer editsApplied;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "Relevance score from hybrid search (Reciprocal Rank Fusion over text + vector "
            + "rankings; higher is better). Only present on /search results; omitted otherwise.")
    private Double score;
}
