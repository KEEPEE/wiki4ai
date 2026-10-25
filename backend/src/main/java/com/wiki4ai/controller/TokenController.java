package com.wiki4ai.controller;

import com.wiki4ai.dto.ApiTokenCreatedDTO;
import com.wiki4ai.dto.ApiTokenDTO;
import com.wiki4ai.dto.TokenCreateRequestDTO;
import com.wiki4ai.service.ApiTokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for API tokens (WIKI4AI-135 / WIKI4AI-136).
 * Endpoints live under {@code /api/v1/tokens}, which falls under
 * {@code anyRequest().authenticated()} in SecurityConfig — the configuration is
 * deliberately unchanged, and ownership is enforced in {@link ApiTokenService}.
 * <p>
 * The legacy endpoints under {@code /api/v1/auth/token*} stay exactly as they are:
 * they issue and list clear-text JWTs, and existing clients keep working. This
 * controller is the new, hashed, revocable path.
 * <p>
 * The plaintext token appears only in the 201 response of {@code POST /tokens}.
 * No read endpoint returns a plaintext or a hash.
 */
@RestController
@RequestMapping("/api/v1/tokens")
@RequiredArgsConstructor
@Tag(name = "API tokens", description = "Hashované API tokeny — vytvorenie, zoznam, revokácia")
public class TokenController {

    private final ApiTokenService apiTokenService;

    /**
     * Current authenticated username from the SecurityContext. "anonymous" when no
     * authentication is present, which the service turns into a 403 on write paths.
     */
    private String getCurrentUsername() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getName() != null) {
            return auth.getName();
        }
        return "anonymous";
    }

    @Operation(summary = "Vytvoriť API token", description =
            "Vytvorí nový hashovaný API token (w4a_ + 32 znakov base62). V DB sa uloží iba "
                    + "SHA-256 hash a zobrazovací prefix — plaintext sa vráti práve tu a nikdy viac. "
                    + "Voliteľné: expiresAt (null = nekonečná platnosť), scopes (default read write), "
                    + "actorId (actor, za ktorého token koná). "
                    + "Admin scope na token viazaný na agenta vráti 400.")
    @ApiResponse(responseCode = "201", description = "Token vytvorený — plaintext vrátený iba raz")
    @ApiResponse(responseCode = "400", description = "Chýbajúci name, neznáma scope, neplatný actorId, expiresAt v minulosti")
    @ApiResponse(responseCode = "401", description = "Neprihlásený volajúci")
    @PostMapping
    public ResponseEntity<ApiTokenCreatedDTO> createToken(
            @Valid @RequestBody TokenCreateRequestDTO request) {
        ApiTokenCreatedDTO created = apiTokenService.createToken(request, getCurrentUsername());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @Operation(summary = "Zoznam API tokenov", description =
            "Tokeny aktuálneho usera, newest first. Bez plaintextu a bez hashu — id, name, "
                    + "prefix, scopes, expiresAt, revokedAt, lastUsedAt, createdAt. "
                    + "Legacy tokeny (čistý JWT) sú zahrnuté, bez prefixu a scopes.")
    @ApiResponse(responseCode = "200", description = "Zoznam tokenov")
    @ApiResponse(responseCode = "401", description = "Neprihlásený volajúci")
    @GetMapping
    public ResponseEntity<List<ApiTokenDTO>> listTokens() {
        return ResponseEntity.ok(apiTokenService.listTokens(getCurrentUsername()));
    }

    @Operation(summary = "Detail API tokenu", description =
            "Detail jedného tokenu. Cudzí token je vrátený ako 404 — API nikdy nepotvrdí, "
                    + "že dané ID patrí niekomu inému.")
    @ApiResponse(responseCode = "200", description = "Token načítaný")
    @ApiResponse(responseCode = "404", description = "Token neexistuje alebo nepatrí tebe")
    @ApiResponse(responseCode = "401", description = "Neprihlásený volajúci")
    @GetMapping("/{id}")
    public ResponseEntity<ApiTokenDTO> getToken(
            @Parameter(description = "ID tokenu") @PathVariable Long id) {
        return ResponseEntity.ok(apiTokenService.getToken(id, getCurrentUsername()));
    }

    @Operation(summary = "Rotovať API token", description =
            "WIKI4AI-137: v jednej transakcii revokuje starý token a vytvorí nový s rovnakým "
                    + "name, scopes, actorId a expiresAt. Vznikne teda iba stav, kde starý už neplatí "
                    + "a nový už áno — nikdy naopak. Plaintext nového tokenu sa vráti práve tu a iba raz. "
                    + "Rotácia platí len pre hashované tokeny: legacy JWT riadok (token_hash null) je 400, "
                    + "lebo jeho čistý text sa nedá znovuvydať. Už revokovaný token je 409, cudzí token je 404.")
    @ApiResponse(responseCode = "201", description = "Nový token vytvorený, starý revokovaný — plaintext vrátený iba raz")
    @ApiResponse(responseCode = "400", description = "Legacy token nie je rotovateľný (chýba token_hash)")
    @ApiResponse(responseCode = "404", description = "Token neexistuje alebo nepatrí tebe")
    @ApiResponse(responseCode = "409", description = "Token je už revokovaný")
    @ApiResponse(responseCode = "401", description = "Neprihlásený volajúci")
    @PostMapping("/{id}/rotate")
    public ResponseEntity<ApiTokenCreatedDTO> rotateToken(
            @Parameter(description = "ID rotovaného tokenu") @PathVariable Long id) {
        ApiTokenCreatedDTO rotated = apiTokenService.rotateToken(id, getCurrentUsername());
        return ResponseEntity.status(HttpStatus.CREATED).body(rotated);
    }

    @Operation(summary = "Revokovať API token", description =
            "Soft revokácia: nastaví revoked_at a evictnu validačnú cache, takže ďalší request "
                    + "s týmto tokenom dostane 401 okamžite. Riadok zostáva v DB pre audit. "
                    + "Opakovaná revokácia je 204. Cudzí token je 404.")
    @ApiResponse(responseCode = "204", description = "Token revokovaný")
    @ApiResponse(responseCode = "404", description = "Token neexistuje alebo nepatrí tebe")
    @ApiResponse(responseCode = "401", description = "Neprihlásený volajúci")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revokeToken(
            @Parameter(description = "ID tokenu") @PathVariable Long id) {
        apiTokenService.revokeToken(id, getCurrentUsername());
        return ResponseEntity.noContent().build();
    }
}
