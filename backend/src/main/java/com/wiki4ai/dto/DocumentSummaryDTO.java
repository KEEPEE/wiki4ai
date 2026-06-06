package com.wiki4ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Lightweight Data Transfer Object for Document listing operations.
 * Same as {@link DocumentDTO} but excludes the 'content' field to keep
 * paginated list responses small and efficient.
 * Used by GET /documents (paginated list) endpoint.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocumentSummaryDTO {

    private Long id;

    private String title;

    private String slug;

    private Long projectId;

    private List<Long> linkedDocuments;

    private java.time.LocalDateTime createdAt;

    private java.time.LocalDateTime updatedAt;
}
