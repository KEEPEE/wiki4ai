package com.wiki4ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Data Transfer Object for creating a new Project.
 * Used exclusively for POST /api/v1/projects requests.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectCreateDTO {

    @NotBlank(message = "Project name is required")
    @Size(max = 255, message = "Project name must be at most 255 characters")
    private String name;

    @Size(max = 1000, message = "Description must be at most 1000 characters")
    private String description;

    /**
     * Optional id of the parent project. When set, the new project is created as a
     * subproject of the given project (hierarchy depth limited to 5, WIKI4AI-29).
     * Null/absent = root project (backward compatible).
     */
    private Long parentId;
}
