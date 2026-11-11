package com.wiki4ai.controller;

import com.wiki4ai.dto.ProjectCreateDTO;
import com.wiki4ai.dto.ProjectDTO;
import com.wiki4ai.dto.ProjectTreeNodeDTO;
import com.wiki4ai.dto.ProjectUpdateDTO;
import com.wiki4ai.dto.ReorderRequestDTO;
import com.wiki4ai.service.ProjectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST Controller for Project CRUD operations.
 * Provides endpoints for managing wiki projects with Swagger documentation.
 * All endpoints are versioned under /api/v1/projects.
 */
@RestController
@RequestMapping("/api/v1/projects")
@RequiredArgsConstructor
@Tag(name = "Projects", description = "API pre správu projektov/topikov")
public class ProjectController {

    private final ProjectService projectService;

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

    @Operation(summary = "Zoznam projektov (filter + poradie)", description = """
            Vráti zoznam wiki projektov. Voliteľné parametre (WIKI4AI-184):
            `sort` = manual | name_asc | name_desc | created_asc | created_desc (default) | updated_desc,
            `q` = case-insensitive filter na názov + popis,
            `visibility` = public | private.
            Vždy platí, že pripnuté (pinned) projekty sú na vrchu a až potom nasleduje zvolený sort.
            Bez parametrov sa správanie nemení oproti predchádzajúcej verzii (created_desc), len pinned projekty
            preskočia na vrch. Súkromné (private) projekty sú viditeľné len ich vlastníkovi a ADMINom —
            ostatným používateľom sa v zozname neobjavia.""")
    @ApiResponse(responseCode = "200", description = "Zoznam projektov úspešne načítaný")
    @ApiResponse(responseCode = "400", description = "Neplatná hodnota parametra sort alebo visibility")
    @GetMapping
    public ResponseEntity<List<ProjectDTO>> getAllProjects(
            @Parameter(description = "Poradie: manual | name_asc | name_desc | created_asc | created_desc | updated_desc")
            @RequestParam(required = false) String sort,
            @Parameter(description = "Case-insensitive filter na názov a popis projektu")
            @RequestParam(required = false) String q,
            @Parameter(description = "Filter viditeľnosti: public | private")
            @RequestParam(required = false) String visibility) {
        return ResponseEntity.ok(
                projectService.getAllProjects(sort, q, visibility, getCurrentUsername()));
    }

    @Operation(summary = "Detail projektu podľa slugu", description = "Vráti detail projektu na základe jeho URL-friendly slugu. Cudzí súkromný (private) projekt vráti 404 (neodhaľuje existenciu).")
    @ApiResponse(responseCode = "200", description = "Projekt úspešne načítaný")
    @ApiResponse(responseCode = "404", description = "Projekt s daným slugom nebol nájdený (alebo je súkromný a volateľ nie je vlastník/ADMIN)")
    @GetMapping("/{slug}")
    public ResponseEntity<ProjectDTO> getProject(
            @Parameter(description = "Slug projektu") @PathVariable String slug) {
        return ResponseEntity.ok(projectService.getProjectBySlug(slug, getCurrentUsername()));
    }

    @Operation(summary = "Strom podprojektov", description = "Vráti vnoorený strom podprojektov (všetky úrovne, max hĺbka 5) pre zadaný projekt. Každý uzol obsahuje id, name, slug, parentSlug, depth, hasChildren a documentCount. Súkromné podprojekty sú v strome neviditeľné pre ne-vlastníkov.")
    @ApiResponse(responseCode = "200", description = "Strom projektov úspešne načítaný")
    @ApiResponse(responseCode = "404", description = "Projekt s daným slugom nebol nájdený (alebo je súkromný a volateľ nie je vlastník/ADMIN)")
    @GetMapping("/{slug}/tree")
    public ResponseEntity<ProjectTreeNodeDTO> getProjectTree(
            @Parameter(description = "Slug koreňového projektu stromu") @PathVariable String slug) {
        return ResponseEntity.ok(projectService.getProjectTree(slug, getCurrentUsername()));
    }

