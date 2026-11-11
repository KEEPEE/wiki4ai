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

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.List;

/**
 * REST Controller for Vault entry CRUD operations.
 * All endpoints require JWT authentication and enforce ownership-based access control.
 */
@RestController
@RequestMapping("/api/v1/vault")
@RequiredArgsConstructor
@Slf4j
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

    @Operation(summary = "Nastaviť master password", description = "Uloží hash master password a šifrovací salt pre používateľa. Hash aj derivácia kľúča prebiehajú na klientovi (PBKDF2). Po v2 initu sa master password nastavuje cez vault v2 — toto vráti 409.")
    @ApiResponse(responseCode = "200", description = "Master password nastavené")
    @ApiResponse(responseCode = "409", description = "Vault je pod správou kľúčovej hierarchie v2; resetovanie by osirotilo entries")
    @PostMapping("/master-password/set")
    public ResponseEntity<Void> setMasterPassword(@RequestBody SetMasterPasswordRequestDTO request) {
        User user = getCurrentUser();

        // WIKI4AI-197 (D1): this endpoint overwrites the verifier AND the encryption
        // salt, unconditionally, because that is what a first-time setup needs. Once a
        // v2 wrapped key exists the salt it was built from is no longer ours to change:
        // re-deriving from a new salt leaves the wrapped DEK unrecoverable and every
        // entry in the vault with it.
        //
        // Refusing is the whole fix. Deleting the old verifier instead would read as
        // "no master password set" to the current frontend, which sends the user to
        // the setup screen and lands them right back here.
        if (user.getVaultV2WrappedKey() != null
                || masterPasswordService.isV2Managed(user.getVaultMasterPasswordHash())) {
            throw new IllegalArgumentException(
                    "Vault master password is managed by vault crypto v2; "
                            + "change it from the v2 vault screen, which re-wraps the key");
        }

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
            // WIKI4AI-197 (REV-2 P0 #10): the reason is not the caller's to be told.
            // "Invalid file format" is the whole answer a client can act on — re-read the
            // file, pick another one. What follows the colon used to be the library's own
            // words, and this is the endpoint that receives a vault file and a vault
            // password: a parser complaining about a block it could not decrypt can quote
            // bytes it was working with, and a message that quotes bytes from a secret
            // store has now leaked something on its way to a 400 body.
            //
            // The phrase "Invalid file format" is load-bearing beyond the wording:
            // GlobalExceptionHandler maps an IllegalArgumentException whose message
            // contains it to 400, while the same exception without it becomes 409. The
            // prefix stays; the appended detail goes.
            throw new IllegalArgumentException("Invalid file format");
        } catch (Exception e) {
            // Defense in depth: the KDBX parsing library can throw unchecked exceptions
            // (e.g. on a truncated or non-KDBX file) that VaultImportService doesn't
            // wrap. Surface those as a 400 instead of a raw 500.
            //
            // Logged rather than echoed: an operator still needs to know whether the file
            // was truncated, encrypted with a different KDF, or not a KDBX at all, and
            // that is a question for the server log, not for the response body.
            log.warn("KDBX import rejected an upload: {}", e.getClass().getName(), e);
            throw new IllegalArgumentException("Invalid file format");
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
