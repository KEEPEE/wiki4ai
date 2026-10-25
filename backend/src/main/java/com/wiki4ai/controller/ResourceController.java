package com.wiki4ai.controller;

import com.wiki4ai.dto.ResourceDTO;
import com.wiki4ai.dto.ResourceRequestDTO;
import com.wiki4ai.dto.ReservationDTO;
import com.wiki4ai.dto.ReservationRequestDTO;
import com.wiki4ai.service.ResourceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST controller for the capacity ledger of WIKI4AI-155..157.
 * <p>
 * The controller authorizes nothing and enforces nothing: access (ADMIN user or
 * {@code admin}-scope token for the ledger, a usable actor for holding it), the duplicate
 * key, the capacity race and the release idempotency all live in {@code ResourceService}
 * and are translated to statuses by {@code GlobalExceptionHandler}
 * ({@code IllegalArgumentException} → 409, {@code EntityNotFoundException} → 404,
 * {@code AccessDeniedException} → 403, {@code BadRequestException} → 400). SecurityConfig
 * needs no change: {@code /api/v1/resources/**} and {@code /api/v1/reservations/**} fall
 * under {@code anyRequest().authenticated()}.
 *
 * <h2>Why the path variable is a key and not an id</h2>
 * A resource is addressed by its {@code key} everywhere a client touches capacity —
 * {@code GET|PUT|DELETE /api/v1/resources/{key}} and
 * {@code GET /api/v1/resources/{key}/reservations}. The numeric id is returned in every
 * response, but it is not the name a caller has to know: a client that wants the last
 * runner does not have to look up a row id first. {@code POST /api/v1/reservations/{id}/release}
 * is the one exception, because a reservation is identified by the id its reservation
 * response returned.
 * <p>
 * There is no class-level {@code @RequestMapping}: the release endpoint lives under a
 * different prefix, so every method carries its full path.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Resources", description = "Kapacitné zdroje, ich atómové rezervácie a uvoľňovanie (WIKI4AI-155 až 157)")
public class ResourceController {

    private final ResourceService resourceService;

    /**
     * Current authenticated username from the SecurityContext, "anonymous" when there is
     * none — the convention every other controller here uses, and the value the services
     * map to "no user".
     */
    private String getCurrentUsername() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getName() != null) {
            return auth.getName();
        }
        return "anonymous";
    }

    @Operation(summary = "Zoznam zdrojov", description =
            "Vráti všetky zdroje a ich aktuálne obsadené sloty. Čítanie ledgera je neobmedzené.")
    @ApiResponse(responseCode = "200", description = "Zoznam načítaný")
    @ApiResponse(responseCode = "401", description = "Neeauthentizovaný volajúci")
    @GetMapping("/api/v1/resources")
    public ResponseEntity<List<ResourceDTO>> listResources() {
        return ResponseEntity.ok(resourceService.list());
    }

    @Operation(summary = "Vytvoriť zdroj", description =
            "Len ADMIN user alebo token so scope 'admin'. Duplicitný key → 409. "
                    + "Neplatný key/name/capacity → 400. capacity nesmie byť menšia ako 1.")
    @ApiResponse(responseCode = "201", description = "Zdroj vytvorený")
    @ApiResponse(responseCode = "400", description = "Neplatný key, name alebo capacity")
    @ApiResponse(responseCode = "403", description = "Volajúci nie je ADMIN a nemá admin scope")
    @ApiResponse(responseCode = "409", description = "Key už existuje")
    @PostMapping("/api/v1/resources")
    public ResponseEntity<ResourceDTO> createResource(@RequestBody ResourceRequestDTO request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(resourceService.create(request, getCurrentUsername()));
    }

    @Operation(summary = "Zobraziť zdroj", description = "Vráti zdroj podľa kľúča.")
    @ApiResponse(responseCode = "200", description = "Zdroj načítaný")
    @ApiResponse(responseCode = "404", description = "Zdroj s týmto kľúčom neexistuje")
    @GetMapping("/api/v1/resources/{key}")
    public ResponseEntity<ResourceDTO> getResource(
            @Parameter(description = "Kľúč zdroja") @PathVariable String key) {
        return ResponseEntity.ok(resourceService.get(key));
    }

    @Operation(summary = "Upraviť zdroj", description =
            "PATCH sémantika. Len ADMIN user alebo token so scope 'admin'. Duplicitný key → 409. "
                    + "Zníženie capacity pod aktuálne obsadené sloty → 409 (invariant sa nesmie dať porušiť PUT-om).")
    @ApiResponse(responseCode = "200", description = "Zdroj upravený")
    @ApiResponse(responseCode = "400", description = "Neplatná hodnota pola")
    @ApiResponse(responseCode = "403", description = "Volajúci nie je ADMIN a nemá admin scope")
    @ApiResponse(responseCode = "404", description = "Zdroj neexistuje")
    @ApiResponse(responseCode = "409", description = "Key obsadený alebo capacity pod obsadenými slotmi")
    @PutMapping("/api/v1/resources/{key}")
    public ResponseEntity<ResourceDTO> updateResource(
            @Parameter(description = "Kľúč zdroja") @PathVariable String key,
            @RequestBody ResourceRequestDTO request) {
        return ResponseEntity.ok(resourceService.update(key, request, getCurrentUsername()));
    }

    @Operation(summary = "Zmazať zdroj", description =
            "Len ADMIN user alebo token so scope 'admin'. Zdroj s aktívnymi rezerváciami → 409 "
                    + "(rezervácie sa musia uvoľniť prv, než ledger zmizne). 204 pri úspechu.")
    @ApiResponse(responseCode = "204", description = "Zdroj zmazaný")
    @ApiResponse(responseCode = "403", description = "Volajúci nie je ADMIN a nemá admin scope")
    @ApiResponse(responseCode = "404", description = "Zdroj neexistuje")
    @ApiResponse(responseCode = "409", description = "Zdroj má aktívne rezervácie")
    @DeleteMapping("/api/v1/resources/{key}")
    public ResponseEntity<Void> deleteResource(
            @Parameter(description = "Kľúč zdroja") @PathVariable String key) {
        resourceService.delete(key, getCurrentUsername());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Rezervácie zdroja", description =
            "Vráti rezervácie zdroja podľa kľúča — živé aj uvoľnené, newest first. "
                    + "releasedAt = null znamená, že slot je obsadený.")
    @ApiResponse(responseCode = "200", description = "Zoznam načítaný")
    @ApiResponse(responseCode = "404", description = "Zdroj neexistuje")
    @GetMapping("/api/v1/resources/{key}/reservations")
    public ResponseEntity<List<ReservationDTO>> listReservations(
            @Parameter(description = "Kľúč zdroja") @PathVariable String key) {
        return ResponseEntity.ok(resourceService.listReservations(key));
    }

    @Operation(summary = "Rezervovať kapacitu", description =
            "Atómová rezervácia: podmienka je súčasťou jedného UPDATE, preto dva súběžné "
                    + "pokusy o posledný slot nemôžu vyhrať oba. 201 pri úspechu. "
                    + "Zdroj disabled → 409 (správa hovorí 'disabled'), nedostatok kapacity → 409 "
                    + "(správa hovorí 'capacity'). slots <= 0 alebo nečíslo → 400. "
                    + "Bez actora → 403. Neexistujúci zdroj → 404.")
    @ApiResponse(responseCode = "201", description = "Rezervácia vytvorená")
    @ApiResponse(responseCode = "400", description = "slots nie je kladné celé číslo")
    @ApiResponse(responseCode = "403", description = "Kredential nevymenuje aktora")
    @ApiResponse(responseCode = "404", description = "Zdroj neexistuje")
    @ApiResponse(responseCode = "409", description = "Zdroj je vypnutý alebo nemá dostatočnú kapacitu")
    @PostMapping("/api/v1/resources/{key}/reserve")
    public ResponseEntity<ReservationDTO> reserve(
            @Parameter(description = "Kľúč zdroja") @PathVariable String key,
            @RequestBody(required = false) ReservationRequestDTO request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(resourceService.reserve(key, request, getCurrentUsername()));
    }

    @Operation(summary = "Uvoľniť rezerváciu", description =
            "Manuálne uvoľnenie. Idempotentné: druhý pokus je 204 bez zmeny a active_slots "
                    + "sa zníži presne raz. Neexistujúca rezervácia → 404.")
    @ApiResponse(responseCode = "204", description = "Uvoľnené (aj keď už bolo uvoľnené skôr)")
    @ApiResponse(responseCode = "403", description = "Rezerváciu vlastní iný actor")
    @ApiResponse(responseCode = "404", description = "Rezervácia neexistuje")
    @PostMapping("/api/v1/reservations/{id}/release")
    public ResponseEntity<Void> release(
            @Parameter(description = "id rezervácie") @PathVariable Long id) {
        resourceService.releaseReservation(id, getCurrentUsername());
        return ResponseEntity.noContent().build();
    }
}
