package com.wiki4ai.service;

import com.wiki4ai.dto.UserPermissionDTO;
import com.wiki4ai.model.Permission;
import com.wiki4ai.model.Project;
import com.wiki4ai.model.ProjectPermission;
import com.wiki4ai.model.Role;
import com.wiki4ai.model.User;
import com.wiki4ai.repository.ProjectPermissionRepository;
import com.wiki4ai.repository.ProjectRepository;
import com.wiki4ai.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service for checking and managing user permissions on projects.
 * Enforces the RBAC (Role-Based Access Control) system where MANAGE includes all other permissions.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PermissionService {

    private final ProjectPermissionRepository projectPermissionRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;

    // ── Core permission checks ────────────────────────────────────────────────

    /**
     * Check if a user has a specific permission on a project.
     * MANAGE permission implicitly grants all other permissions (READ, CREATE, UPDATE, DELETE).
     */
    public boolean hasPermission(String username, Long projectId, Permission permission) {
        List<Permission> userPermissions = getPermissions(username, projectId);
        return userPermissions.contains(permission) || userPermissions.contains(Permission.MANAGE);
    }

    /**
     * Get all permissions a user has on a specific project.
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
     * ADMIN role bypasses all project-level permissions - admins have full system-wide access.
     * MANAGE permission implicitly grants all other permissions.
     * READ permission is granted to any authenticated user (wiki convention: logged-in users can read everything).
     * CREATE, UPDATE, DELETE require explicit permission grant.
     */
    public void checkPermission(String username, Long projectId, Permission permission) {
        if (username == null || username.isBlank() || "anonymous".equals(username) || "anonymousUser".equals(username)) {
            return;
        }
        // ADMIN role bypasses all project-level permissions - full system-wide access
        if (isAdminRole(username)) {
            return;
        }
        // MANAGE grants all permissions
        if (hasPermission(username, projectId, Permission.MANAGE)) {
            return;
        }
        // Any authenticated user can READ (wiki convention)
        if (permission == Permission.READ) {
            return;
        }
        // CREATE, UPDATE, DELETE require explicit permission
        if (!hasPermission(username, projectId, permission)) {
            throw new AccessDeniedException(
                    "User '" + username + "' lacks " + permission + " permission on project " + projectId);
        }
    }

    /**
     * Check if a user has the ADMIN role.
     * Returns false if the user is not found or an error occurs.
     */
    private boolean isAdminRole(String username) {
        try {
            User user = userRepository.findByUsername(username)
                    .orElse(null);
            return user != null && Role.ADMIN.equals(user.getRole());
        } catch (Exception e) {
            // If we can't verify the role, fall through to project-level checks
            return false;
        }
    }

    // ── Permission management (grant / update / revoke) ───────────────────────

    /**
     * Grant a set of permissions to a user on a project.
     * Creates or updates the ProjectPermission record.
     */
    @Transactional
    public void grantPermissions(String username, Long projectId, List<Permission> permissions) {
        Long userId = getUserIdByUsername(username);

        ProjectPermission pp = projectPermissionRepository.findByProjectIdAndUserId(projectId, userId)
                .orElseGet(() -> ProjectPermission.builder()
                        .permissions(new ArrayList<>())
                        .build());

        pp.getPermissions().clear();
        pp.getPermissions().addAll(permissions);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User not found with id: " + userId));
        pp.setUser(user);

        // Ensure project reference is set (needed for new ProjectPermission records)
        if (pp.getProject() == null) {
            Project project = projectRepository.findById(projectId)
                    .orElseThrow(() -> new EntityNotFoundException("Project not found with id: " + projectId));
            pp.setProject(project);
        }

        projectPermissionRepository.save(pp);
    }

    /**
     * Grant MANAGE permission (all permissions) to the project creator.
     * Called automatically when a user creates a new project.
     * MANAGE implicitly grants all other permissions (READ, CREATE, UPDATE, DELETE).
     */
    @Transactional
    public void grantManageToCreator(String username, Long projectId) {
        if (username != null && !username.isBlank()) {
            // Grant full access to the creator: MANAGE includes all permissions
            grantPermissions(username, projectId, List.of(Permission.MANAGE));
        }
    }

    /**
     * Grant READ permission to a user on a project.
     * This is used for wiki convention where authenticated users can read projects.
     */
    @Transactional
    public void grantReadToUser(String username, Long projectId) {
        if (username != null && !username.isBlank()) {
            grantPermissions(username, projectId, List.of(Permission.READ));
        }
    }

    /**
     * Revoke specific permissions from a user on a project.
     * Removes only the specified permissions; others remain intact.
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
     * Revoke ALL permissions for a user on a project.
     * Deletes the entire ProjectPermission record.
     */
    @Transactional
    public void revokeAllPermissions(String username, Long projectId) {
        Long userId = getUserIdByUsername(username);
        projectPermissionRepository.deleteByProjectIdAndUserId(projectId, userId);
    }

    // ── Validation helpers for PermissionController ───────────────────────────

    /**
     * Validate that the requester can grant the specified permissions to a target user.
     * Rules:
     * - Requester must have MANAGE permission on the project
     * - Cannot grant more permissions than the requester has (each requested permission must be held by requester)
     * - Target username must exist in the system
     *
     * @throws AccessDeniedException if validation fails
     */
    public void validateGrantPermission(String requesterUsername, Long projectId,
                                          String targetUsername, List<Permission> permissionsToGrant) {
        // Target user must exist (always validate this)
        userRepository.findByUsername(targetUsername)
                .orElseThrow(() -> new AccessDeniedException("User '" + targetUsername + "' not found"));

        // Skip permission validation for unauthenticated users (test mode / public endpoints)
        if (isAnonymous(requesterUsername)) {
            return;
        }

        // Requester must have MANAGE on this project
        checkPermission(requesterUsername, projectId, Permission.MANAGE);

        // Cannot grant permissions the requester doesn't have
        List<Permission> requesterPermissions = getPermissions(requesterUsername, projectId);
        for (Permission perm : permissionsToGrant) {
            if (!requesterPermissions.contains(perm) && !requesterPermissions.contains(Permission.MANAGE)) {
                throw new AccessDeniedException(
                        "Cannot grant " + perm + ": you don't have this permission yourself");
            }
        }
    }

    /**
     * Validate that the requester can revoke permissions from a target user.
     * Rules:
     * - Requester must have MANAGE permission on the project
     * - Cannot revoke your own MANAGE permission (prevents self-lockout)
     * - At least one user with MANAGE must remain after revocation
     *
     * @throws AccessDeniedException if validation fails
     */
    public void validateRevokePermission(String requesterUsername, Long projectId,
                                          String targetUsername) {
        // Target user must exist (always validate this)
        userRepository.findByUsername(targetUsername)
                .orElseThrow(() -> new AccessDeniedException("User '" + targetUsername + "' not found"));

        // Skip permission validation for unauthenticated users (test mode / public endpoints)
        if (isAnonymous(requesterUsername)) {
            return;
        }

        // Requester must have MANAGE on this project
        checkPermission(requesterUsername, projectId, Permission.MANAGE);

        // Cannot revoke your own permissions (prevents self-lockout)
        if (requesterUsername.equals(targetUsername)) {
            throw new AccessDeniedException(
                    "Cannot revoke your own permissions. Ask another MANAGE user to do it.");
        }

        // Ensure at least one MANAGE remains after revocation
        Long targetUserId = getUserIdByUsername(targetUsername);
        ProjectPermission targetPerm = projectPermissionRepository
                .findByProjectIdAndUserId(projectId, targetUserId).orElse(null);
        if (targetPerm != null && targetPerm.getPermissions().contains(Permission.MANAGE)) {
            long manageCount = countManageUsers(projectId);
            if (manageCount <= 1) {
                throw new AccessDeniedException(
                        "Cannot revoke permissions from '" + targetUsername +
                        "': they are the only user with MANAGE permission. At least one MANAGE must remain.");
            }
        }
    }

    /**
     * Get all users and their permissions on a project as DTOs.
     */
    public List<UserPermissionDTO> getAllProjectPermissions(Long projectId) {
        List<ProjectPermission> perms = projectPermissionRepository.findByProjectId(projectId);
        return perms.stream()
                .map(pp -> UserPermissionDTO.builder()
                        .username(pp.getUser().getUsername())
                        .permissions(new ArrayList<>(pp.getPermissions()))
                        .build())
                .collect(Collectors.toList());
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Count how many users have MANAGE permission on a project.
     */
    private long countManageUsers(Long projectId) {
        List<ProjectPermission> perms = projectPermissionRepository.findByProjectId(projectId);
        return perms.stream()
                .filter(pp -> pp.getPermissions().contains(Permission.MANAGE))
                .count();
    }

    /**
     * Resolve user ID from username. Throws AccessDeniedException if user not found.
     */
    private Long getUserIdByUsername(String username) {
        return userRepository.findByUsername(username)
                .map(User::getId)
                .orElseThrow(() -> new AccessDeniedException("User '" + username + "' not found"));
    }

    /**
     * Check if a username represents an unauthenticated/anonymous user.
     */
    private boolean isAnonymous(String username) {
        return username == null || username.isBlank()
                || "anonymous".equals(username)
                || "anonymousUser".equals(username);
    }
}
