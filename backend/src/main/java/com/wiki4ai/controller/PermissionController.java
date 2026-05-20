package com.wiki4ai.controller;

import com.wiki4ai.dto.PermissionGrantRequestDTO;
import com.wiki4ai.dto.UserPermissionDTO;
import com.wiki4ai.model.Permission;
import com.wiki4ai.model.Project;
import com.wiki4ai.repository.ProjectRepository;
import com.wiki4ai.service.PermissionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST Controller for managing user permissions on projects.
 * Endpoints are nested under /api/v1/projects/{projectSlug}/permissions.
 * Only users with MANAGE permission can grant or revoke other permissions.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectSlug}/permissions")
@RequiredArgsConstructor
@Tag(name = "Permissions", description = "API pre správu oprávnení používateľov na projektoch")
public class PermissionController {

    private final PermissionService permissionService;
    private final ProjectRepository projectRepository;

    /**
     * Get the current authenticated username from SecurityContext.
     * Returns "anonymous" if no authentication is present (for backward compatibility with tests).
     */
    private String getCurrentUsername() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getName() != null) {
            return auth.getName();
        }
        return "anonymous";
    }

    /**
     * Resolve project by slug. Throws 404 if not found.
     */
    private Project getProjectBySlug(String slug) {
        return projectRepository.findBySlug(slug)
                .orElseThrow(() -> new EntityNotFoundException("Project with slug '" + slug + "' not found"));
    }

    // ── GET: List all users with their permissions on a project ───────────────

    @GetMapping
    @Operation(
            summary = "List all user permissions",
            description = "Returns all users and their permissions on this project. Requires READ permission."
    )
    @ApiResponse(responseCode = "200", description = "List of user permissions returned successfully")
    @ApiResponse(responseCode = "403", description = "Missing READ permission")
    @ApiResponse(responseCode = "404", description = "Project not found")
    public ResponseEntity<List<UserPermissionDTO>> listPermissions(
            @Parameter(description = "Slug projektu") @PathVariable String projectSlug) {

        Project project = getProjectBySlug(projectSlug);
        String username = getCurrentUsername();

        // Require READ permission to view permissions list
        permissionService.checkPermission(username, project.getId(), Permission.READ);

        List<UserPermissionDTO> permissions = permissionService.getAllProjectPermissions(project.getId());
        return ResponseEntity.ok(permissions);
    }

    // ── POST: Grant permissions to a user ─────────────────────────────────────

    @PostMapping
    @Operation(
            summary = "Grant permissions to a user",
            description = "Grants the specified permissions to a user on this project. Requires MANAGE permission."
    )
    @ApiResponse(responseCode = "201", description = "Permissions granted successfully")
    @ApiResponse(responseCode = "403", description = "Missing MANAGE permission or cannot grant more than you have")
    @ApiResponse(responseCode = "404", description = "Project not found or user not found")
    public ResponseEntity<?> grantPermissions(
            @Parameter(description = "Slug projektu") @PathVariable String projectSlug,
            @Valid @RequestBody PermissionGrantRequestDTO request) {

        Project project = getProjectBySlug(projectSlug);
        String requesterUsername = getCurrentUsername();

        try {
            permissionService.validateGrantPermission(
                    requesterUsername, project.getId(),
                    request.getUsername(), request.getPermissions());

            permissionService.grantPermissions(
                    request.getUsername(), project.getId(), request.getPermissions());

            Map<String, String> response = new HashMap<>();
            response.put("message", "Permissions granted to '" + request.getUsername() + "'");
            return ResponseEntity.status(HttpStatus.CREATED).body(response);

        } catch (AccessDeniedException e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error);
        }
    }

    // ── PUT: Update a user's permissions ──────────────────────────────────────

    @PutMapping("/{username}")
    @Operation(
            summary = "Update a user's permissions",
            description = "Replaces all of a user's permissions on this project. Requires MANAGE permission."
    )
    @ApiResponse(responseCode = "200", description = "Permissions updated successfully")
    @ApiResponse(responseCode = "403", description = "Missing MANAGE permission or cannot grant more than you have")
    @ApiResponse(responseCode = "404", description = "Project not found or user not found")
    public ResponseEntity<?> updatePermissions(
            @Parameter(description = "Slug projektu") @PathVariable String projectSlug,
            @Parameter(description = "Username whose permissions to update") @PathVariable String username,
            @Valid @RequestBody PermissionGrantRequestDTO request) {

        Project project = getProjectBySlug(projectSlug);
        String requesterUsername = getCurrentUsername();

        try {
            permissionService.validateGrantPermission(
                    requesterUsername, project.getId(),
                    username, request.getPermissions());

            // Update replaces all existing permissions
            permissionService.grantPermissions(username, project.getId(), request.getPermissions());

            Map<String, String> response = new HashMap<>();
            response.put("message", "Permissions updated for '" + username + "'");
            return ResponseEntity.ok(response);

        } catch (AccessDeniedException e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error);
        }
    }

    // ── DELETE: Revoke all permissions for a user ─────────────────────────────

    @DeleteMapping("/{username}")
    @Operation(
            summary = "Revoke all permissions for a user",
            description = "Removes all permissions from a user on this project. Requires MANAGE permission."
    )
    @ApiResponse(responseCode = "200", description = "Permissions revoked successfully")
    @ApiResponse(responseCode = "403", description = "Missing MANAGE permission or cannot revoke (self-lockout prevention)")
    @ApiResponse(responseCode = "404", description = "Project not found or user not found")
    public ResponseEntity<?> revokeAllPermissions(
            @Parameter(description = "Slug projektu") @PathVariable String projectSlug,
            @Parameter(description = "Username whose permissions to revoke") @PathVariable String username) {

        Project project = getProjectBySlug(projectSlug);
        String requesterUsername = getCurrentUsername();

        try {
            permissionService.validateRevokePermission(
                    requesterUsername, project.getId(), username);

            permissionService.revokeAllPermissions(username, project.getId());

            Map<String, String> response = new HashMap<>();
            response.put("message", "All permissions revoked from '" + username + "'");
            return ResponseEntity.ok(response);

        } catch (AccessDeniedException e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error);
        }
    }
}
