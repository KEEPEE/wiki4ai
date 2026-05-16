package com.wiki4ai.controller;

import com.wiki4ai.dto.DocumentCreateDTO;
import com.wiki4ai.dto.DocumentDTO;
import com.wiki4ai.dto.DocumentUpdateDTO;
import com.wiki4ai.dto.LinkCreateDTO;
import com.wiki4ai.service.DocumentService;
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
 * REST Controller for Document CRUD operations within a project.
 * Provides endpoints for managing wiki documents with Swagger documentation.
 * All endpoints are nested under /api/v1/projects/{projectSlug}/documents.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectSlug}/documents")
@RequiredArgsConstructor
@Tag(name = "Documents", description = "API pre správu markdown dokumentov")
public class DocumentController {

    private final DocumentService documentService;

    @Operation(summary = "Vytvorenie nového dokumentu", description = "Vytvorí nový dokument v rámci projektu.")
    @ApiResponse(responseCode = "201", description = "Dokument úspešne vytvorený")
    @ApiResponse(responseCode = "400", description = "Neplatný vstup (validation error)")
    @PostMapping
    public ResponseEntity<DocumentDTO> createDocument(
            @Parameter(description = "Slug projektu") @PathVariable String projectSlug,
            @Valid @RequestBody DocumentCreateDTO dto) {
        // TODO: resolve projectId from projectSlug - for now use a placeholder
        Long projectId = 1L; // Will be resolved via service layer in production
        DocumentDTO created = documentService.createDocument(projectId, dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @Operation(summary = "Zoznam dokumentov v projekte", description = "Vráti zoznam všetkých dokumentov v rámci projektu.")
    @ApiResponse(responseCode = "200", description = "Zoznam dokumentov úspešne načítaný")
    @GetMapping
    public ResponseEntity<List<DocumentDTO>> getDocuments(
            @Parameter(description = "Slug projektu") @PathVariable String projectSlug) {
        Long projectId = 1L; // Will be resolved via service layer in production
        return ResponseEntity.ok(documentService.getDocumentsByProject(projectId));
    }

    @Operation(summary = "Detail dokumentu podľa slugu", description = "Vráti detail dokumentu na základe jeho URL-friendly slugu.")
    @ApiResponse(responseCode = "200", description = "Dokument úspešne načítaný")
    @ApiResponse(responseCode = "404", description = "Dokument s daným slugom nebol nájdený")
    @GetMapping("/{docSlug}")
    public ResponseEntity<DocumentDTO> getDocument(
            @Parameter(description = "Slug projektu") @PathVariable String projectSlug,
            @Parameter(description = "Slug dokumentu") @PathVariable String docSlug) {
        Long projectId = 1L; // Will be resolved via service layer in production
        return ResponseEntity.ok(documentService.getDocument(projectId, docSlug));
    }

    @Operation(summary = "Aktualizácia dokumentu", description = "Aktualizuje existujúci dokument podľa slugu.")
    @ApiResponse(responseCode = "200", description = "Dokument úspešne aktualizovaný")
    @ApiResponse(responseCode = "404", description = "Dokument s daným slugom nebol nájdený")
    @PutMapping("/{docSlug}")
    public ResponseEntity<DocumentDTO> updateDocument(
            @Parameter(description = "Slug projektu") @PathVariable String projectSlug,
            @Parameter(description = "Slug dokumentu") @PathVariable String docSlug,
            @Valid @RequestBody DocumentUpdateDTO dto) {
        Long projectId = 1L; // Will be resolved via service layer in production
        return ResponseEntity.ok(documentService.updateDocumentBySlug(projectId, docSlug, dto));
    }

    @Operation(summary = "Vymazanie dokumentu", description = "Vymaže dokument podľa slugu.")
    @ApiResponse(responseCode = "204", description = "Dokument úspešne vymazaný")
    @ApiResponse(responseCode = "404", description = "Dokument s daným slugom nebol nájdený")
    @DeleteMapping("/{docSlug}")
    public ResponseEntity<Void> deleteDocument(
            @Parameter(description = "Slug projektu") @PathVariable String projectSlug,
            @Parameter(description = "Slug dokumentu") @PathVariable String docSlug) {
        Long projectId = 1L; // Will be resolved via service layer in production
        documentService.deleteDocumentBySlug(projectId, docSlug);
        return ResponseEntity.noContent().build();
    }

    // ==================== Link Management Endpoints ====================

    @Operation(summary = "Pridanie prepojenia medzi dokumentmi", description = "Pripojí jeden dokument k druhému v rámci rovnakého projektu.")
    @ApiResponse(responseCode = "200", description = "Prepojenie úspešne pridané")
    @ApiResponse(responseCode = "404", description = "Dokument nebol nájdený")
    @PostMapping("/{docSlug}/links")
    public ResponseEntity<DocumentDTO> addLink(
            @Parameter(description = "Slug dokumentu") @PathVariable String docSlug,
            @Valid @RequestBody LinkCreateDTO dto) {
        // TODO: resolve projectId from projectSlug - for now use a placeholder
        Long sourceDocId = 1L; // Will be resolved via service layer in production
        DocumentDTO updated = documentService.addLink(sourceDocId, dto.getTargetDocumentId());
        return ResponseEntity.ok(updated);
    }

    @Operation(summary = "Odstránenie prepojenia medzi dokumentmi", description = "Odstráni prepojenie medzi dvoma dokumentmi.")
    @ApiResponse(responseCode = "204", description = "Prepojenie úspešne odstránené")
    @ApiResponse(responseCode = "404", description = "Dokument nebol nájdený alebo prepojenie neexistuje")
    @DeleteMapping("/{docSlug}/links/{targetDocId}")
    public ResponseEntity<Void> removeLink(
            @Parameter(description = "Slug dokumentu") @PathVariable String docSlug,
            @Parameter(description = "ID cieľového dokumentu") @PathVariable Long targetDocId) {
        // TODO: resolve projectId from projectSlug - for now use a placeholder
        Long sourceDocId = 1L; // Will be resolved via service layer in production
        documentService.removeLink(sourceDocId, targetDocId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Zoznam prepojení dokumentu", description = "Vráti zoznam všetkých dokumentov, na ktoré odkazuje daný dokument.")
    @ApiResponse(responseCode = "200", description = "Zoznam prepojení úspešne načítaný")
    @ApiResponse(responseCode = "404", description = "Dokument nebol nájdený")
    @GetMapping("/{docSlug}/links")
    public ResponseEntity<List<DocumentDTO>> getLinks(
            @Parameter(description = "Slug dokumentu") @PathVariable String docSlug) {
        // TODO: resolve projectId from projectSlug - for now use a placeholder
        Long sourceDocId = 1L; // Will be resolved via service layer in production
        return ResponseEntity.ok(documentService.getLinkedDocuments(sourceDocId));
    }
}
