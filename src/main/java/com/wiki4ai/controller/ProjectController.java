package com.wiki4ai.controller;

import com.wiki4ai.dto.ProjectDTO;
import com.wiki4ai.service.ProjectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST Controller for Project CRUD operations.
 * Provides endpoints for managing wiki projects with Swagger documentation.
 */
@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
@Tag(name = "Project", description = "API for managing Wiki Projects")
public class ProjectController {

    private final ProjectService projectService;

    @Operation(summary = "Get all projects", description = "Returns a list of all wiki projects ordered by creation date.")
    @ApiResponse(responseCode = "200", description = "List of projects retrieved successfully")
    @GetMapping
    public ResponseEntity<List<ProjectDTO>> getAllProjects() {
        return ResponseEntity.ok(projectService.getAllProjects());
    }

    @Operation(summary = "Get project by ID", description = "Returns a single project by its ID.")
    @ApiResponse(responseCode = "200", description = "Project retrieved successfully")
    @ApiResponse(responseCode = "404", description = "Project not found")
    @GetMapping("/{id}")
    public ResponseEntity<ProjectDTO> getProjectById(
            @Parameter(description = "Project ID") @PathVariable Long id) {
        return ResponseEntity.ok(projectService.getProjectById(id));
    }

    @Operation(summary = "Get project by slug", description = "Returns a single project by its URL-friendly slug.")
    @ApiResponse(responseCode = "200", description = "Project retrieved successfully")
    @ApiResponse(responseCode = "404", description = "Project not found")
    @GetMapping("/slug/{slug}")
    public ResponseEntity<ProjectDTO> getProjectBySlug(
            @Parameter(description = "Project slug") @PathVariable String slug) {
        return ResponseEntity.ok(projectService.getProjectBySlug(slug));
    }

    @Operation(summary = "Create a new project", description = "Creates a new wiki project.")
    @ApiResponse(responseCode = "201", description = "Project created successfully")
    @PostMapping
    public ResponseEntity<ProjectDTO> createProject(@Valid @RequestBody ProjectDTO dto) {
        ProjectDTO created = projectService.createProject(dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @Operation(summary = "Update a project", description = "Updates an existing project by ID.")
    @ApiResponse(responseCode = "200", description = "Project updated successfully")
    @ApiResponse(responseCode = "404", description = "Project not found")
    @PutMapping("/{id}")
    public ResponseEntity<ProjectDTO> updateProject(
            @Parameter(description = "Project ID") @PathVariable Long id,
            @Valid @RequestBody ProjectDTO dto) {
        return ResponseEntity.ok(projectService.updateProject(id, dto));
    }

    @Operation(summary = "Delete a project", description = "Deletes a project by ID.")
    @ApiResponse(responseCode = "204", description = "Project deleted successfully")
    @ApiResponse(responseCode = "404", description = "Project not found")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteProject(@Parameter(description = "Project ID") @PathVariable Long id) {
        projectService.deleteProject(id);
        return ResponseEntity.noContent().build();
    }
}
