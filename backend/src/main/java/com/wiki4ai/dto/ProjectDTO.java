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

    /**
     * Id of the user who created this project (WIKI4AI-99). Null for legacy
     * projects created before the owner concept existed. Read-only: clients
     * cannot set it — the backend always assigns the authenticated creator.
     */
    private Long ownerId;

    /**
     * 'public' or 'private' (WIKI4AI-99). Private projects are visible only to
     * their owner and ADMIN users. Defaults to 'public' when omitted on create
     * (backward compatible).
     */
    private String visibility;

    private java.time.LocalDateTime createdAt;

    private java.time.LocalDateTime updatedAt;
}
