package com.wiki4ai.controller;

import com.wiki4ai.dto.PermissionGrantRequestDTO;
import com.wiki4ai.dto.ProjectDTO;
import com.wiki4ai.dto.UserPermissionDTO;
import com.wiki4ai.model.Permission;
import com.wiki4ai.model.Project;
import com.wiki4ai.repository.ProjectRepository;
import com.wiki4ai.service.PermissionService;
import com.wiki4ai.service.ProjectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
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
    private final ProjectService projectService;

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
            description = "Returns all users and their permissions on this project. Requires an "
                    + "authenticated caller and that the project is visible to it: a private project "
                    + "belonging to somebody else is reported as 404, the same way the project "
                    + "endpoints report it."
    )
    @ApiResponse(responseCode = "200", description = "List of user permissions returned successfully",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = UserPermissionDTO.class))))
    @ApiResponse(responseCode = "401", description = "Not authenticated")
    @ApiResponse(responseCode = "403", description = "Missing READ permission")
    @ApiResponse(responseCode = "404", description = "Project not found, or private and not yours")
    public ResponseEntity<?> listPermissions(
            @Parameter(description = "Slug projektu") @PathVariable String projectSlug) {

        String username = getCurrentUsername();

        // WIKI4AI-197 commit 13: the member list used to be readable by literally anyone.
        // GET /api/v1/projects/** is permitAll in SecurityConfig, so an unauthenticated
        // visitor reached this method, getCurrentUsername() answered "anonymous", and
        // PermissionService.checkPermission exempts anonymous callers — so the check the
        // javadoc advertised had nothing behind it. Who has access to which project is
        // itself information: on a wiki where projects are public by default, this was a
        // directory of accounts and their roles.
        if (isAnonymous(username)) {
            return unauthorized();
        }

        // The visibility gate, not just a permission check: a private project of another
        // user must be 404 here, exactly as getProjectBySlug reports it, or the member
        // list would confirm that a project the caller was told does not exist does exist.
        ProjectDTO project = projectService.getProjectBySlug(projectSlug, username);

        // Kept deliberately: READ is universal for authenticated callers today, so this
        // is not what rejects anyone. It is the seam where a stricter READ rule lands
        // without touching this method again.
        permissionService.checkPermission(username, project.getId(), Permission.READ);

        List<UserPermissionDTO> permissions = permissionService.getAllProjectPermissions(project.getId());
        return ResponseEntity.ok(permissions);
    }

    /** Whether this caller carries no identity (WIKI4AI-197 commit 13). */
    private static boolean isAnonymous(String username) {
        return username == null
                || username.isBlank()
                || "anonymous".equals(username)
                || "anonymousUser".equals(username);
    }

    /**
     * The 401 body for a caller with no identity. Shaped like {@code GlobalExceptionHandler}
     * so a client parses one error shape for every failure, and returned rather than thrown
     * because this route is {@code permitAll} — the filter chain never gets the chance to
     * answer it, and an exception with no handler would surface as a 500.
     */
    private static ResponseEntity<Map<String, Object>> unauthorized() {
        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", java.time.LocalDateTime.now().toString());
        body.put("status", HttpStatus.UNAUTHORIZED.value());
        body.put("error", "Unauthorized");
        body.put("message", "Authentication required to list project members");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
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
