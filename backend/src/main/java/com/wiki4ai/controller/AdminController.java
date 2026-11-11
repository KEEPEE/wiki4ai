package com.wiki4ai.controller;

import com.wiki4ai.dto.ChangeRoleRequestDTO;
import com.wiki4ai.dto.CreateUserRequestDTO;
import com.wiki4ai.dto.IntegrityReportDTO;
import com.wiki4ai.dto.ProjectOrderEntryDTO;
import com.wiki4ai.dto.ProjectOrderUpdateRequestDTO;
import com.wiki4ai.dto.UserDTO;
import com.wiki4ai.service.AdminService;
import com.wiki4ai.service.IntegrityService;
import com.wiki4ai.service.ProjectOrderAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller for admin endpoints.
 * All endpoints require ADMIN role. Access is verified in the service layer.
 */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Admin", description = "Administrator endpoints for user management")
public class AdminController {

    private final AdminService adminService;
    private final IntegrityService integrityService;
    private final ProjectOrderAdminService projectOrderAdminService;

    public AdminController(AdminService adminService, IntegrityService integrityService,
                           ProjectOrderAdminService projectOrderAdminService) {
        this.adminService = adminService;
        this.integrityService = integrityService;
        this.projectOrderAdminService = projectOrderAdminService;
    }

