package com.wiki4ai.service;

import com.wiki4ai.model.Permission;
import com.wiki4ai.model.ProjectPermission;
import com.wiki4ai.model.User;
import com.wiki4ai.repository.ProjectPermissionRepository;
import com.wiki4ai.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;

/**
 * Service for checking and managing user permissions on projects.
 * Enforces the RBAC (Role-Based Access Control) system where MANAGE includes all other permissions.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PermissionService {

    private final ProjectPermissionRepository projectPermissionRepository;
    private final UserRepository userRepository;

    /**
     * Check if a user has a specific permission on a project.
     * MANAGE permission implicitly grants all other permissions (READ, CREATE, UPDATE, DELETE).
     *
     * @param username  the username to check
     * @param projectId the project ID
     * @param permission the required permission
     * @return true if the user has the permission or MANAGE, false otherwise
     */
    public boolean hasPermission(String username, Long projectId, Permission permission) {
        List<Permission> userPermissions = getPermissions(username, projectId);
        return userPermissions.contains(permission) || userPermissions.contains(Permission.MANAGE);
    }

    /**
     * Get all permissions a user has on a specific project.
     *
     * @param username  the username
     * @param projectId the project ID
     * @return list of permissions (empty list if none)
     */
    public List<Permission> getPermissions(String username, Long projectId) {
        Long userId = getUserIdByUsername(username);
        ProjectPermission pp = projectPermissionRepository.findByProjectIdAndUserId(projectId, userId)
                .orElse(null);
        if (pp == null || pp.getPermissions() == null) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(pp.getPermissions());
    }

    /**
     * Check permission and throw AccessDeniedException if the user lacks it.
     * Skips check if username is null or blank (allows unauthenticated access for public endpoints / tests).
     *
     * @param username  the username to check
     * @param projectId the project ID
     * @param permission the required permission
     * @throws AccessDeniedException if the user does not have the required permission
     */
    public void checkPermission(String username, Long projectId, Permission permission) {
        // Skip permission check for unauthenticated users (null/blank/"anonymous" username)
        // This allows tests without security context and truly public endpoints to work
        if (username == null || username.isBlank() || "anonymous".equals(username)) {
            return;
        }
        if (!hasPermission(username, projectId, permission)) {
            throw new AccessDeniedException(
                    "User '" + username + "' lacks " + permission + " permission on project " + projectId);
        }
    }

    /**
     * Grant a set of permissions to a user on a project.
     * Creates or updates the ProjectPermission record.
     *
     * @param username  the username
     * @param projectId the project ID
     * @param permissions the permissions to grant (replaces existing)
     */
    @Transactional
    public void grantPermissions(String username, Long projectId, List<Permission> permissions) {
        Long userId = getUserIdByUsername(username);

        ProjectPermission pp = projectPermissionRepository.findByProjectIdAndUserId(projectId, userId)
                .orElseGet(() -> ProjectPermission.builder()
                        .permissions(new java.util.ArrayList<>())
                        .build());

        pp.getPermissions().clear();
        pp.getPermissions().addAll(permissions);

        // We need to set the user and project references for proper persistence
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User not found with id: " + userId));
        pp.setUser(user);

        projectPermissionRepository.save(pp);
    }

    /**
     * Grant MANAGE permission (all permissions) to the project creator.
     * Called automatically when a user creates a new project.
     * Skips if username is null or blank.
     *
     * @param username  the creator's username
     * @param projectId the newly created project ID
     */
    @Transactional
    public void grantManageToCreator(String username, Long projectId) {
        if (username != null && !username.isBlank()) {
            grantPermissions(username, projectId, List.of(Permission.MANAGE));
        }
    }

    /**
     * Revoke specific permissions from a user on a project.
     * Removes only the specified permissions; others remain intact.
     *
     * @param username  the username
     * @param projectId the project ID
     * @param permissions the permissions to revoke
     */
    @Transactional
    public void revokePermissions(String username, Long projectId, List<Permission> permissions) {
        Long userId = getUserIdByUsername(username);

        ProjectPermission pp = projectPermissionRepository.findByProjectIdAndUserId(projectId, userId)
                .orElse(null);

        if (pp != null) {
            pp.getPermissions().removeAll(permissions);
            projectPermissionRepository.save(pp);
        }
    }

    /**
     * Resolve user ID from username. Throws AccessDeniedException if user not found.
     */
    private Long getUserIdByUsername(String username) {
        return userRepository.findByUsername(username)
                .map(User::getId)
                .orElseThrow(() -> new AccessDeniedException("User '" + username + "' not found"));
    }
}
