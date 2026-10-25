package com.wiki4ai.controller;

import com.wiki4ai.dto.InboxResponseDTO;
import com.wiki4ai.dto.UnseenEventsDTO;
import com.wiki4ai.exception.BadRequestException;
import com.wiki4ai.service.ActorInboxService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * HTTP layer of the actor inbox (WIKI4AI-152) and its unseen-event marker (WIKI4AI-153).
 *
 * <pre>
 * GET  /api/v1/actors/{id}/inbox?cursor=&amp;limit=
 * GET  /api/v1/actors/{id}/inbox/unseen
 * POST /api/v1/actors/{id}/inbox/mark-seen        {"eventId": 123}   (body optional)
 * </pre>
 *
 * <h2>This class contains no rule</h2>
 * SPEC rule 7. Who may read an inbox, what a cursor means, which events count as "an item
 * slipped away", which handoffs count as a mention, what the marker may be advanced to — all
 * of it is in {@link ActorInboxService}, once. What is here is the shape of the request and
 * the shape of the answer, and one deliberate detail:
 * <p>
 * {@code limit} is bound as an {@link Integer} and nothing more. A value that is not a number
 * is therefore rejected by Spring's own argument resolution and answered by the existing
 * {@code GlobalExceptionHandler#handleTypeMismatch} as a 400 naming the parameter —
 * {@code "Invalid value for parameter 'limit'"} — which is what the AC of WIKI4AI-152 asks
 * for and is one less hand-written parse in a project that already maps that exception.
 * A number outside 1..50 is a 400 from the service. Both paths name 'limit'.
 *
 * <h2>Why the cursor is a string the client returns, not something it builds</h2>
 * The cursor of WIKI4AI-152 is per-stream: four keyset positions, one per stream, because a
 * single number cannot advance four independent id sequences without skipping or repeating
 * rows. It is opaque for the same reason it is per-stream — a client that assembled it would
 * have to know the streams, and the streams are this story's decision, not part of the
 * contract. A client stores the string and sends it back. See
 * {@link ActorInboxService} for the encoding and for why it is not merged by id.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/actors")
@RequiredArgsConstructor
public class ActorInboxController {

    private final ActorInboxService actorInboxService;

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

    @Operation(summary = "Inbox aktora", description = """
            né štyri prúdy inboxu jedného aktora: (1) work itemy, ktoré mu patria \
            (assignee), (2) komentáre k itemom, ktoré vytvoril alebo ktoré drží, \
            (3) mentions — handoff komentáre, ktorých payload to_handle je jeho handle, \
            (4) itemy, ktoré mu ušli (lease_expired, stale_claim_cleared z work_item_events). \
            Vnútri prúdu sú položky zoradené podla monotonne id — to je to, po com ide \
            keyset stránkovanie. Napriec prúdom je poradie chronologicke podla created_at, \
            s id len ako tie-break, lebo work_items.id=5 a work_item_comments.id=5 nie su \
            ten isty cas: su to dve nezavisne postupnosti. Kurzor je opaque retazec, ktory \
            klient len vrati — nesie jednu poziciu na prud. limit: default 50, strop 50; \
            0, -1, 51 a ne-cislovna hodnota vratia 400 menujuci 'limit'.""")
    @ApiResponse(responseCode = "200", description = "Inbox načítaný")
    @ApiResponse(responseCode = "400", description = "Neplatný cursor alebo limit mimo 1..50")
    @ApiResponse(responseCode = "403", description = "Inbox patrí inému actorovi, alebo kredencial menuje actor")
    @ApiResponse(responseCode = "404", description = "Actor neexistuje")
    @GetMapping("/{id}/inbox")
    public ResponseEntity<InboxResponseDTO> getInbox(
            @Parameter(description = "ID aktora, ktorého inbox sa číta") @PathVariable Long id,
            @Parameter(description = "Opačný kurzor z predchádzajúcej stránky; prázdné = prvá stránka")
            @RequestParam(required = false) String cursor,
            @Parameter(description = "Veľkosť stránky PRUDU (default 50, max 50)")
            @RequestParam(required = false) Integer limit) {

        // The access rule is not here: getInbox applies it in the service that owns it.
        return ResponseEntity.ok(actorInboxService.getInbox(id, cursor, limit, getCurrentUsername()));
    }

    @Operation(summary = "Čo pribudlo do inboxu aktora", description = """
            Pocet eventov tohto aktora, kteste este nevidel, a ich id. Meria sa podla \
            actors.last_seen_event_id (V23): event je unseen, ak je jeho id vacsi ako marker. \
            Null marker znamena 'tentho actor este necital inbox' - nie 0 - a vtedy je unseen \
            cely jeho eventovy prud. lastSeenEventId vraca aktualny marker a newestEventId \
            najvyssi id, ktory v prude skutocne existuje; to je strop, pod ktory server \
            ostrihne eventId poslany klientom pri mark-se.""")
    @ApiResponse(responseCode = "200", description = "Unseen eventy načítané")
    @ApiResponse(responseCode = "403", description = "Inbox patrí inému actorovi")
    @ApiResponse(responseCode = "404", description = "Actor neexistuje")
    @GetMapping("/{id}/inbox/unseen")
    public ResponseEntity<UnseenEventsDTO> getUnseen(
            @Parameter(description = "ID aktora") @PathVariable Long id) {

        return ResponseEntity.ok(actorInboxService.getUnseen(id, getCurrentUsername()));
    }

    @Operation(summary = "Označit eventy inboxu ako prečítané", description = """
            Posunie marker actors.last_seen_event_id. Hodnotu rozhoduje server, nie klient: \
            eventId z telesa je ostrihny na najvyssi id, ktory v eventovom prude tohto aktora \
            skutocne existuje, takze ziadny volatel nemoze oznacit za precitele eventy, kteste \
            este neexistuju. Marker sa nikdy neposunie spat. Volanie je idempotentne - druhe \
            volanie bez novych eventov nic nezmeni a nie je chyba. Bez telesa oznaci vsetko, \
            co prave existuje.""")
    @ApiResponse(responseCode = "204", description = "Marker nastavený (aj keď sa nič nezmenilo)")
    @ApiResponse(responseCode = "400", description = "eventId nie je číslo alebo je záporný")
    @ApiResponse(responseCode = "403", description = "Inbox patrí inému actorovi")
    @ApiResponse(responseCode = "404", description = "Actor neexistuje")
    @PostMapping("/{id}/inbox/mark-seen")
    public ResponseEntity<Void> markSeen(
            @Parameter(description = "ID aktora") @PathVariable Long id,
            @Parameter(description = "Voliteľné: {\"eventId\": 123}. Bez telesa = všetko, čo práve existuje.")
            @RequestBody(required = false) Map<String, Object> body) {

        Long requestedEventId = null;
        if (body != null && body.containsKey("eventId")) {
            Object raw = body.get("eventId");
            if (raw == null) {
                requestedEventId = null;
            } else if (raw instanceof Number number) {
                requestedEventId = number.longValue();
            } else {
                throw new BadRequestException("Field 'eventId' must be a number");
            }
        }

        actorInboxService.markSeen(id, requestedEventId, getCurrentUsername());
        // 204: the call changes a marker and has nothing to report. What the marker now is,
        // and what is still unseen, is what GET .../inbox/unseen is for.
        return ResponseEntity.noContent().build();
    }
}
