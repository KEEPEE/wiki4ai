package com.wiki4ai.controller;

import com.wiki4ai.dto.ImportResultDTO;
import com.wiki4ai.dto.MigrationExportDTO;
import com.wiki4ai.service.MigrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * REST controller for instance-wide data migration (WIKI4AI-41, epic WIKI4AI-28).
 *
 * <p>Both endpoints require a valid JWT. Export is readable by any authenticated user
 * (project content is already publicly readable in this app); import requires the
 * ADMIN role because it writes instance-wide data (and {@code full} mode wipes it).</p>
 */
@RestController
@RequestMapping("/api/v1/migration")
@RequiredArgsConstructor
@Tag(name = "Migration", description = "Instance-wide export/import between wiki4ai instances (schema-agnostic JSON)")
public class MigrationController {

    private final MigrationService migrationService;

    /**
     * Get the current authenticated username from SecurityContext.
     * Returns "anonymous" if no authentication is present (test mode).
     */
    private String getCurrentUsername() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getName() != null) {
            return auth.getName();
        }
        return "anonymous";
    }

    @GetMapping("/export")
    @Operation(
            summary = "Export all wiki data as a JSON archive",
            description = "Returns the full instance export: projects (with parent hierarchy), documents "
                    + "(title + body + slug), document links and user usernames. No secrets, no embeddings."
    )
    @ApiResponse(responseCode = "200", description = "Export archive returned")
    @ApiResponse(responseCode = "401", description = "Authentication required")
    public ResponseEntity<MigrationExportDTO> export() {
        return ResponseEntity.ok(migrationService.export());
    }

    @PostMapping("/import")
    @Operation(
            summary = "Import a JSON export archive into this instance",
            description = "Body must be the exact output of GET /api/v1/migration/export. "
                    + "mode=merge (default) creates only what is missing and never deletes anything; "
                    + "mode=full wipes all wiki content first and requires confirmFullRestore=true. "
                    + "Admin role required."
    )
    @ApiResponse(responseCode = "200", description = "Import finished — result contains ID mapping and integrity report")
    @ApiResponse(responseCode = "400", description = "Unknown mode, missing full-restore confirmation, or malformed archive")
    @ApiResponse(responseCode = "401", description = "Authentication required")
    @ApiResponse(responseCode = "403", description = "Admin access required")
    public ResponseEntity<?> importData(
            @RequestParam(defaultValue = MigrationService.MODE_MERGE) String mode,
            @RequestParam(required = false) Boolean confirmFullRestore,
            @RequestBody MigrationExportDTO exportArchive) {
        try {
            migrationService.verifyAdminRole(getCurrentUsername());
            ImportResultDTO result = migrationService.importData(exportArchive, mode, confirmFullRestore, getCurrentUsername());
            return ResponseEntity.ok(result);
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
}
