package com.wiki4ai.controller;

import com.wiki4ai.dto.ActorCreateDTO;
import com.wiki4ai.dto.ActorDTO;
import com.wiki4ai.dto.ActorHeartbeatDTO;
import com.wiki4ai.dto.ActorUpdateDTO;
import com.wiki4ai.dto.ActorWhoamiDTO;
import com.wiki4ai.service.ActorService;
import com.wiki4ai.service.ApiTokenService;
import com.wiki4ai.service.RequestActorResolver;
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
 * REST Controller for the actor registry (WIKI4AI-131).
 * Endpoints are versioned under /api/v1/actors.
 * <p>
 * Ownership: only the actor's owner or an ADMIN may update or delete an actor
 * (enforced in ActorService — the JWT filter grants ROLE_USER to everyone, so
 * nothing role-specific is configured here). Anyone else gets 403 Forbidden.
 */
@RestController
@RequestMapping("/api/v1/actors")
@RequiredArgsConstructor
@Tag(name = "Actors", description = "API pre register agentov — agent / human / service")
public class ActorController {

    private final ActorService actorService;

    /**
     * Token side of the actor API (WIKI4AI-137): the kill switch revokes an actor's
     * tokens, which is token business, so it goes through ApiTokenService — where the
     * ownership rule and the cache eviction live — and not through ActorService.
     */
    private final ApiTokenService apiTokenService;

    /**
     * Reads the caller's identity off the authenticated request (WIKI4AI-134).
     * The actor id used for attribution and for whoami comes from here and from
     * nowhere else — never from the body, a query parameter or a header.
     */
    private final RequestActorResolver requestActorResolver;

