package com.wiki4ai.controller;

import com.wiki4ai.dto.ActorMetricsResponseDTO;
import com.wiki4ai.exception.BadRequestException;
import com.wiki4ai.service.ActorMetricsService;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

/**
 * The metrics endpoint of WIKI4AI-147: {@code GET /api/v1/metrics/actors?from=&to=},
 * narrowed to one project by {@code &projectId=} since WIKI4AI-173.
 * <p>
 * Everything the numbers are made of is derived in {@code ActorMetricsService} from
 * {@code work_item_events} alone. This class adds nothing to that; it decides only
 * how a time range is spelled and what a badly spelled one costs.
 * <p>
 * {@code projectId} is taken as a {@code String} and handed to the service unparsed,
 * for the reason {@link #parseBoundary} states about the window: a numeric parameter
 * declared as {@code Long} turns a typo into a converter failure the caller cannot
 * read. What a caller gets instead is a 400 naming {@code projectId}.
 * <p>
 * SecurityConfig needs no change: the route falls under
 * {@code anyRequest().authenticated()}, and who may see whose metrics is decided in
 * the service, like every other ownership rule of this project (SPEC rule 7).
 */
@RestController
@RequestMapping("/api/v1/metrics")
@RequiredArgsConstructor
@Tag(name = "Actor metrics", description = "Metriky odvodené výhradne z work_item_events (WIKI4AI-147)")
public class MetricsController {

    private final ActorMetricsService actorMetricsService;

    /**
     * Current authenticated username from the SecurityContext, "anonymous" when
     * there is none — the convention every other controller here uses.
     */
    private String getCurrentUsername() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getName() != null) {
            return auth.getName();
        }
        return "anonymous";
    }

    @Operation(summary = "Metriky actorov z eventov", description =
            "Čas v stave, cycle time created→done, počet blocked, počet lease_expired a priemer na "
                    + "dokončenie per actor. Všetko odvodené iba z work_item_events; work_items sa "
                    + "čítajú nulovo. from/to sú voliteľné, akceptujú ISO dátum (2026-10-01) alebo "
                    + "ISO dátum a čas (2026-10-01T08:00:00). Neplatný formát → 400. "
                    + "Vlastné metriky vidí ktokoľvek autentizovaný, cudzie iba ADMIN. "
                    + "projectId zúži okno na jeden projekt; neexistujúci alebo cudzí private "
                    + "projekt → 404 (WIKI4AI-173).")
    @ApiResponse(responseCode = "200", description = "Metriky vrátené")
    @ApiResponse(responseCode = "400", description = "Neplatný formát from/to, projectId alebo scope; "
            + "to skôr ako from; scope=project bez projectId")
    @ApiResponse(responseCode = "403", description = "Credential bez actora a nie si ADMIN")
    @ApiResponse(responseCode = "404", description = "projectId menuje projekt, ktorý neexistuje "
            + "alebo ho volajúci nevidí (WIKI4AI-173)")
    @GetMapping("/actors")
    public ResponseEntity<ActorMetricsResponseDTO> actorMetrics(
            @Parameter(description = "Začiatok okna: 2026-10-01 alebo 2026-10-01T08:00:00")
            @RequestParam(required = false) String from,
            @Parameter(description = "Koniec okna: 2026-10-01 alebo 2026-10-01T08:00:00")
            @RequestParam(required = false) String to,
            @Parameter(description = "all | self | project. project vyžaduje projectId. "
                    + "Bez hodnoty: to, na čo má volajúci nárok (all pre ADMIN, inak self).")
            @RequestParam(required = false) String scope,
            @Parameter(description = "Číslo projektu, na ktorý sa okno zúži. Neexistujúci alebo "
                    + "cudzí private projekt → 404.")
            @RequestParam(required = false) String projectId) {

        LocalDateTime parsedFrom = parseBoundary(from, "from");
        LocalDateTime parsedTo = parseBoundary(to, "to");
        return ResponseEntity.ok(actorMetricsService.actorMetrics(
                parsedFrom, parsedTo, scope, projectId, getCurrentUsername()));
    }

    /**
     * Parse one window boundary.
     * <p>
     * Parsed by hand rather than declared as a {@code LocalDateTime} parameter,
     * because the cost of a typo has to be a 400 with a message that says which
     * parameter was wrong and what it expected. Left to Spring's converter it is a
     * type-mismatch on a parameter the caller cannot see the type of, and a caller
     * who gets a 500 from a metrics endpoint has learned nothing.
     * <p>
     * A bare date is accepted because a window is usually asked for in days. For
     * {@code to} it means the end of that day, not its first second — asking for
     * {@code to=2026-10-09} and silently losing everything that happened on the 9th
     * is the kind of surprise a reporting endpoint should not have.
     */
    private LocalDateTime parseBoundary(String value, String parameter) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim();
        try {
            return LocalDateTime.parse(text);
        } catch (DateTimeParseException ignored) {
            // Not a date-time; a bare date is the other spelling a caller will use.
        }
        try {
            LocalDate date = LocalDate.parse(text);
            return "to".equals(parameter) ? date.atTime(23, 59, 59, 999_999_999) : date.atStartOfDay();
        } catch (DateTimeParseException e) {
            throw new BadRequestException("Parameter '" + parameter + "' must be an ISO date"
                    + " (2026-10-01) or an ISO date and time (2026-10-01T08:00:00); got '" + value + "'");
        }
    }
}
