package com.wiki4ai.dto;

import com.wiki4ai.model.Permission;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * DTO representing a user's permissions on a specific project.
 * Used in GET responses from the PermissionController.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserPermissionDTO {

    private String username;
    private List<Permission> permissions;
}
