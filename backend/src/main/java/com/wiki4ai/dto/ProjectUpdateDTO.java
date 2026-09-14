package com.wiki4ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Data Transfer Object for updating an existing Project.
 * Used exclusively for PUT /api/v1/projects/{slug} requests.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectUpdateDTO {

    @NotBlank(message = "Project name is required")
    @Size(max = 255, message = "Project name must be at most 255 characters")
    private String name;

    @Size(max = 1000, message = "Description must be at most 1000 characters")
    private String description;

    /**
     * Optional parent project id used to MOVE the project in the hierarchy (WIKI4AI-30).
     * Semantics are driven by {@link #parentIdPresent}:
     * <ul>
     *   <li>key absent from JSON ({@code parentIdPresent == false}) → no move, keep current parent</li>
     *   <li>{@code "parentId": null} ({@code parentIdPresent == true}) → move back to root</li>
     *   <li>{@code "parentId": 42} → move under project with id 42 (validated: depth ≤ 5, no cycles)</li>
     * </ul>
     */
    private Long parentId;

    /**
     * True when the JSON payload explicitly contained a "parentId" key.
     * Populated by Jackson via the custom setter below; not serialized.
     */
    @JsonIgnore
    private boolean parentIdPresent;

    /**
     * Custom setter so that Jackson can distinguish between an explicit
     * {@code "parentId": null} (move to root) and an absent key (no move).
     * Lombok skips generating a setter when one is already defined.
     */
    public void setParentId(Long parentId) {
        this.parentId = parentId;
        this.parentIdPresent = true;
    }
}
