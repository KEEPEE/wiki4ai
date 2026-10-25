package com.wiki4ai.controller;

import com.wiki4ai.dto.ProjectActorAssignDTO;
import com.wiki4ai.dto.ProjectActorDTO;
import com.wiki4ai.dto.ProjectActorUpdateRoleDTO;
import com.wiki4ai.service.ProjectActorService;
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
 * REST Controller for project actor assignments (WIKI4AI-132).
 * Endpoints are versioned under /api/v1/projects/{projectId}/actors.
 * <p>
 * Role enforcement lives in ProjectActorService — the JWT filter grants ROLE_USER
 * to everyone, so nothing role-specific is configured here. Managing the actors of
 * a project (POST / PUT / DELETE) requires the project's 'admin' actor role, the
 * project ownership, or an ADMIN user; anyone else gets 403 Forbidden.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/actors")
@RequiredArgsConstructor
@Tag(name = "Project actors", description = "API pre role agentov v projekte (WIKI4AI-132)")
public class ProjectActorController {

    private final ProjectActorService projectActorService;

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

    @Operation(summary = "Zoznam actorov projektu", description =
            "Vráti actorov priradených k projektu. Voliteľný parameter role filtruje podľa " +
            "'viewer' | 'worker' | 'planner' | 'admin' (case-insensitive); neplatná hodnota vráti 400. " +
            "Súkromný projekt patrí cudzím volajúcim ako 404.")
    @ApiResponse(responseCode = "200", description = "Zoznam actorov úspešne načítaný")
    @ApiResponse(responseCode = "400", description = "Neplatná hodnota parametra role")
    @ApiResponse(responseCode = "404", description = "Projekt neexistuje (alebo je súkromný)")
    @GetMapping
    public ResponseEntity<List<ProjectActorDTO>> listProjectActors(
            @Parameter(description = "ID projektu") @PathVariable Long projectId,
            @Parameter(description = "Filter podľa role: viewer | worker | planner | admin")
            @RequestParam(required = false) String role) {
        return ResponseEntity.ok(projectActorService.listProjectActors(projectId, role, getCurrentUsername()));
    }

    @Operation(summary = "Priradenie actora k projektu", description =
            "Priradí actora k projektu. Vyžaduje roľu 'admin' k projektu (majiteľ projektu, " +
            "ADMIN user, alebo volajúci, ktorého actor má v projekte roľu admin). " +
            "Archivovaný actor → 400, neznámy actor/projekt → 404, duplicitné priradenie → 409. " +
            "Role default 'viewer'.")
    @ApiResponse(responseCode = "201", description = "Actor úspešne priradený")
    @ApiResponse(responseCode = "400", description = "Neplatná role alebo archivovaný actor")
    @ApiResponse(responseCode = "403", description = "Nemáš roľu admin k tomuto projektu")
    @ApiResponse(responseCode = "404", description = "Projekt alebo actor neexistuje")
    @ApiResponse(responseCode = "409", description = "Actor je už k projektu priradený")
    @PostMapping
    public ResponseEntity<ProjectActorDTO> assignActor(
            @Parameter(description = "ID projektu") @PathVariable Long projectId,
            @Valid @RequestBody ProjectActorAssignDTO dto) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(projectActorService.assignActor(projectId, dto, getCurrentUsername()));
    }

    @Operation(summary = "Zmena role actora v projekte", description =
            "Zmení roľu, ktorú actor v projekte má. Vyžaduje roľu 'admin' k projektu. " +
            "Neplatná role → 400, actor nie je priradený → 404.")
    @ApiResponse(responseCode = "200", description = "Role úspešne zmenená")
    @ApiResponse(responseCode = "400", description = "Chýbajúca alebo neplatná role")
    @ApiResponse(responseCode = "403", description = "Nemáš roľu admin k tomuto projektu")
    @ApiResponse(responseCode = "404", description = "Projekt neexistuje alebo actor nie je priradený")
    @PutMapping("/{actorId}")
    public ResponseEntity<ProjectActorDTO> updateActorRole(
            @Parameter(description = "ID projektu") @PathVariable Long projectId,
            @Parameter(description = "ID actora") @PathVariable Long actorId,
            @Valid @RequestBody ProjectActorUpdateRoleDTO dto) {
        return ResponseEntity.ok(
                projectActorService.updateRole(projectId, actorId, dto, getCurrentUsername()));
    }

    @Operation(summary = "Odobratie actora z projektu", description =
            "Odstráni priradenie actora k projektu. Vyžaduje roľu 'admin' k projektu.")
    @ApiResponse(responseCode = "204", description = "Actor úspešne odobratý")
    @ApiResponse(responseCode = "403", description = "Nemáš roľu admin k tomuto projektu")
    @ApiResponse(responseCode = "404", description = "Projekt neexistuje alebo actor nie je priradený")
    @DeleteMapping("/{actorId}")
    public ResponseEntity<Void> removeActor(
            @Parameter(description = "ID projektu") @PathVariable Long projectId,
            @Parameter(description = "ID actora") @PathVariable Long actorId) {
        projectActorService.removeActor(projectId, actorId, getCurrentUsername());
        return ResponseEntity.noContent().build();
    }
}
