package com.wiki4ai.controller;

import com.wiki4ai.dto.GlobalSearchResultDTO;
import com.wiki4ai.service.DocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST controller for the global (cross-project) document search (WIKI4AI-61).
 *
 * <p>Access policy (user decision 2026-09-20, P1): this endpoint is LOGIN-ONLY.
 * The path {@code /api/v1/search/**} deliberately does NOT match the
 * {@code GET /api/v1/projects/**} permitAll matcher in SecurityConfig, so every
 * request requires a valid JWT — anonymous requests get 401. Per-project search
 * ({@code GET /api/v1/projects/{slug}/documents/search}) is unchanged and stays public.</p>
 */
@RestController
@RequestMapping("/api/v1/search")
@RequiredArgsConstructor
@Tag(name = "Search", description = "Globálne (cross-project) hybridné vyhľadávanie dokumentov")
public class SearchController {

    /** Default result limit for global search. */
    static final int DEFAULT_LIMIT = 20;
    /** Hard cap for the result limit (payload protection). */
    static final int MAX_LIMIT = 50;

    private final DocumentService documentService;

    /**
     * Get the current authenticated username from SecurityContext.
     * Returns "anonymous" if no authentication is present (test mode with security disabled).
     */
    private String getCurrentUsername() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getName() != null) {
            return auth.getName();
        }
        return "anonymous";
    }

    @Operation(summary = "Globálne vyhľadávanie dokumentov",
            description = "Hybridné (text + semantické) vyhľadávanie po VŠETKÝCH projektoch. "
                    + "Vyžaduje platný JWT token (login-only).")
    @ApiResponse(responseCode = "200", description = "Výsledky globálneho vyhľadávania s atribúciou projektov")
    @ApiResponse(responseCode = "400", description = "Neplatný keyword (musí byť aspoň 2 znaky)")
    @ApiResponse(responseCode = "401", description = "Chýba platný JWT token")
    @GetMapping("/documents")
    public ResponseEntity<List<GlobalSearchResultDTO>> searchDocuments(
            @Parameter(description = "Kľúčové slovo na vyhľadávanie (min. 2 znaky)") @RequestParam(required = false) String keyword,
            @Parameter(description = "Maximálny počet výsledkov (default 20, max 50)") @RequestParam(required = false) Integer limit) {

        // Validate keyword — must be at least 2 characters (same rule as per-project search).
        if (keyword == null || keyword.trim().length() < 2) {
            return ResponseEntity.badRequest().build();
        }

        int effectiveLimit = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, MAX_LIMIT));
        String username = getCurrentUsername();
        List<GlobalSearchResultDTO> results = documentService.searchDocumentsGlobal(keyword.trim(), username, effectiveLimit);
        return ResponseEntity.ok(results);
    }
}
