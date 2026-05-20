package com.wiki4ai.controller;

import com.wiki4ai.dto.UserDTO;
import com.wiki4ai.service.AdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
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

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
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
}
