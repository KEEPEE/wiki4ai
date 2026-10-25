package com.wiki4ai.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Data Transfer Object for assigning an actor to a project (WIKI4AI-132).
 * Used exclusively for POST /api/v1/projects/{projectId}/actors requests.
 * <p>
 * The {@code projectId} comes from the path, never from the body. An unknown
 * role is rejected with 400 Bad Request; an omitted role defaults to 'viewer'.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectActorAssignDTO {

    /** Id of the actor to assign. Required. */
    @NotNull(message = "Actor id is required")
    private Long actorId;

    /** 'viewer' (default) | 'worker' | 'planner' | 'admin'. */
    @Size(max = 16, message = "Role must be at most 16 characters")
    private String role;
}
