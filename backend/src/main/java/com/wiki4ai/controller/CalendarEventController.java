package com.wiki4ai.controller;

import com.wiki4ai.dto.CalendarEventCreateDTO;
import com.wiki4ai.dto.CalendarEventDTO;
import com.wiki4ai.dto.CalendarEventUpdateDTO;
import com.wiki4ai.dto.EventTypeCreateDTO;
import com.wiki4ai.dto.EventTypeDTO;
import com.wiki4ai.service.CalendarEventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * REST Controller for calendar operations (WIKI4AI-96).
 * Provides endpoints for managing calendar events and event types with
 * Swagger documentation. All endpoints are versioned under /api/v1/calendar.
 * <p>
 * Visibility: private events are visible only to their creator and ADMIN
 * users; for other users detail/update/delete return 404 (existence is not
 * revealed) and list endpoints simply omit them.
 */
@RestController
@RequestMapping("/api/v1/calendar")
@RequiredArgsConstructor
@Tag(name = "Calendar", description = "API pre káľendar — udalosti a typy eventov")
public class CalendarEventController {

    private final CalendarEventService calendarEventService;

    /**
     * Get the current authenticated username from SecurityContext.
     * Returns "anonymous" if no authentication is present (for backward compatibility with tests).
     */
    private String getCurrentUsername() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getName() != null) {
            return auth.getName();
        }
        return "anonymous";
    }

    // ── Events ────────────────────────────────────────────────────────────────

    @Operation(summary = "Zoznam udalostí v dátovom rozsahu", description =
            "Vráti udalosti medzi from a to (vč.). ADMIN vidí všetko; ostatní uvidia public udalosti + vlastné private. " +
            "Parametre from/to sú voliteľné — default = aktuálny mesiac. Parametr type filteruje podľa názvu typu eventov (case-insensitive). " +
            "mine=true vráti len vlastné udalosti (obe viditeľnosti). Výsledok je zoradený po dátume a čase (all-day udalosti najprv).")
    @ApiResponse(responseCode = "200", description = "Zoznam udalostí úspešne načítaný")
    @ApiResponse(responseCode = "400", description = "Neplatný dátum alebo from > to")
    @GetMapping("/events")
    public ResponseEntity<List<CalendarEventDTO>> listEvents(
            @Parameter(description = "Počiatočný deň rozsahu (YYYY-MM-DD), default = 1. deň aktuálneho mesiaca")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "Konečný deň rozsahu (YYYY-MM-DD), default = posledný deň aktuálneho mesiaca")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "Filter podľa názvu typu eventov (case-insensitive)")
            @RequestParam(required = false) String type,
            @Parameter(description = "true = len vlastné udalosti")
            @RequestParam(required = false, defaultValue = "false") boolean mine) {
        return ResponseEntity.ok(calendarEventService.listEvents(from, to, type, mine, getCurrentUsername()));
    }

    @Operation(summary = "Detail udalosti podľa ID", description =
            "Vráti detail jednej udalosti. Cudzí private event vráti 404 (neodhaľuje existenciu).")
    @ApiResponse(responseCode = "200", description = "Udalosť úspešne načítaná")
    @ApiResponse(responseCode = "404", description = "Udalosť neexistuje alebo je cudzí private event")
    @GetMapping("/events/{id}")
    public ResponseEntity<CalendarEventDTO> getEvent(
            @Parameter(description = "ID udalosti") @PathVariable Long id) {
        return ResponseEntity.ok(calendarEventService.getEvent(id, getCurrentUsername()));
    }

    @Operation(summary = "Vytvorenie novej udalosti", description =
            "Vytvorí novú udalosť. Vtvorca = aktuálny autentifikovaný user (JWT). " +
            "title a eventDate sú povinné; startTime/endTime sú voliteľné (null = all-day event); " +
            "eventType prijme id alebo názov typu (case-insensitive); visibility default 'public'.")
    @ApiResponse(responseCode = "201", description = "Udalosť úspešne vytvorená")
    @ApiResponse(responseCode = "400", description = "Chýbajúce povinné polia, neexistujúci typ eventov (so zoznamom dostupných) alebo neplatná visibility")
    @PostMapping("/events")
    public ResponseEntity<CalendarEventDTO> createEvent(@Valid @RequestBody CalendarEventCreateDTO dto) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(calendarEventService.createEvent(dto, getCurrentUsername()));
    }

    @Operation(summary = "Aktualizácia udalosti", description =
            "Aktualizuje existujúcu udalosť. PATCH-like správanie: null polia = bez zmeny. " +
            "Len owner alebo ADMIN (cudzí private event = 404).")
    @ApiResponse(responseCode = "200", description = "Udalosť úspešne aktualizovaná")
    @ApiResponse(responseCode = "400", description = "Neplatný vstup (napr. neexistujúci typ, prázdny title)")
    @ApiResponse(responseCode = "404", description = "Udalosť neexistuje alebo je cudzí private event")
    @PutMapping("/events/{id}")
    public ResponseEntity<CalendarEventDTO> updateEvent(
            @Parameter(description = "ID udalosti") @PathVariable Long id,
            @Valid @RequestBody CalendarEventUpdateDTO dto) {
        return ResponseEntity.ok(calendarEventService.updateEvent(id, dto, getCurrentUsername()));
    }

    @Operation(summary = "Vymazanie udalosti", description =
            "Vymaže udalosť. Len owner alebo ADMIN (cudzí private event = 404).")
    @ApiResponse(responseCode = "204", description = "Udalosť úspešne vymazaná")
    @ApiResponse(responseCode = "404", description = "Udalosť neexistuje alebo je cudzí private event")
    @DeleteMapping("/events/{id}")
    public ResponseEntity<Void> deleteEvent(
            @Parameter(description = "ID udalosti") @PathVariable Long id) {
        calendarEventService.deleteEvent(id, getCurrentUsername());
        return ResponseEntity.noContent().build();
    }

    // ── Event types ───────────────────────────────────────────────────────────

    @Operation(summary = "Zoznam typov eventov", description =
            "Vráti všetky typy eventov zoradené podľa id (seed typy najprv).")
    @ApiResponse(responseCode = "200", description = "Zoznam typov úspešne načítaný")
    @GetMapping("/event-types")
    public ResponseEntity<List<EventTypeDTO>> listEventTypes() {
        return ResponseEntity.ok(calendarEventService.listEventTypes());
    }

    @Operation(summary = "Vytvorenie nového typu eventov", description =
            "Vytvorí nový typ eventov. Duplicitný názov (case-insensitive) = 409.")
    @ApiResponse(responseCode = "201", description = "Typ úspešne vytvorený")
    @ApiResponse(responseCode = "400", description = "Chýba názov typu")
    @ApiResponse(responseCode = "409", description = "Typ s rovnakým názvom už existuje")
    @PostMapping("/event-types")
    public ResponseEntity<EventTypeDTO> createEventType(@Valid @RequestBody EventTypeCreateDTO dto) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(calendarEventService.createEventType(dto, getCurrentUsername()));
    }

    @Operation(summary = "Vymazanie typu eventov", description =
            "Vymaže typ eventov. Ak má nejaké udalosti → 409 (vráti počet); inak OK.")
    @ApiResponse(responseCode = "204", description = "Typ úspešne vymazaný")
    @ApiResponse(responseCode = "404", description = "Typ s daným ID nebol nájdený")
    @ApiResponse(responseCode = "409", description = "Typ je ešte použitý udalosťami (vráti počet)")
    @DeleteMapping("/event-types/{id}")
    public ResponseEntity<Void> deleteEventType(
            @Parameter(description = "ID typu eventov") @PathVariable Long id) {
        calendarEventService.deleteEventType(id, getCurrentUsername());
        return ResponseEntity.noContent().build();
    }
}
