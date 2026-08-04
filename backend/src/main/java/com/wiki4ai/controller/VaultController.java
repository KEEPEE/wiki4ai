package com.wiki4ai.controller;

import com.wiki4ai.dto.EncryptedVaultEntryDTO;
import com.wiki4ai.repository.UserRepository;
import com.wiki4ai.service.VaultService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST Controller for Vault entry CRUD operations.
 * All endpoints require JWT authentication and enforce ownership-based access control.
 */
@RestController
@RequestMapping("/api/v1/vault")
@RequiredArgsConstructor
@Tag(name = "Vault", description = "API pre správu šifrovaných vault entries (heslá, tokeny)")
public class VaultController {

    private final VaultService vaultService;
    private final UserRepository userRepository;

    /**
     * Get the current authenticated user ID from SecurityContext.
     */
    private Long getCurrentUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
            throw new AccessDeniedException("Authentication required");
        }

        String username = auth.getName();
        return userRepository.findByUsername(username)
                .map(u -> u.getId())
                .orElseThrow(() -> new AccessDeniedException("User not found"));
    }

    @Operation(summary = "Vytvoriť novú vault entry", description = "Vytvorí novú šifrovanú vault entry pre prihláseného používateľa.")
    @ApiResponse(responseCode = "201", description = "Entry úspešne vytvorená")
    @ApiResponse(responseCode = "400", description = "Neplatný vstup (chýbajúce povinné polia)")
    @PostMapping("/entries")
    public ResponseEntity<EncryptedVaultEntryDTO> createEntry(@RequestBody EncryptedVaultEntryDTO dto) {
        Long userId = getCurrentUserId();
        EncryptedVaultEntryDTO created = vaultService.createEntry(userId, dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @Operation(summary = "Zoznam všetkých entries pre používateľa", description = "Vráti všetky šifrované vault entry pre prihláseného používateľa.")
    @ApiResponse(responseCode = "200", description = "Zoznam entries úspešne načítaný")
    @GetMapping("/entries")
    public ResponseEntity<List<EncryptedVaultEntryDTO>> getEntries() {
        Long userId = getCurrentUserId();
        List<EncryptedVaultEntryDTO> entries = vaultService.getEntriesByUser(userId);
        return ResponseEntity.ok(entries);
    }

    @Operation(summary = "Detail entry podľa ID", description = "Vráti detail špecifickej vault entry. Vyžaduje vlastníctvo entry.")
    @ApiResponse(responseCode = "200", description = "Entry úspešne načítaná")
    @ApiResponse(responseCode = "403", description = "Entry nepatrí prihlásenému používateľovi")
    @ApiResponse(responseCode = "404", description = "Entry nebol nájdená")
    @GetMapping("/entries/{id}")
    public ResponseEntity<EncryptedVaultEntryDTO> getEntry(
            @Parameter(description = "ID vault entry") @PathVariable Long id) {
        Long userId = getCurrentUserId();
        EncryptedVaultEntryDTO entry = vaultService.getEntryById(id, userId);
        return ResponseEntity.ok(entry);
    }

    @Operation(summary = "Upraviť existujúcu entry", description = "Aktualizuje existujúcu vault entry. Vyžaduje vlastníctvo entry.")
    @ApiResponse(responseCode = "200", description = "Entry úspešne aktualizovaná")
    @ApiResponse(responseCode = "403", description = "Entry nepatrí prihlásenému používateľovi")
    @ApiResponse(responseCode = "404", description = "Entry nebol nájdená")
    @PutMapping("/entries/{id}")
    public ResponseEntity<EncryptedVaultEntryDTO> updateEntry(
            @Parameter(description = "ID vault entry") @PathVariable Long id,
            @RequestBody EncryptedVaultEntryDTO dto) {
        Long userId = getCurrentUserId();
        EncryptedVaultEntryDTO updated = vaultService.updateEntry(id, userId, dto);
        return ResponseEntity.ok(updated);
    }

    @Operation(summary = "Zmazať entry", description = "Vymaže vault entry. Vyžaduje vlastníctvo entry.")
    @ApiResponse(responseCode = "204", description = "Entry úspešne vymazaná")
    @ApiResponse(responseCode = "403", description = "Entry nepatrí prihlásenému používateľovi")
    @ApiResponse(responseCode = "404", description = "Entry nebol nájdená")
    @DeleteMapping("/entries/{id}")
    public ResponseEntity<Void> deleteEntry(
            @Parameter(description = "ID vault entry") @PathVariable Long id) {
        Long userId = getCurrentUserId();
        vaultService.deleteEntry(id, userId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Vyhľadať entries", description = "Hľadanie vault entries podľa title a URL. Voliteľný filter podľa group_path prefixu.")
    @ApiResponse(responseCode = "200", description = "Výsledky vyhľadávania")
    @GetMapping("/search")
    public ResponseEntity<List<EncryptedVaultEntryDTO>> searchEntries(
            @Parameter(description = "Hľadaný text (title alebo URL)") @RequestParam String q,
            @Parameter(description = "Filter podľa group_path prefixu") @RequestParam(required = false) String groupPath) {
        Long userId = getCurrentUserId();
        List<EncryptedVaultEntryDTO> results = vaultService.searchEntries(userId, q, groupPath);
        return ResponseEntity.ok(results);
    }
}
