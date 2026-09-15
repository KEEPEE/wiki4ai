package com.wiki4ai.controller;

import com.wiki4ai.service.EmbeddingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Admin endpoints for the embedding backfill (WIKI4AI-35, epic WIKI4AI-26).
 * All endpoints require the ADMIN role — verified in {@link EmbeddingService}.
 */
@RestController
@RequestMapping("/api/v1/admin/embeddings")
@Tag(name = "Embeddings Admin", description = "Administrator endpoints for document embeddings")
public class EmbeddingAdminController {

    private final EmbeddingService embeddingService;

    public EmbeddingAdminController(EmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
    }

    /**
     * Start an asynchronous backfill of document embeddings.
     *
     * <p>The backfill walks all documents in batches of 4, skipping documents
     * that already have an embedding unless {@code force=true}. It runs in the
     * background (a full re-embed takes minutes) — poll
     * {@code GET /api/v1/admin/embeddings/backfill/status} for progress and the
     * final embedded count.</p>
     */
    @PostMapping("/backfill")
    @Operation(
            summary = "Start embedding backfill (async)",
            description = "Embeds all documents without an embedding (or all with force=true). "
                    + "Runs in the background; use the status endpoint to follow progress. Admin access required."
    )
    @ApiResponse(responseCode = "202", description = "Backfill started")
    @ApiResponse(responseCode = "401", description = "Authentication required - no valid JWT token provided")
    @ApiResponse(responseCode = "403", description = "Admin access required - current user does not have ADMIN role")
    @ApiResponse(responseCode = "409", description = "A backfill is already running")
    public ResponseEntity<?> startBackfill(@RequestParam(defaultValue = "false") boolean force) {
        try {
            embeddingService.verifyAdminRole();
        } catch (SecurityException e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());
            if (e.getMessage().contains("Authentication required") || e.getMessage().contains("not found in database")) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
            }
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error);
        }

        boolean started = embeddingService.startBackfill(force);
        if (!started) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "A backfill is already running");
            return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
        }

        Map<String, Object> body = new HashMap<>();
        body.put("status", "started");
        body.put("force", force);
        body.put("message", "Backfill running in the background; poll /api/v1/admin/embeddings/backfill/status");
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
    }

    /**
     * Current backfill progress (running flag, processed/total, embedded count,
     * last error, start/finish timestamps).
     */
    @GetMapping("/backfill/status")
    @Operation(
            summary = "Get backfill status",
            description = "Live progress of the embedding backfill. Admin access required."
    )
    @ApiResponse(responseCode = "200", description = "Status returned")
    @ApiResponse(responseCode = "401", description = "Authentication required - no valid JWT token provided")
    @ApiResponse(responseCode = "403", description = "Admin access required - current user does not have ADMIN role")
    public ResponseEntity<?> backfillStatus() {
        try {
            embeddingService.verifyAdminRole();
        } catch (SecurityException e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());
            if (e.getMessage().contains("Authentication required") || e.getMessage().contains("not found in database")) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
            }
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error);
        }
        return ResponseEntity.ok(embeddingService.getStatus());
    }
}