    @Operation(summary = "Vytvorenie nového projektu", description = "Vytvorí nový wiki projekt. Vtvorca automaticky dostáva MANAGE oprávnenie. Voliteľné `parentId` v tele vytvorí subprojekt pod daným projektom (max hĺbka hierarchie 5).")
    @ApiResponse(responseCode = "201", description = "Projekt úspešne vytvorený")
    @ApiResponse(responseCode = "400", description = "Neplatný vstup (validation error) alebo prekročená max. hĺbka hierarchie / cyklus")
    @ApiResponse(responseCode = "404", description = "Rodičovský projekt nebol nájdený")
    @ApiResponse(responseCode = "409", description = "Projekt s rovnakým názvom už existuje")
    @PostMapping
    public ResponseEntity<ProjectDTO> createProject(@Valid @RequestBody ProjectCreateDTO dto) {
        String username = getCurrentUsername();
        ProjectDTO created = projectService.createProject(dto, username);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @Operation(summary = "Aktualizácia projektu", description = "Aktualizuje existujúci projekt podľa slugu. Vyžaduje MANAGE oprávnenie. Voliteľné `parentId` presunie projekt v hierarchii: hodnota = nový rodič, explicitné null = späť na root, chýbajúce kľúč = bez presunu (max hĺbka 5, cykly odmietnuté).")
    @ApiResponse(responseCode = "200", description = "Projekt úspešne aktualizovaný")
    @ApiResponse(responseCode = "400", description = "Neplatný vstup (validation error), presun pod vlastného potomka / seba alebo prekročená max. hĺbka hierarchie")
    @ApiResponse(responseCode = "403", description = "Chýba MANAGE oprávnenie")
    @ApiResponse(responseCode = "404", description = "Projekt s daným slugom nebol nájdený alebo rodičovský projekt nebol nájdený")
    @PutMapping("/{slug}")
    public ResponseEntity<ProjectDTO> updateProject(
            @Parameter(description = "Slug projektu") @PathVariable String slug,
            @Valid @RequestBody ProjectUpdateDTO dto) {
        String username = getCurrentUsername();
        return ResponseEntity.ok(projectService.updateProjectBySlug(slug, dto, username));
    }

    @Operation(summary = "Vymazanie projektu", description = "Vymaže projekt podľa slugu. Vyžaduje MANAGE oprávnenie.")
    @ApiResponse(responseCode = "204", description = "Projekt úspešne vymazaný")
    @ApiResponse(responseCode = "403", description = "Chýba MANAGE oprávnenie")
    @ApiResponse(responseCode = "404", description = "Projekt s daným slugom nebol nájdený")
    @DeleteMapping("/{slug}")
    public ResponseEntity<Void> deleteProject(
            @Parameter(description = "Slug projektu") @PathVariable String slug) {
        String username = getCurrentUsername();
        projectService.deleteProjectBySlug(slug, username);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Export projektu ako ZIP", description = "Exportuje všetky dokumenty projektu ako ZIP archív s .md súbormi. Vyžaduje READ oprávnenie.")
    @ApiResponse(responseCode = "200", description = "ZIP archív úspešne vytvorený")
    @ApiResponse(responseCode = "403", description = "Chýba READ oprávnenie")
    @ApiResponse(responseCode = "404", description = "Projekt s daným slugom nebol nájdený")
    @GetMapping(value = "/{slug}/export", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<ByteArrayResource> exportProject(
            @Parameter(description = "Slug projektu") @PathVariable String slug) {
        String username = getCurrentUsername();
        byte[] zipData = projectService.exportProjectAsZip(slug, username);
        ByteArrayResource resource = new ByteArrayResource(zipData);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + slug + ".zip\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(zipData.length)
                .body(resource);
    }

    // ── Pin to top + manual order (WIKI4AI-184) ───────────────────────────────
    //
    // Dedicated POST endpoints, not a PATCH: the backend has no @PatchMapping
    // anywhere, and the existing PUT cannot carry a pin-only change because
    // ProjectUpdateDTO.name is @NotBlank.

    @Operation(summary = "Pripnutie projektu na vrch", description = """
            Pripne projekt na vrch zoznamov (WIKI4AI-184). Pri prvom pripnutí projekt získa
            aj pozíciu nad súčasnou prvou položkou manuálneho poradia. Opakované pripnutie
            pozíciu nemení. `updated_at` projektu sa neposúva — pin nie je editácia.
            Vyžaduje MANAGE oprávnenie; cudzí súkromný projekt vráti 404.""")
    @ApiResponse(responseCode = "200", description = "Projekt pripnutý")
    @ApiResponse(responseCode = "403", description = "Chýba MANAGE oprávnenie")
    @ApiResponse(responseCode = "404", description = "Projekt s daným slugom nebol nájdený (alebo je cudzí súkromný)")
    @PostMapping("/{slug}/pin")
    public ResponseEntity<ProjectDTO> pinProject(
            @Parameter(description = "Slug projektu") @PathVariable String slug) {
        return ResponseEntity.ok(projectService.pinProject(slug, getCurrentUsername()));
    }

    @Operation(summary = "Odopnutie projektu", description = """
            Odoberie projekt z vrchu zoznamov (WIKI4AI-184). Manuálna pozícia (sortOrder)
            zostáva zachovaná, takže sa projekt vrámi presne tam, kde bol pred pripnutím.
            Vyžaduje MANAGE oprávnenie.""")
    @ApiResponse(responseCode = "200", description = "Projekt odopnutý")
    @ApiResponse(responseCode = "403", description = "Chýba MANAGE oprávnenie")
    @ApiResponse(responseCode = "404", description = "Projekt s daným slugom nebol nájdený (alebo je cudzí súkromný)")
    @PostMapping("/{slug}/unpin")
    public ResponseEntity<ProjectDTO> unpinProject(
            @Parameter(description = "Slug projektu") @PathVariable String slug) {
        return ResponseEntity.ok(projectService.unpinProject(slug, getCurrentUsername()));
    }

    @Operation(summary = "Zmena poradia projektu", description = """
            Presunie projekt v manuálnom poradí (WIKI4AI-184). Práve jedno z telies:
            `{"beforeSlug": "..."}`, `{"afterSlug": "..."}` alebo `{"position": N}`
            (0-indexované, hodnota mimo rozsahu sa zovrie). Celé zoznam sa po presunutí
            prečísla husto 0..n-1. `updated_at` sa neposúva. Vyžaduje MANAGE oprávnenie.""")
    @ApiResponse(responseCode = "200", description = "Projekt presunutý, vráta sa s novou pozíciou")
    @ApiResponse(responseCode = "400", description = "Telelo neobsahuje práve jeden selektor (beforeSlug/afterSlug/position)")
    @ApiResponse(responseCode = "403", description = "Chýba MANAGE oprávnenie")
    @ApiResponse(responseCode = "404", description = "Projekt alebo kotva (beforeSlug/afterSlug) nebol nájdený")
    @PostMapping("/{slug}/reorder")
    public ResponseEntity<ProjectDTO> reorderProject(
            @Parameter(description = "Slug projektu") @PathVariable String slug,
            @RequestBody ReorderRequestDTO request) {
        return ResponseEntity.ok(projectService.reorderProject(slug, request, getCurrentUsername()));
    }

    // ── ID-based endpoints (for frontend compatibility) ──────────────────────

    @Operation(summary = "Detail projektu podľa ID", description = "Vráti detail projektu na základe jeho číselného ID. Cudzí súkromný (private) projekt vráti 404 (neodhaľuje existenciu).")
    @ApiResponse(responseCode = "200", description = "Projekt úspešne načítaný")
    @ApiResponse(responseCode = "404", description = "Projekt s daným ID nebol nájdený (alebo je súkromný a volateľ nie je vlastník/ADMIN)")
    @GetMapping("/by-id/{id}")
    public ResponseEntity<ProjectDTO> getProjectById(
            @Parameter(description = "ID projektu") @PathVariable Long id) {
        return ResponseEntity.ok(projectService.getProjectById(id, getCurrentUsername()));
    }

    @Operation(summary = "Aktualizácia projektu podľa ID", description = "Aktualizuje existujúci projekt podľa číselného ID. Vyžaduje MANAGE oprávnenie.")
    @ApiResponse(responseCode = "200", description = "Projekt úspešne aktualizovaný")
    @ApiResponse(responseCode = "400", description = "Neplatný vstup (validation error)")
    @ApiResponse(responseCode = "403", description = "Chýba MANAGE oprávnenie")
    @ApiResponse(responseCode = "404", description = "Projekt s daným ID nebol nájdený")
    @PutMapping("/by-id/{id}")
    public ResponseEntity<ProjectDTO> updateProjectById(
            @Parameter(description = "ID projektu") @PathVariable Long id,
            @Valid @RequestBody ProjectUpdateDTO dto) {
        String username = getCurrentUsername();
        return ResponseEntity.ok(projectService.updateProjectById(id, dto, username));
    }

    @Operation(summary = "Vymazanie projektu podľa ID", description = "Vymaže projekt podľa číselného ID. Vyžaduje MANAGE oprávnenie.")
    @ApiResponse(responseCode = "204", description = "Projekt úspešne vymazaný")
    @ApiResponse(responseCode = "403", description = "Chýba MANAGE oprávnenie")
    @ApiResponse(responseCode = "404", description = "Projekt s daným ID nebol nájdený")
    @DeleteMapping("/by-id/{id}")
    public ResponseEntity<Void> deleteProjectById(
            @Parameter(description = "ID projektu") @PathVariable Long id) {
        String username = getCurrentUsername();
        projectService.deleteProject(id, username);
        return ResponseEntity.noContent().build();
    }
}
