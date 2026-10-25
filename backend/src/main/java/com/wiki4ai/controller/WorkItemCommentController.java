package com.wiki4ai.controller;

import com.wiki4ai.dto.WorkItemCommentDTO;
import com.wiki4ai.dto.WorkItemCommentRequestDTO;
import com.wiki4ai.service.WorkItemCommentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * The comment endpoints of WIKI4AI-148: {@code POST} and {@code GET} on
 * {@code /api/v1/work-items/{id}/comments}.
 *
 * <h2>Two methods, and only two</h2>
 * There is no PUT, no PATCH and no DELETE here, and that is a rule of the story, not
 * an omission: a comment is a statement somebody made at a moment, and the discussion
 * of a work item is only worth reading later if nothing in it was edited after the
 * fact. The absence is backed by three other layers — every mutating method of
 * {@code WorkItemCommentRepository} throws, {@code WorkItemComment}'s
 * {@code @PreUpdate} / {@code @PreRemove} throw, and V22 installs a BEFORE UPDATE
 * trigger — so "the API has no such endpoint" is the visible half of a rule the
 * application and the schema enforce as well. A PUT added here later would fail at
 * three places before it could write anything.
 * <p>
 * A controller of its own rather than more methods on {@code WorkItemController}, for
 * the same reason {@code WorkItemEventsController} is one: {@code WorkItemControllerTest}
 * is a {@code @WebMvcTest} slice that mocks {@code WorkItemService} and nothing else,
 * and giving that controller a second collaborator would fail its context before any
 * assertion ran — and editing somebody else's test to fix that is not this story's to
 * do. The routes do not collide: {@code /api/v1/work-items/{id}} and
 * {@code /api/v1/work-items/{id}/comments} are different paths and Spring picks the
 * more specific one. SecurityConfig needs no change — both fall under
 * {@code anyRequest().authenticated()}.
 * <p>
 * The controller authorizes nothing of its own and validates nothing of its own:
 * ownership (foreign private → 404, foreign public → 403), the required fields (400)
 * and the per-kind payload contracts (422) are decided in
 * {@code WorkItemCommentService} and translated to statuses by
 * {@code GlobalExceptionHandler}. No parameter of any endpoint names an actor —
 * {@code authorActorId} in a body has no field to land on and is dropped.
 * <p>
 * Pagination follows the contract the timeline and documents endpoints already use:
 * {@code page} 0-indexed, {@code size} default 50 clamped to 1..100, response is a
 * Spring {@code Page}.
 * <p>
 * WIKI4AI-151 added one parameter to the read, {@code needs_human}, and no endpoint: the
 * two methods above are still the only two. It is a filter over the {@code payload} of
 * {@code blocker} comments, and it is a read only — nothing here notifies anybody, and
 * nothing here runs on a timer. wiki4ai records that a blocker needs a human and makes
 * that findable; telling a human is not this API's job and no part of WIKI4AI-151 does
 * it.
 */
@RestController
@RequestMapping("/api/v1/work-items")
@RequiredArgsConstructor
@Tag(name = "Work item comments", description = "Nemenné komentáre work itemu s kontraktmi per kind (WIKI4AI-148, 149)")
public class WorkItemCommentController {

    private final WorkItemCommentService workItemCommentService;

    /**
     * Current authenticated username from the SecurityContext, "anonymous" when
     * there is none — the same convention every other controller here uses, and the
     * value the services map to "no user".
     */
    private String getCurrentUsername() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getName() != null) {
            return auth.getName();
        }
        return "anonymous";
    }

    @Operation(summary = "Pridať komentár", description =
            "Append-only komentár k work itemu. kind: status | blocker | result | handoff | note | decision. "
                    + "author_actor_id sa berie výhradne z credentialu — authorActorId v tele requestu je ignorovaný. "
                    + "Kontrakt payloadu per kind vynucuje server: result → where, next, artifacts[]; "
                    + "blocker → blocking, needs_human(boolean); handoff → to_handle(active actor), context; "
                    + "decision → what, why, applies_to. "
                    + "Chýbajúci body → 400. Neznámy kind → 400. Porušenie kontraktu payloadu → 422.")
    @ApiResponse(responseCode = "201", description = "Komentár zapísaný")
    @ApiResponse(responseCode = "400", description = "Chýbajúci body alebo neznámy kind")
    @ApiResponse(responseCode = "403", description = "Cudzí public projekt, alebo credential bez actora")
    @ApiResponse(responseCode = "404", description = "Work item neexistuje alebo je v cudzom súkromnom projekte")
    @ApiResponse(responseCode = "422", description = "Payload nezodpovedá kontraktu kindu")
    @PostMapping("/{id}/comments")
    public ResponseEntity<WorkItemCommentDTO> addComment(
            @Parameter(description = "ID work itemu") @PathVariable Long id,
            @Parameter(description = "kind, body a voliteľný payload") @RequestBody WorkItemCommentRequestDTO dto) {

        WorkItemCommentDTO created = workItemCommentService.create(id, dto, getCurrentUsername());

        // Location names the collection the comment was appended to, not
        // /comments/{id}: there is no endpoint that reads one comment by id, and a
        // Location header advertising a route that does not exist would be a promise
        // this API does not keep.
        return ResponseEntity
                .created(URI.create("/api/v1/work-items/" + id + "/comments"))
                .body(created);
    }

    @Operation(summary = "Komentáre work itemu", description =
            "Vráti komentáre jedného work itemu, chronologicky (created_at ASC, id ASC). "
                    + "Stránkované: page (0-indexed), size (default 50, max 100). "
                    + "Filter kind (status | blocker | result | handoff | note | decision). "
                    + "Filter needs_human=true|false platí pre kind=blocker — číta sa z payloadu, nie zo stĺpca; "
                    + "v kombinácii s iným kindom je výsledok prázdna stránka. Neplatná hodnota needs_human → 400. "
                    + "Cudzí súkromný projekt → 404. Cudzí public projekt → 403 (rovnako ako timeline).")
    @ApiResponse(responseCode = "200", description = "Komentáre načítané")
    @ApiResponse(responseCode = "400", description = "Neznámy filter kind alebo neplatná hodnota needs_human")
    @ApiResponse(responseCode = "403", description = "Item je vo verejnom projekte, v ktorom nemáš rolu")
    @ApiResponse(responseCode = "404", description = "Work item neexistuje alebo je v cudzom súkromnom projekte")
    @GetMapping("/{id}/comments")
    public ResponseEntity<Page<WorkItemCommentDTO>> listComments(
            @Parameter(description = "ID work itemu") @PathVariable Long id,
            @Parameter(description = "Číslo stránky (0-indexed, default 0)")
            @RequestParam(required = false, defaultValue = "0") int page,
            @Parameter(description = "Veľkosť stránky (default 50, max 100)")
            @RequestParam(required = false, defaultValue = "50") int size,
            @Parameter(description = "Filtrovať podľa kindu (status | blocker | result | handoff | note | decision)")
            @RequestParam(required = false) String kind,
            @Parameter(description = "Filtrovať blocker komentáre podľa payload.needs_human (true | false). "
                    + "Iný kind ako blocker → prázdna stránka. Neplatná hodnota → 400.")
            @RequestParam(name = "needs_human", required = false) String needsHuman) {

        return ResponseEntity.ok(workItemCommentService.list(id, kind, needsHuman, page, size,
                getCurrentUsername()));
    }
}
