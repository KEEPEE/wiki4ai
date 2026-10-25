package com.wiki4ai.controller;

import com.wiki4ai.dto.WorkItemClaimDTO;
import com.wiki4ai.dto.WorkItemDTO;
import com.wiki4ai.dto.WorkItemLeaseRequestDTO;
import com.wiki4ai.dto.WorkItemRequestDTO;
import com.wiki4ai.service.WorkItemService;
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
 * REST controller for work items (WIKI4AI-140) and the atomic claim
 * (WIKI4AI-141), under {@code /api/v1/work-items}.
 * <p>
 * The controller does no authorization of its own and knows nothing about the
 * state machine: ownership (foreign private project → 404, foreign public → 403),
 * legal transitions and the claim race are decided in {@code WorkItemService} and
 * translated to statuses by {@code GlobalExceptionHandler}
 * (IllegalArgumentException → 409, EntityNotFoundException → 404,
 * AccessDeniedException → 403, BadRequestException → 400). SecurityConfig needs
 * no change: {@code /api/v1/work-items/**} falls under {@code anyRequest()
 * .authenticated()}.
 * <p>
 * No parameter of any endpoint names an actor. Attribution is resolved from the
 * verified credential inside the service ({@code RequestActorResolver}), so a
 * caller cannot claim to be somebody else by sending an id.
 */
@RestController
@RequestMapping("/api/v1/work-items")
@RequiredArgsConstructor
@Tag(name = "Work items", description = "Jednotky práce a ich atómový claim (WIKI4AI-140, 141)")
public class WorkItemController {

    private final WorkItemService workItemService;

    /**
     * Current authenticated username from the SecurityContext, "anonymous" when
     * there is none — the same convention every other controller here uses, and
     * the value the services map to "no user".
     */
    private String getCurrentUsername() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getName() != null) {
            return auth.getName();
        }
        return "anonymous";
    }

    @Operation(summary = "Zoznam work itemov", description =
            "Vráti work itemy. Filtre (sčítavajú sa AND): projectId, status, label, assignee. "
                    + "Neplatný status → 400. label sa porovnáva ako celý token, nie ako podreťazec. "
                    + "Bez projectId sa vynechajú položky zo súkromných projektov, ktoré volajúci nevidí. "
                    + "Cudzí súkromný projekt → 404.")
    @ApiResponse(responseCode = "200", description = "Zoznam načítaný")
    @ApiResponse(responseCode = "400", description = "Neplatná hodnota filtra status")
    @ApiResponse(responseCode = "404", description = "Projekt neexistuje alebo je súkromný")
    @GetMapping
    public ResponseEntity<List<WorkItemDTO>> listWorkItems(
            @Parameter(description = "Filtrovať podľa projektu") @RequestParam(required = false) Long projectId,
            @Parameter(description = "Filtrovať podľa stavu") @RequestParam(required = false) String status,
            @Parameter(description = "Filtrovať podľa labelu (presný token, nie podreťazec)")
            @RequestParam(required = false) String label,
            @Parameter(description = "Filtrovať podľa actora, ktorý item drží (assigneeActorId)")
            @RequestParam(required = false) Long assignee) {
        String username = getCurrentUsername();
        // The three-argument form is the service API this endpoint had before
        // WIKI4AI-144 and it stays the target when no new filter is supplied, so
        // existing callers of the service are untouched. With a label or an
        // assignee the full filter form is used. Both paths run the same rules —
        // the short one delegates to the long one.
        List<WorkItemDTO> items = (label == null && assignee == null)
                ? workItemService.listWorkItems(projectId, status, username)
                : workItemService.listWorkItems(projectId, status, label, assignee, username);
        return ResponseEntity.ok(items);
    }

    @Operation(summary = "Detail work itemu", description =
            "Vráti jeden work item. Položka v cudzom súkromnom projekte je 404 (jej existencia sa nezjavuje).")
    @ApiResponse(responseCode = "200", description = "Detail načítaný")
    @ApiResponse(responseCode = "404", description = "Work item neexistuje (alebo je v cudzom súkromnom projekte)")
    @GetMapping("/{id}")
    public ResponseEntity<WorkItemDTO> getWorkItem(
            @Parameter(description = "ID work itemu") @PathVariable Long id) {
        return ResponseEntity.ok(workItemService.getWorkItem(id, getCurrentUsername()));
    }

    @Operation(summary = "Vytvorenie work itemu", description =
            "Vytvorí work item v projekte. project_id a title sú povinné. Stav 'claimed' nie je "
                    + "dostupný pri vytvorení (400) — vzniká iba claimom. Atribúcia tvora sa berie "
                    + "z overeného credentialu, nie z tela požiadavky. Duplicitný externalRef → 409.")
    @ApiResponse(responseCode = "201", description = "Work item vytvorený")
    @ApiResponse(responseCode = "400", description = "Chýbajúci title/projectId alebo neplatná hodnota")
    @ApiResponse(responseCode = "403", description = "Na tento projekt nemáš právo zapisovať")
    @ApiResponse(responseCode = "404", description = "Projekt neexistuje alebo je súkromný")
    @ApiResponse(responseCode = "409", description = "externalRef je už obsadený")
    @PostMapping
    public ResponseEntity<WorkItemDTO> createWorkItem(@Valid @RequestBody WorkItemRequestDTO dto) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(workItemService.createWorkItem(dto, getCurrentUsername()));
    }

    @Operation(summary = "Zmena work itemu", description =
            "PATCH sémantika: null pole znamená nemenit. Stav musí byť prípustný prechod, inak 409. "
                    + "Stav 'claimed' nie je cez PUT dosiahnuteľný nikdy (409). "
                    + "Cudzí súkromný projekt → 404, cudzí public → 403.")
    @ApiResponse(responseCode = "200", description = "Work item zmenený")
    @ApiResponse(responseCode = "400", description = "Neplatná hodnota pola")
    @ApiResponse(responseCode = "403", description = "Na tento projekt nemáš právo zapisovať")
    @ApiResponse(responseCode = "404", description = "Work item neexistuje (alebo je v cudzom súkromnom projekte)")
    @ApiResponse(responseCode = "409", description = "Neplatný prechod stavov alebo obsadený externalRef")
    @PutMapping("/{id}")
    public ResponseEntity<WorkItemDTO> updateWorkItem(
            @Parameter(description = "ID work itemu") @PathVariable Long id,
            @Valid @RequestBody WorkItemRequestDTO dto) {
        return ResponseEntity.ok(workItemService.updateWorkItem(id, dto, getCurrentUsername()));
    }

    @Operation(summary = "Zmazanie work itemu", description =
            "Zmaže work item. Cudzí súkromný projekt → 404, cudzí public → 403.")
    @ApiResponse(responseCode = "204", description = "Work item zmazaný")
    @ApiResponse(responseCode = "403", description = "Na tento projekt nemáš právo zapisovať")
    @ApiResponse(responseCode = "404", description = "Work item neexistuje (alebo je v cudzom súkromnom projekte)")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteWorkItem(
            @Parameter(description = "ID work itemu") @PathVariable Long id) {
        workItemService.deleteWorkItem(id, getCurrentUsername());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Claim work itemu", description =
            "Atomicky si vyber ready work item pre actora daného credentialu. Prebeh: vyhráva ten, "
                    + "kto ako prvý zmení riadok; ostatní dostanú 409. Claim na item, ktorý nie je "
                    + "'ready', → 409. Credential bez actora (legacy JWT bez human actora) → 403, "
                    + "nie 500. Vráti claimToken, leaseExpiresAt a assigneeActorId.")
    @ApiResponse(responseCode = "200", description = "Claim úspešný")
    @ApiResponse(responseCode = "403", description = "Credential nema actor, alebo nemáš právo na tento projekt")
    @ApiResponse(responseCode = "404", description = "Work item neexistuje (alebo je v cudzom súkromnom projekte)")
    @ApiResponse(responseCode = "409", description = "Item uz nie je 'ready' (niekto iny ho uz claimol)")
    @PostMapping("/{id}/claim")
    public ResponseEntity<WorkItemClaimDTO> claimWorkItem(
            @Parameter(description = "ID work itemu") @PathVariable Long id) {
        return ResponseEntity.ok(workItemService.claimWorkItem(id, getCurrentUsername()));
    }

    @Operation(summary = "Predlzanie lease", description =
            "Obnovi lease_expires_at work itemu, ktory volajuci drzi. Drzitelia je ten, "
                    + "ktoho actor (z overeneho credentialu) je assigneeActor_id; volitelny "
                    + "claimToken musi v takom pripade sediet. Iny actor → 403. "
                    + "Item nie je claimed/running → 409 (nie je co predlzovat). "
                    + "Dlzka lease je nastavenie servera, nie rozhodnutie klienta.")
    @ApiResponse(responseCode = "200", description = "Lease predlzeny")
    @ApiResponse(responseCode = "403", description = "Item nedrzis ty, alebo claimToken nesedí")
    @ApiResponse(responseCode = "404", description = "Work item neexistuje (alebo je v cudzom súkromnom projekte)")
    @ApiResponse(responseCode = "409", description = "Item nedrzí ziadny actor (lease neexistuje alebo bol uvolneny)")
    @PostMapping("/{id}/lease")
    public ResponseEntity<WorkItemClaimDTO> extendLease(
            @Parameter(description = "ID work itemu") @PathVariable Long id,
            @Valid @RequestBody(required = false) WorkItemLeaseRequestDTO dto) {
        String claimToken = (dto == null) ? null : dto.getClaimToken();
        return ResponseEntity.ok(workItemService.extendLease(id, claimToken, getCurrentUsername()));
    }
}