    /**
     * Get an integrity report for this instance (WIKI4AI-42, epic WIKI4AI-28):
     * per-table row counts plus canonical content checksums (slug-ordered, so two
     * instances with identical content produce identical checksums). Used to compare
     * source/target before and after a data migration. Admin access required.
     */
    @GetMapping("/integrity")
    @Operation(
            summary = "Get integrity report",
            description = "Returns per-table row counts and canonical content checksums for this instance. "
                    + "Admin access required."
    )
    @ApiResponse(responseCode = "200", description = "Integrity report returned successfully")
    @ApiResponse(responseCode = "401", description = "Authentication required - no valid JWT token provided")
    @ApiResponse(responseCode = "403", description = "Admin access required - current user does not have ADMIN role")
    public ResponseEntity<?> getIntegrityReport() {
        try {
            IntegrityReportDTO report = integrityService.getIntegrityReport();
            return ResponseEntity.ok(report);
        } catch (SecurityException e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());

            if (e.getMessage() != null && (e.getMessage().contains("Authentication required")
                    || e.getMessage().contains("not found in database"))) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
            }
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error);
        }
    }

    /**
     * Get a paginated list of all registered users.
     * Supports optional search by username/email and pagination parameters.
     * Only accessible to users with ADMIN role.
     *
     * @param pageable pagination (page, size, sort) - defaults to page=0, size=20, sorted by id asc
     * @param search   optional search term to filter users by username or email (case-insensitive)
     * @return paginated list of UserDTO with metadata (total pages, total elements, etc.)
     */
    @GetMapping("/users")
    @Operation(
            summary = "List all users",
            description = "Returns a paginated list of all registered users. Supports search by username/email. Admin access required."
    )
    @ApiResponse(responseCode = "200", description = "Users list returned successfully")
    @ApiResponse(responseCode = "401", description = "Authentication required - no valid JWT token provided")
    @ApiResponse(responseCode = "403", description = "Admin access required - current user does not have ADMIN role")
    public ResponseEntity<?> getAllUsers(
            @PageableDefault(size = 20, sort = "id") Pageable pageable,
            @RequestParam(required = false) String search) {

        try {
            Page<UserDTO> users = adminService.listUsers(pageable, search);
            return ResponseEntity.ok(users);
        } catch (SecurityException e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());

            // Determine if it's an auth or authorization issue
            if (e.getMessage().contains("Authentication required") || e.getMessage().contains("not found in database")) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
            } else {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error);
            }
        }
    }

    /**
     * Create a new user with the specified credentials and role.
     * Only accessible to users with ADMIN role.
     *
     * @param request the creation request containing username, email, password, and role
     * @return the created UserDTO with 201 Created status
     */
    @PostMapping("/users")
    @Operation(
            summary = "Create a new user",
            description = "Creates a new user with the specified credentials and role. Admin access required."
    )
    @ApiResponse(responseCode = "201", description = "User created successfully")
    @ApiResponse(responseCode = "400", description = "Invalid request body - validation failed")
    @ApiResponse(responseCode = "401", description = "Authentication required - no valid JWT token provided")
    @ApiResponse(responseCode = "403", description = "Admin access required - current user does not have ADMIN role")
    @ApiResponse(responseCode = "409", description = "Conflict - username or email already exists")
    public ResponseEntity<?> createUser(@Valid @RequestBody CreateUserRequestDTO request) {
        try {
            UserDTO createdUser = adminService.createUser(request);
            return ResponseEntity.status(HttpStatus.CREATED).body(createdUser);
        } catch (SecurityException e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());

            // Determine if it's an auth or authorization issue
            if (e.getMessage().contains("Authentication required") || e.getMessage().contains("not found in database")) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
            } else {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error);
            }
        } catch (IllegalArgumentException e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
        }
    }

    /**
     * Change the role of an existing user.
     * Only accessible to users with ADMIN role.
     *
     * @param id      the ID of the user whose role should be changed
     * @param request the change role request containing the new role (ADMIN or USER)
     * @return the updated UserDTO with 200 OK status
     */
    @PutMapping("/users/{id}/role")
    @Operation(
            summary = "Change user role",
            description = "Changes the role of an existing user. Admin access required."
    )
    @ApiResponse(responseCode = "200", description = "User role updated successfully")
    @ApiResponse(responseCode = "400", description = "Invalid request body or cannot delete own account")
    @ApiResponse(responseCode = "401", description = "Authentication required - no valid JWT token provided")
    @ApiResponse(responseCode = "403", description = "Admin access required - current user does not have ADMIN role")
    @ApiResponse(responseCode = "404", description = "User not found")
    public ResponseEntity<?> changeUserRole(
            @PathVariable Long id,
            @Valid @RequestBody ChangeRoleRequestDTO request) {
        try {
            UserDTO updatedUser = adminService.changeUserRole(id, request);
            return ResponseEntity.ok(updatedUser);
        } catch (SecurityException e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());

            if (e.getMessage().contains("Authentication required") || e.getMessage().contains("not found in database")) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
            } else {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error);
            }
        } catch (IllegalArgumentException e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
        }
    }

    /**
     * Delete a user from the system.
     * Only accessible to users with ADMIN role.
     * An admin cannot delete their own account.
     *
     * @param id the ID of the user to delete
     * @return 204 No Content on success
     */
    @DeleteMapping("/users/{id}")
    @Operation(
            summary = "Delete a user",
            description = "Deletes a user from the database. Admin access required. Cannot delete your own account."
    )
    @ApiResponse(responseCode = "204", description = "User deleted successfully")
    @ApiResponse(responseCode = "400", description = "Cannot delete your own account")
    @ApiResponse(responseCode = "401", description = "Authentication required - no valid JWT token provided")
    @ApiResponse(responseCode = "403", description = "Admin access required - current user does not have ADMIN role")
    @ApiResponse(responseCode = "404", description = "User not found")
    public ResponseEntity<?> deleteUser(@PathVariable Long id) {
        try {
            adminService.deleteUser(id);
            return ResponseEntity.noContent().build();
        } catch (SecurityException e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());

            if (e.getMessage().contains("Authentication required") || e.getMessage().contains("not found in database")) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
            } else {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error);
            }
        } catch (IllegalArgumentException e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());

            // Self-deletion attempt returns 400 Bad Request
            if (e.getMessage().contains("cannot delete your own")) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
            } else {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
            }
        }
    }

    // ── Global project order (WIKI4AI-200) ────────────────────────────────────
    //
    // Since WIKI4AI-200 every user arranges their OWN dashboard, and the global
    // projects.pinned / projects.sort_order columns became the default a user inherits
    // until they customise something. These two endpoints are what makes "inherits a
    // default" mean something: they are the only way to arrange the order a freshly
    // registered account sees. They never read or write user_project_order, so arranging
    // the default does not overwrite what individual users have already chosen.

    /**
     * Show the GLOBAL project order — the default every user who has customised nothing
     * inherits. Admin only.
     */
    @GetMapping("/projects-order")
    @Operation(
            summary = "Get the global project order",
            description = "Returns every project in the global manual order (pinned first, then "
                    + "sort_order, then id). This is the default a user who has pinned or dragged "
                    + "nothing inherits; it is not any individual user's view. Admin access required."
    )
    @ApiResponse(responseCode = "200", description = "Global project order returned")
    @ApiResponse(responseCode = "401", description = "Authentication required - no valid JWT token provided")
    @ApiResponse(responseCode = "403", description = "Admin access required - current user does not have ADMIN role")
    public ResponseEntity<?> getGlobalProjectOrder() {
        try {
            List<ProjectOrderEntryDTO> order = projectOrderAdminService.getGlobalOrder();
            return ResponseEntity.ok(order);
        } catch (SecurityException e) {
            return adminOrderError(e);
        }
    }

    /**
     * Write the GLOBAL project order. Partial per entry: a null {@code pinned} or
     * {@code sortOrder} leaves that value alone, and a project absent from the payload
     * keeps both. Admin only.
     */
    @PutMapping("/projects-order")
    @Operation(
            summary = "Set the global project order",
            description = "Writes the global pinned flag and manual position of the listed projects. "
                    + "Users who have customised nothing see the result; users who have pinned or "
                    + "dragged something keep their own view. Admin access required."
    )
    @ApiResponse(responseCode = "200", description = "The resulting global project order")
    @ApiResponse(responseCode = "400", description = "Empty payload, or an entry without a slug")
    @ApiResponse(responseCode = "401", description = "Authentication required - no valid JWT token provided")
    @ApiResponse(responseCode = "403", description = "Admin access required - current user does not have ADMIN role")
    @ApiResponse(responseCode = "404", description = "A slug does not resolve to a project")
    public ResponseEntity<?> setGlobalProjectOrder(
            @RequestBody ProjectOrderUpdateRequestDTO request) {
        try {
            List<ProjectOrderEntryDTO> order = projectOrderAdminService.setGlobalOrder(request);
            return ResponseEntity.ok(order);
        } catch (SecurityException e) {
            return adminOrderError(e);
        }
    }

    /**
     * The 401-vs-403 split every admin endpoint in this class makes: an absent or
     * unresolvable identity is an authentication failure, a wrong role is an
     * authorization one. Same wording rule as the endpoints above.
     */
    private ResponseEntity<Map<String, String>> adminOrderError(SecurityException e) {
        Map<String, String> error = new HashMap<>();
        error.put("error", e.getMessage());
        if (e.getMessage() != null && (e.getMessage().contains("Authentication required")
                || e.getMessage().contains("not found in database"))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error);
    }
}
