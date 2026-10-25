package com.wiki4ai.controller;

import com.wiki4ai.dto.WorkItemEventDTO;
import com.wiki4ai.service.WorkItemEventService;
import com.wiki4ai.service.WorkItemService;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The timeline endpoint of WIKI4AI-146: {@code GET /api/v1/work-items/{id}/events}.
 * <p>
 * A controller of its own rather than another method on
 * {@code WorkItemController}, for one practical and one honest reason. Practically:
 * {@code WorkItemControllerTest} is a {@code @WebMvcTest} slice that mocks
 * {@code WorkItemService} and nothing else; giving {@code WorkItemController} a
 * second collaborator would fail that slice's context before any assertion ran, and
 * editing somebody else's test to fix it is not this story's to do. Honestly: the
 * two endpoints answer different questions — "what is this item now" and "what
 * happened to it" — and the second one has different visibility rules, which is
 * easier to see when they are not sitting in the same class.
 * <p>
 * The route itself does not collide: {@code /api/v1/work-items/{id}} and
 * {@code /api/v1/work-items/{id}/events} are different paths, and Spring picks the
 * more specific one. SecurityConfig needs no change — both fall under
 * {@code anyRequest().authenticated()}.
 * <p>
 * Pagination follows the contract the documents endpoint already uses: {@code page}
 * 0-indexed, {@code size} default 50 clamped to 1..100, and the response is a Spring
 * {@code Page}, so {@code totalElements}, {@code totalPages} and
 * {@code last}/{@code first} behave the way they do everywhere else in this API.
 */
@RestController
@RequestMapping("/api/v1/work-items")
@RequiredArgsConstructor
@Tag(name = "Work item timeline", description = "Append-only timeline work itemu (WIKI4AI-146)")
public class WorkItemEventsController {

    private final WorkItemService workItemService;
    private final WorkItemEventService workItemEventService;

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

    @Operation(summary = "Timeline work itemu", description =
            "Vráti append-only eventy jedného work itemu, chronologicky (created_at ASC, id ASC). "
                    + "Obsahuje aj lease_expired. Stránkované: page (0-indexed), size (default 50, max 100). "
                    + "Cudzí súkromný projekt → 404. Cudzí public projekt → 403 (timeline menuje actorov).")
    @ApiResponse(responseCode = "200", description = "Timeline načítaný")
    @ApiResponse(responseCode = "403", description = "Item je vo verejnom projekte, v ktorom nemáš rolu")
    @ApiResponse(responseCode = "404", description = "Work item neexistuje alebo je v cudzom súkromnom projekte")
    @GetMapping("/{id}/events")
    public ResponseEntity<Page<WorkItemEventDTO>> listEvents(
            @Parameter(description = "ID work itemu") @PathVariable Long id,
            @Parameter(description = "Číslo stránky (0-indexed, default 0)")
            @RequestParam(required = false, defaultValue = "0") int page,
            @Parameter(description = "Veľkosť stránky (default 50, max 100)")
            @RequestParam(required = false, defaultValue = "50") int size) {

        // Ownership first, in the service that owns the work item rules
        // (SPEC rule 7): foreign private project → 404, foreign public → 403.
        workItemService.requireEventTimelineAccess(id, getCurrentUsername());
        return ResponseEntity.ok(workItemEventService.listEvents(id, page, size));
    }
}