    /**
     * Get the current authenticated username from SecurityContext.
     * Returns "anonymous" if no authentication is present (for backward
     * compatibility with tests that run with security disabled).
     */
    private String getCurrentUsername() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getName() != null) {
            return auth.getName();
        }
        return "anonymous";
    }

    @Operation(summary = "Zoznam actorov", description =
            "Vráti actorov zoradených podľa handle. Voliteľný parameter status filtruje podľa " +
            "'active' | 'paused' | 'archived' (case-insensitive); neplatná hodnota vráti 400.")
    @ApiResponse(responseCode = "200", description = "Zoznam actorov úspešne načítaný")
    @ApiResponse(responseCode = "400", description = "Neplatná hodnota parametra status")
    @GetMapping
    public ResponseEntity<List<ActorDTO>> listActors(
            @Parameter(description = "Filter podľa statusu: active | paused | archived")
            @RequestParam(required = false) String status) {
        return ResponseEntity.ok(actorService.listActors(status, getCurrentUsername()));
    }

    @Operation(summary = "Vytvorenie nového actora", description =
            "Vytvorí actora. Vlastník (ownerUserId) je vždy aktuálny autentifikovaný user. " +
            "handle je povinné a globálne unikátne (normalizované na malé písmená); " +
            "duplicitný handle vráti 409. kind default 'agent', status default 'active'.")
    @ApiResponse(responseCode = "201", description = "Actor úspešne vytvorený")
    @ApiResponse(responseCode = "400", description = "Chýbajúci/neplatný handle, kind alebo status")
    @ApiResponse(responseCode = "409", description = "Actor s rovnakým handle už existuje")
    @PostMapping
    public ResponseEntity<ActorDTO> createActor(@Valid @RequestBody ActorCreateDTO dto) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(actorService.createActor(dto, getCurrentUsername()));
    }

    @Operation(summary = "Kto som (identita volajúceho)", description =
            "Vráti identitu volajúceho: user a — ak token nesie actorId — aj actor vrátane handle a kind. " +
            "Hodnota sa číta výhradne z overeného detailu tokena; body, query parameter ani header sa nečítajú. " +
            "Volajúci bez actora (legacy JWT, token bez actorId) dostane actorId a handle null — nie chybu.")
    @ApiResponse(responseCode = "200", description = "Identita vrátená (aj keď volajúci nemá actor)")
    @ApiResponse(responseCode = "401", description = "Neprihlásený volajúci")
    // Declared BEFORE @GetMapping("/{id}") and matched before it: Spring's path
    // resolution prefers the literal segment over the path variable, so
    // GET /api/v1/actors/whoami never reaches the Long conversion of /{id}.
    @GetMapping("/whoami")
    public ResponseEntity<ActorWhoamiDTO> whoami() {
        return ResponseEntity.ok(actorService.whoami(
                getCurrentUsername(), requestActorResolver.currentIdentity()));
    }

    @Operation(summary = "Detail actora podľa ID", description =
            "Vráti detail jedného actora. Actoria sú zdieľaný register — čítať môže každý autentifikovaný user.")
    @ApiResponse(responseCode = "200", description = "Actor úspešne načítaný")
    @ApiResponse(responseCode = "404", description = "Actor s daným ID neexistuje")
    @GetMapping("/{id}")
    public ResponseEntity<ActorDTO> getActor(
            @Parameter(description = "ID actora") @PathVariable Long id) {
        return ResponseEntity.ok(actorService.getActor(id, getCurrentUsername()));
    }

    @Operation(summary = "Aktualizácia actora", description =
            "Aktualizuje actora. PATCH-like správanie: null polia = bez zmeny. " +
            "Len vlastník alebo ADMIN (inak 403). Premenovanie na handle iného actora vráti 409.")
    @ApiResponse(responseCode = "200", description = "Actor úspešne aktualizovaný")
    @ApiResponse(responseCode = "400", description = "Neplatný handle, kind alebo status")
    @ApiResponse(responseCode = "403", description = "Actor nevlastníš — update len vlastník alebo ADMIN")
    @ApiResponse(responseCode = "404", description = "Actor s daným ID neexistuje")
    @ApiResponse(responseCode = "409", description = "Handle už používa iný actor")
    @PutMapping("/{id}")
    public ResponseEntity<ActorDTO> updateActor(
            @Parameter(description = "ID actora") @PathVariable Long id,
            @Valid @RequestBody ActorUpdateDTO dto) {
        return ResponseEntity.ok(actorService.updateActor(id, dto, getCurrentUsername()));
    }

    @Operation(summary = "Vymazanie actora", description =
            "Vymaže actora. Len vlastník alebo ADMIN (inak 403).")
    @ApiResponse(responseCode = "204", description = "Actor úspešne vymazaný")
    @ApiResponse(responseCode = "403", description = "Actor nevlastníš — delete len vlastník alebo ADMIN")
    @ApiResponse(responseCode = "404", description = "Actor s daným ID neexistuje")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteActor(
            @Parameter(description = "ID actora") @PathVariable Long id) {
        actorService.deleteActor(id, getCurrentUsername());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Heartbeat actora", description =
            "Nahlási, že actor je živý. Telom je voliteľné status ('idle' | 'working' | 'busy') a note " +
            "(max 256 znakov); obe polia môžu chýbať. Vlastník actora alebo ADMIN (inak 403). " +
            "Archivovaný actor → 400. Druhé volanie v throttle okne (30 s) nič nezapíše, ale vráti 204 — " +
            "rozhodnutie o throttlingu nie je signál pre klienta.")
    @ApiResponse(responseCode = "204", description = "Heartbeat prijatý (zapisaný alebo preskočený throttle oknom)")
    @ApiResponse(responseCode = "400", description = "Neplatná presence status, note dlhší ako 256 znakov alebo archivovaný actor")
    @ApiResponse(responseCode = "403", description = "Actor nevlastníš — heartbeat len vlastník alebo ADMIN")
    @ApiResponse(responseCode = "404", description = "Actor s daným ID neexistuje")
    @PostMapping("/{id}/heartbeat")
    public ResponseEntity<Void> heartbeat(
            @Parameter(description = "ID actora") @PathVariable Long id,
            @RequestBody(required = false) @Valid ActorHeartbeatDTO dto) {
        actorService.heartbeat(id, dto, getCurrentUsername());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Kill switch: revokovať všetky tokeny actora", description =
            "WIKI4AI-137: nastaví revoked_at na všetky tokeny daného actora naraz a evictne "
                    + "validačnú cache každého z nich — znefunkční teda aj tokeny, ktoré práve "
                    + "slúžia z cache. Riadky zostávajú v DB pre audit. Vlastník actora alebo "
                    + "ADMIN, inak 404 (API nepotvrdí, že actor existuje). Idempotentné: druhé "
                    + "volanie je opäť 204 a mení už nulu riadkov.")
    @ApiResponse(responseCode = "204", description = "Všetky tokeny actora revokované")
    @ApiResponse(responseCode = "404", description = "Actor neexistuje alebo nepatrí tebe")
    @ApiResponse(responseCode = "401", description = "Neprihlásený volajúci")
    @DeleteMapping("/{id}/tokens")
    public ResponseEntity<Void> revokeActorTokens(
            @Parameter(description = "ID actora") @PathVariable Long id) {
        apiTokenService.revokeActorTokens(id, getCurrentUsername());
        return ResponseEntity.noContent().build();
    }
}
