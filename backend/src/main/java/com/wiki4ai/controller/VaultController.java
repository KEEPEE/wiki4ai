package com.wiki4ai.controller;

import com.wiki4ai.dto.EncryptedVaultEntryDTO;
import com.wiki4ai.dto.SetMasterPasswordRequestDTO;
import com.wiki4ai.dto.VaultSaltResponseDTO;
import com.wiki4ai.dto.VerifyMasterPasswordRequestDTO;
import com.wiki4ai.dto.VaultEntryImportDTO;
import com.wiki4ai.dto.VaultEntryRequestDTO;
import com.wiki4ai.dto.VaultEntryResponseDTO;
import com.wiki4ai.model.User;
import com.wiki4ai.repository.UserRepository;
import com.wiki4ai.service.VaultImportService;
import com.wiki4ai.service.VaultMasterPasswordService;
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
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
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
    private final VaultMasterPasswordService masterPasswordService;
    private final VaultImportService importService;

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

    /**
     * Get the current authenticated User entity from SecurityContext.
     */
    private User getCurrentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
            throw new AccessDeniedException("Authentication required");
        }

        String username = auth.getName();
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new AccessDeniedException("User not found"));
    }

    @Operation(summary = "Vytvoriť novú vault entry", description = "Vytvorí novú šifrovanú vault entry pre prihláseného používateľa.")
    @ApiResponse(responseCode = "201", description = "Entry úspešne vytvorená")
    @ApiResponse(responseCode = "400", description = "Neplatný vstup (chýbajúce povinné polia)")
    @PostMapping("/entries")
    public ResponseEntity<VaultEntryResponseDTO> createEntry(@RequestBody VaultEntryRequestDTO dto) {
        Long userId = getCurrentUserId();
        VaultEntryResponseDTO created = vaultService.createEntry(userId, dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @Operation(summary = "Zoznam všetkých entries pre používateľa", description = "Vráti všetky šifrované vault entry pre prihláseného používateľa.")
    @ApiResponse(responseCode = "200", description = "Zoznam entries úspešne načítaný")
    @GetMapping("/entries")
    public ResponseEntity<List<VaultEntryResponseDTO>> getEntries() {
        Long userId = getCurrentUserId();
        List<VaultEntryResponseDTO> entries = vaultService.getEntriesByUser(userId);
        return ResponseEntity.ok(entries);
    }

    @Operation(summary = "Detail entry podľa ID", description = "Vráti detail špecifickej vault entry. Vyžaduje vlastníctvo entry.")
    @ApiResponse(responseCode = "200", description = "Entry úspešne načítaná")
    @ApiResponse(responseCode = "403", description = "Entry nepatrí prihlásenému používateľovi")
    @ApiResponse(responseCode = "404", description = "Entry nebol nájdená")
    @GetMapping("/entries/{id}")
    public ResponseEntity<VaultEntryResponseDTO> getEntry(
            @Parameter(description = "ID vault entry") @PathVariable Long id) {
        Long userId = getCurrentUserId();
        VaultEntryResponseDTO entry = vaultService.getEntryById(id, userId);
        return ResponseEntity.ok(entry);
    }

    @Operation(summary = "Upraviť existujúcu entry", description = "Aktualizuje existujúcu vault entry. Vyžaduje vlastníctvo entry.")
    @ApiResponse(responseCode = "200", description = "Entry úspešne aktualizovaná")
    @ApiResponse(responseCode = "403", description = "Entry nepatrí prihlásenému používateľovi")
    @ApiResponse(responseCode = "404", description = "Entry nebol nájdená")
    @PutMapping("/entries/{id}")
    public ResponseEntity<VaultEntryResponseDTO> updateEntry(
            @Parameter(description = "ID vault entry") @PathVariable Long id,
            @RequestBody VaultEntryRequestDTO dto) {
        Long userId = getCurrentUserId();
        VaultEntryResponseDTO updated = vaultService.updateEntry(id, userId, dto);
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
    public ResponseEntity<List<VaultEntryResponseDTO>> searchEntries(
            @Parameter(description = "Hľadaný text (title alebo URL)") @RequestParam String q,
            @Parameter(description = "Filter podľa group_path prefixu") @RequestParam(required = false) String groupPath) {
        Long userId = getCurrentUserId();
        List<VaultEntryResponseDTO> results = vaultService.searchEntries(userId, q, groupPath);
        return ResponseEntity.ok(results);
    }

    @Operation(summary = "Skontrolovať stav master password", description = "Vráti informáciu či má používateľ nastavené master password pre vault.")
    @ApiResponse(responseCode = "200", description = "Stav master password")
    @GetMapping("/master-password/status")
    public ResponseEntity<Boolean> getMasterPasswordStatus() {
        User user = getCurrentUser();
        boolean isSet = masterPasswordService.isPasswordSet(user.getVaultMasterPasswordHash());
        return ResponseEntity.ok(isSet);
    }

    @Operation(summary = "Nastaviť master password", description = "Uloží hash master password a šifrovací salt pre používateľa. Hash aj derivácia kľúča prebiehajú na klientovi (PBKDF2).")
    @ApiResponse(responseCode = "200", description = "Master password nastavené")
    @PostMapping("/master-password/set")
    public ResponseEntity<Void> setMasterPassword(@RequestBody SetMasterPasswordRequestDTO request) {
        User user = getCurrentUser();
        String hash = masterPasswordService.hashPassword(request.getMasterPasswordHash());
        user.setVaultMasterPasswordHash(hash);
        if (request.getSalt() != null && !request.getSalt().isBlank()) {
            user.setVaultSalt(request.getSalt());
        }
        userRepository.save(user);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Získať vault encryption salt", description = "Vráti Base64 salt použitý na odvodenie šifrovacieho kľúča vaultu. Salt nie je tajný údaj - slúži len na PBKDF2 deriváciu, nie na autentifikáciu.")
    @ApiResponse(responseCode = "200", description = "Salt nájdený")
    @ApiResponse(responseCode = "404", description = "Salt nie je nastavený (vault ešte nebol nastavený, alebo predchádza tejto funkcii)")
    @GetMapping("/master-password/salt")
    public ResponseEntity<VaultSaltResponseDTO> getVaultSalt() {
        User user = getCurrentUser();
        if (user.getVaultSalt() == null || user.getVaultSalt().isBlank()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(VaultSaltResponseDTO.builder().salt(user.getVaultSalt()).build());
    }

    @Operation(summary = "Overiť master password", description = "Overí či je zadaný hash správny pre používateľa.")
    @ApiResponse(responseCode = "200", description = "Master password je správne")
    @ApiResponse(responseCode = "401", description = "Master password je nesprávne")
    @PostMapping("/master-password/verify")
    public ResponseEntity<Void> verifyMasterPassword(@RequestBody VerifyMasterPasswordRequestDTO request) {
        User user = getCurrentUser();

        if (!masterPasswordService.verifyPassword(request.getMasterPasswordHash(), user.getVaultMasterPasswordHash())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Import entries z KDBX súboru", description = "Načíta a dešifruje KDBX súbor, vráti entries ako JSON pre ďalší import do vaultu.")
    @ApiResponse(responseCode = "200", description = "Entries úspešne importované")
    @ApiResponse(responseCode = "400", description = "Neplatný súbor alebo heslo")
    @PostMapping("/import/kdbx")
    public ResponseEntity<List<VaultEntryImportDTO>> importFromKdbx(
            @Parameter(description = "KDBX súbor na import") @RequestParam("file") MultipartFile file,
            @Parameter(description = "Heslo pre KDBX súbor") @RequestParam("password") String password) {

        if (file.isEmpty()) {
            throw new IllegalArgumentException("Invalid file format: no file provided");
        }

        try {
            List<VaultEntryImportDTO> entries = importService.importFromKdbx(file.getInputStream(), password);
            return ResponseEntity.ok(entries);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid file format: " + e.getMessage());
        }
    }

    @Operation(summary = "Export entries do CSV/JSON", description = "Vráti všetky šifrované vault entry pre prihláseného používateľa. Backend vracia encrypted blobs, frontend dešifruje a formátuje.")
    @ApiResponse(responseCode = "200", description = "Encrypted entries na export")
    @GetMapping("/export")
    public ResponseEntity<List<VaultEntryResponseDTO>> exportEntries(
            @Parameter(description = "Formát exportu (csv alebo json) - backend vracia vždy JSON encrypted data, frontend formátuje")
            @RequestParam(required = false, defaultValue = "json") String format) {
        Long userId = getCurrentUserId();
        List<VaultEntryResponseDTO> entries = vaultService.getEntriesByUser(userId);
        return ResponseEntity.ok(entries);
    }
}
