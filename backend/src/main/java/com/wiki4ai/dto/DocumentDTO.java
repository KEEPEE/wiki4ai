package com.wiki4ai.dto;

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
}
