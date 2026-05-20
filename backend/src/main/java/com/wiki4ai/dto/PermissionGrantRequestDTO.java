package com.wiki4ai.dto;

import com.wiki4ai.model.Permission;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * DTO for granting or updating permissions on a project.
 * Used in POST and PUT requests to the PermissionController.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PermissionGrantRequestDTO {

    @NotBlank(message = "Username is required")
    private String username;

    @NotEmpty(message = "At least one permission is required")
    private List<Permission> permissions;
}
