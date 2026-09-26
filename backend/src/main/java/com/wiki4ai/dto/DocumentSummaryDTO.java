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

    /**
     * Id of the user who created this document (WIKI4AI-99). Null for legacy
     * documents. Read-only — assigned by the backend on create.
     */
    private Long ownerId;

    /**
     * 'public' or 'private' (WIKI4AI-99). Private documents are listed only for
     * their owner and ADMIN users.
     */
    private String visibility;

    private List<Long> linkedDocuments;

    private java.time.LocalDateTime createdAt;

    private java.time.LocalDateTime updatedAt;
}
