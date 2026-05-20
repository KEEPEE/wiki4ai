package com.wiki4ai.dto;

import com.wiki4ai.model.Role;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for changing a user's role.
 * Used by PUT /api/v1/admin/users/{id}/role endpoint.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChangeRoleRequestDTO {

    @NotNull(message = "Role is required")
    private Role role;
}
