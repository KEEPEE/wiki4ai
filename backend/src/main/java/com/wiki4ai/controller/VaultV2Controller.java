package com.wiki4ai.controller;

import com.wiki4ai.dto.VaultV2InitKeyRequestDTO;
import com.wiki4ai.dto.VaultV2MigrateRequestDTO;
import com.wiki4ai.dto.VaultV2MigrateResponseDTO;
import com.wiki4ai.dto.VaultV2ParamsResponseDTO;
import com.wiki4ai.model.User;
import com.wiki4ai.repository.UserRepository;
import com.wiki4ai.service.VaultV2Service;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * WIKI4AI-197 (D1): the v2 vault endpoints, kept apart from {@link VaultController}
 * so the v1 surface stays exactly as it is.
 *
 * <p>Every one of them is authenticated by the same JWT as the rest of the API and
 * scoped to the caller. None of them takes a password: the server is not given one,
 * which is the difference between v2 and v1.
 *
 * <p>Nothing here is called by the current frontend, and nothing it calls has changed
 * shape. That is what makes D1 deployable on its own — the schema, the endpoints and
 * the guards can all go out before any client uses them.
 */
@RestController
@RequestMapping("/api/v1/vault/v2")
@RequiredArgsConstructor
@Tag(name = "Vault v2", description = "Kľúčová hierarchia vaultu v2: init kľúča a postupné prešifrovanie (WIKI4AI-197)")
public class VaultV2Controller {

    private final VaultV2Service vaultV2Service;
    private final UserRepository userRepository;

    private Long getCurrentUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
            throw new AccessDeniedException("Authentication required");
        }
        return userRepository.findByUsername(auth.getName())
                .map(User::getId)
                .orElseThrow(() -> new AccessDeniedException("User not found"));
    }

    @Operation(summary = "Parametre vaultu v2", description = "Vráti salt, KDF parametre, zabalený DEK a otisk kľúča. Pred inicializáciou vráti initialized=false a zvyšok null.")
    @ApiResponse(responseCode = "200", description = "Parametre vrátené")
    @GetMapping("/params")
    public ResponseEntity<VaultV2ParamsResponseDTO> getParams() {
        return ResponseEntity.ok(vaultV2Service.getParams(getCurrentUserId()));
    }

    @Operation(summary = "Vygenerovať salt pre vault v2", description = "Write-once: salt vygeneruje len ak ešte žiadny nie je. Volajúci vždy dostane ten, ktorý platí.")
    @ApiResponse(responseCode = "200", description = "Platný salt (vygenerovaný alebo už existujúci)")
    @PostMapping("/init-salt")
    public ResponseEntity<VaultV2ParamsResponseDTO> initSalt() {
        return ResponseEntity.ok(vaultV2Service.initSalt(getCurrentUserId()));
    }

    @Operation(summary = "Uložiť zabalený DEK", description = "Write-once. Druhý pokus vráti 409 a klient si stiahne existujúci kľúč. V rovnakej transakcii nahradí v1 verifier sentinelom.")
    @ApiResponse(responseCode = "200", description = "Kľúč uložený")
    @ApiResponse(responseCode = "409", description = "Kľúč už existuje, alebo salt ešte nebol vyžiadaný")
    @PostMapping("/init-key")
    public ResponseEntity<VaultV2ParamsResponseDTO> initKey(@Valid @RequestBody VaultV2InitKeyRequestDTO request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(vaultV2Service.initKey(getCurrentUserId(), request));
    }

    @Operation(summary = "Přešifrovať dávku entries na v2", description = "Jediná transakcia: zámok riadkov, kontrola expectedV1Digest ako compare-and-set, inak 409 so zoznamom id a bez zmeny.")
    @ApiResponse(responseCode = "200", description = "Dávka prebehla")
    @ApiResponse(responseCode = "409", description = "Najmenej jeden riadok sa zmenil — nezmenená je celá dávka")
    @PostMapping("/migrate")
    public ResponseEntity<VaultV2MigrateResponseDTO> migrate(@Valid @RequestBody VaultV2MigrateRequestDTO request) {
        return ResponseEntity.ok(vaultV2Service.migrate(getCurrentUserId(), request));
    }
}
