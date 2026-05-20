package com.wiki4ai.controller;

import com.wiki4ai.dto.ProjectCreateDTO;
import com.wiki4ai.dto.ProjectDTO;
import com.wiki4ai.dto.ProjectUpdateDTO;
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

    @Operation(summary = "Zoznam všetkých projektov", description = "Vráti zoznam všetkých wiki projektov zoradených podľa dátumu vytvorenia.")
    @ApiResponse(responseCode = "200", description = "Zoznam projektov úspešne načítaný")
    @GetMapping
    public ResponseEntity<List<ProjectDTO>> getAllProjects() {
        return ResponseEntity.ok(projectService.getAllProjects());
    }

    @Operation(summary = "Detail projektu podľa slugu", description = "Vráti detail projektu na základe jeho URL-friendly slugu.")
    @ApiResponse(responseCode = "200", description = "Projekt úspešne načítaný")
    @ApiResponse(responseCode = "404", description = "Projekt s daným slugom nebol nájdený")
    @GetMapping("/{slug}")
    public ResponseEntity<ProjectDTO> getProject(
            @Parameter(description = "Slug projektu") @PathVariable String slug) {
        return ResponseEntity.ok(projectService.getProjectBySlug(slug));
    }

    @Operation(summary = "Vytvorenie nového projektu", description = "Vytvorí nový wiki projekt. Vtvorca automaticky dostáva MANAGE oprávnenie.")
    @ApiResponse(responseCode = "201", description = "Projekt úspešne vytvorený")
    @ApiResponse(responseCode = "400", description = "Neplatný vstup (validation error)")
    @ApiResponse(responseCode = "409", description = "Projekt s rovnakým názvom už existuje")
    @PostMapping
    public ResponseEntity<ProjectDTO> createProject(@Valid @RequestBody ProjectCreateDTO dto) {
        String username = getCurrentUsername();
        ProjectDTO created = projectService.createProject(dto, username);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @Operation(summary = "Aktualizácia projektu", description = "Aktualizuje existujúci projekt podľa slugu. Vyžaduje MANAGE oprávnenie.")
    @ApiResponse(responseCode = "200", description = "Projekt úspešne aktualizovaný")
    @ApiResponse(responseCode = "400", description = "Neplatný vstup (validation error)")
    @ApiResponse(responseCode = "403", description = "Chýba MANAGE oprávnenie")
    @ApiResponse(responseCode = "404", description = "Projekt s daným slugom nebol nájdený")
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

    // ── ID-based endpoints (for frontend compatibility) ──────────────────────

    @Operation(summary = "Detail projektu podľa ID", description = "Vráti detail projektu na základe jeho číselného ID.")
    @ApiResponse(responseCode = "200", description = "Projekt úspešne načítaný")
    @ApiResponse(responseCode = "404", description = "Projekt s daným ID nebol nájdený")
    @GetMapping("/by-id/{id}")
    public ResponseEntity<ProjectDTO> getProjectById(
            @Parameter(description = "ID projektu") @PathVariable Long id) {
        return ResponseEntity.ok(projectService.getProjectById(id));
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
