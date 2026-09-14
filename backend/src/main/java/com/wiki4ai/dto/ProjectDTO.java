package com.wiki4ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Data Transfer Object for Project entity.
 * Used for API request/response payloads.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectDTO {

    private Long id;

    @NotBlank(message = "Project name is required")
    @Size(max = 255, message = "Project name must be at most 255 characters")
    private String name;

    @Size(max = 1000, message = "Description must be at most 1000 characters")
    private String description;

    private String slug;

    /**
     * Slug of the parent project, or null for root projects (WIKI4AI-29).
     */
    private String parentSlug;

    /**
     * Hierarchy depth: root projects have depth 1, their children depth 2, etc.
     * Maximum allowed depth is 5 (WIKI4AI-29).
     */
    private int depth;

    private int documentCount;

    private java.time.LocalDateTime createdAt;

    private java.time.LocalDateTime updatedAt;
}
