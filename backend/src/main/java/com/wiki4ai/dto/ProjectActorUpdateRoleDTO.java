package com.wiki4ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Data Transfer Object for changing the role of an assigned actor
 * (WIKI4AI-132). Used exclusively for
 * PUT /api/v1/projects/{projectId}/actors/{actorId} requests.
 * <p>
 * Unlike the PATCH-like update DTOs elsewhere in this API, a role change must
 * carry the new role: an empty payload has nothing to change and is rejected
 * with 400 Bad Request.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectActorUpdateRoleDTO {

    /** New role: 'viewer' | 'worker' | 'planner' | 'admin'. Required. */
    @NotBlank(message = "Role is required")
    @Size(max = 16, message = "Role must be at most 16 characters")
    private String role;
}
