package com.wiki4ai.controller;

import com.wiki4ai.dto.DocumentContentDTO;
import com.wiki4ai.dto.DocumentCreateDTO;
import com.wiki4ai.dto.DocumentDTO;
import com.wiki4ai.dto.DocumentUpdateDTO;
import com.wiki4ai.dto.LinkCreateDTO;
import com.wiki4ai.dto.ProjectDTO;
import com.wiki4ai.service.DocumentService;
import com.wiki4ai.service.ProjectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
    private final ProjectService projectService;

    /**
     * Resolve projectId from projectSlug.
     */
    private Long resolveProjectId(String projectSlug) {
        ProjectDTO project = projectService.getProjectBySlug(projectSlug);
        return project.getId();
    }

    /**
     * Resolve source document ID from docSlug within a project.
     */
    private Long resolveSourceDocId(String projectSlug, String docSlug) {
        DocumentDTO doc = documentService.getDocument(resolveProjectId(projectSlug), docSlug);
        return doc.getId();
    }

    @Operation(summary = "Vytvorenie nového dokumentu", description = "Vytvorí nový dokument v rámci projektu.")
    @ApiResponse(responseCode = "201", description = "Dokument úspešne vytvorený")
    @ApiResponse(responseCode = "400", description = "Neplatný vstup (validation error)")
    @PostMapping
    public ResponseEntity<DocumentDTO> createDocument(
            @Parameter(description = "Slug projektu") @PathVariable String projectSlug,
            @Valid @RequestBody DocumentCreateDTO dto) {
        Long projectId = resolveProjectId(projectSlug);
        DocumentDTO created = documentService.createDocument(projectId, dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @Operation(summary = "Zoznam dokumentov v projekte", description = "Vráti zoznam všetkých dokumentov v rámci projektu.")
    @ApiResponse(responseCode = "200", description = "Zoznam dokumentov úspešne načítaný")
    @GetMapping
    public ResponseEntity<List<DocumentDTO>> getDocuments(
            @Parameter(description = "Slug projektu") @PathVariable String projectSlug) {
        Long projectId = resolveProjectId(projectSlug);
        return ResponseEntity.ok(documentService.getDocumentsByProject(projectId));
    }

    @Operation(summary = "Detail dokumentu podľa slugu", description = "Vráti detail dokumentu na základe jeho URL-friendly slugu.")
    @ApiResponse(responseCode = "200", description = "Dokument úspešne načítaný")
    @ApiResponse(responseCode = "404", description = "Dokument s daným slugom nebol nájdený")
    @GetMapping("/{docSlug}")
    public ResponseEntity<DocumentDTO> getDocument(
            @Parameter(description = "Slug projektu") @PathVariable String projectSlug,
            @Parameter(description = "Slug dokumentu") @PathVariable String docSlug) {
        Long projectId = resolveProjectId(projectSlug);
        return ResponseEntity.ok(documentService.getDocument(projectId, docSlug));
    }

    @Operation(summary = "Obsah dokumentu s renderovaným markdownom", description = "Vráti dokument s renderovaným HTML obsahom a prepojenými wiki odkazmi.")
    @ApiResponse(responseCode = "200", description = "Obsah dokumentu úspešne načítaný")
    @ApiResponse(responseCode = "404", description = "Dokument s daným slugom nebol nájdený")
    @GetMapping("/{docSlug}/content")
    public ResponseEntity<DocumentContentDTO> getContent(
            @Parameter(description = "Slug projektu") @PathVariable String projectSlug,
            @Parameter(description = "Slug dokumentu") @PathVariable String docSlug) {
        Long projectId = resolveProjectId(projectSlug);
        return ResponseEntity.ok(documentService.getDocumentContent(projectId, docSlug));
    }

    @Operation(summary = "Aktualizácia dokumentu", description = "Aktualizuje existujúci dokument podľa slugu.")
    @ApiResponse(responseCode = "200", description = "Dokument úspešne aktualizovaný")
    @ApiResponse(responseCode = "404", description = "Dokument s daným slugom nebol nájdený")
    @PutMapping("/{docSlug}")
    public ResponseEntity<DocumentDTO> updateDocument(
            @Parameter(description = "Slug projektu") @PathVariable String projectSlug,
            @Parameter(description = "Slug dokumentu") @PathVariable String docSlug,
            @Valid @RequestBody DocumentUpdateDTO dto) {
        Long projectId = resolveProjectId(projectSlug);
        return ResponseEntity.ok(documentService.updateDocumentBySlug(projectId, docSlug, dto));
    }

    @Operation(summary = "Vymazanie dokumentu", description = "Vymaže dokument podľa slugu.")
    @ApiResponse(responseCode = "204", description = "Dokument úspešne vymazaný")
    @ApiResponse(responseCode = "404", description = "Dokument s daným slugom nebol nájdený")
    @DeleteMapping("/{docSlug}")
    public ResponseEntity<Void> deleteDocument(
            @Parameter(description = "Slug projektu") @PathVariable String projectSlug,
            @Parameter(description = "Slug dokumentu") @PathVariable String docSlug) {
        Long projectId = resolveProjectId(projectSlug);
        documentService.deleteDocumentBySlug(projectId, docSlug);
        return ResponseEntity.noContent().build();
    }

    // ==================== Link Management Endpoints ====================

    @Operation(summary = "Pridanie prepojenia medzi dokumentmi", description = "Pripojí jeden dokument k druhému v rámci rovnakého projektu.")
    @ApiResponse(responseCode = "200", description = "Prepojenie úspešne pridané")
    @ApiResponse(responseCode = "404", description = "Dokument nebol nájdený")
    @PostMapping("/{docSlug}/links")
    public ResponseEntity<DocumentDTO> addLink(
            @Parameter(description = "Slug projektu") @PathVariable String projectSlug,
            @Parameter(description = "Slug dokumentu") @PathVariable String docSlug,
            @Valid @RequestBody LinkCreateDTO dto) {
        Long sourceDocId = resolveSourceDocId(projectSlug, docSlug);
        DocumentDTO updated = documentService.addLink(sourceDocId, dto.getTargetDocumentId());
        return ResponseEntity.ok(updated);
    }

    @Operation(summary = "Odstránenie prepojenia medzi dokumentmi", description = "Odstráni prepojenie medzi dvoma dokumentmi.")
    @ApiResponse(responseCode = "204", description = "Prepojenie úspešne odstránené")
    @ApiResponse(responseCode = "404", description = "Dokument nebol nájdený alebo prepojenie neexistuje")
    @DeleteMapping("/{docSlug}/links/{targetDocId}")
    public ResponseEntity<Void> removeLink(
            @Parameter(description = "Slug projektu") @PathVariable String projectSlug,
            @Parameter(description = "Slug dokumentu") @PathVariable String docSlug,
            @Parameter(description = "ID cieľového dokumentu") @PathVariable Long targetDocId) {
        Long sourceDocId = resolveSourceDocId(projectSlug, docSlug);
        documentService.removeLink(sourceDocId, targetDocId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Zoznam prepojení dokumentu", description = "Vráti zoznam všetkých dokumentov, na ktoré odkazuje daný dokument.")
    @ApiResponse(responseCode = "200", description = "Zoznam prepojení úspešne načítaný")
    @ApiResponse(responseCode = "404", description = "Dokument nebol nájdený")
    @GetMapping("/{docSlug}/links")
    public ResponseEntity<List<DocumentDTO>> getLinks(
            @Parameter(description = "Slug projektu") @PathVariable String projectSlug,
            @Parameter(description = "Slug dokumentu") @PathVariable String docSlug) {
        Long sourceDocId = resolveSourceDocId(projectSlug, docSlug);
        return ResponseEntity.ok(documentService.getLinkedDocuments(sourceDocId));
    }

    // ==================== Search Endpoint ====================

    @Operation(summary = "Vyhľadávanie dokumentov v projekte", description = "Vyhľadá dokumenty podľa kľúčového slova v obsahu v rámci projektu.")
    @ApiResponse(responseCode = "200", description = "Výsledky vyhľadávania úspešne vrátené")
    @ApiResponse(responseCode = "400", description = "Neplatný keyword (musí byť aspoň 2 znaky)")
    @ApiResponse(responseCode = "404", description = "Projekt nebol nájdený")
    @GetMapping("/search")
    public ResponseEntity<List<DocumentDTO>> searchDocuments(
            @Parameter(description = "Slug projektu") @PathVariable String projectSlug,
            @Parameter(description = "Kľúčové slovo na vyhľadávanie (min. 2 znaky)") @RequestParam(required = false) String keyword) {

        // Validate keyword - must be at least 2 characters
        if (keyword == null || keyword.trim().length() < 2) {
            return ResponseEntity.badRequest().build();
        }

        Long projectId = resolveProjectId(projectSlug);
        List<DocumentDTO> results = documentService.searchDocuments(projectId, keyword.trim());
        return ResponseEntity.ok(results);
    }

    // ==================== Upload Endpoint ====================

    @Operation(summary = "Upload markdown súbor", description = "Nahraje .md alebo .markdown súbor a vytvorí z neho dokument v projekte.")
    @ApiResponse(responseCode = "201", description = "Dokument úspešne vytvorený z nahraného súboru")
    @ApiResponse(responseCode = "400", description = "Neplatný formát súboru (len .md a .markdown)")
    @ApiResponse(responseCode = "409", description = "Dokument s rovnakým názvom už existuje v projekte")
    @ApiResponse(responseCode = "413", description = "Súbor je príliš veľký (max 5MB)")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentDTO> uploadDocument(
            @Parameter(description = "Slug projektu") @PathVariable String projectSlug,
            @Parameter(description = "Markdown súbor (.md alebo .markdown)") @RequestParam("file") MultipartFile file) throws IOException {

        // Validate file extension
        String filename = file.getOriginalFilename();
        if (!isValidMarkdownFile(filename)) {
            throw new IllegalArgumentException(
                    "Invalid file format. Only .md and .markdown files are allowed.");
        }

        Long projectId = resolveProjectId(projectSlug);

        // Read file content as text
        String content = new String(file.getBytes(), StandardCharsets.UTF_8);

        DocumentDTO created = documentService.uploadDocument(projectId, filename, content);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * Check if the given filename has a valid markdown extension (.md or .markdown).
     */
    private boolean isValidMarkdownFile(String filename) {
        if (filename == null || filename.isBlank()) {
            return false;
        }
        int lastDot = filename.lastIndexOf('.');
        if (lastDot <= 0) {
            return false;
        }
        String extension = filename.substring(lastDot + 1).toLowerCase();
        return "md".equals(extension) || "markdown".equals(extension);
    }
}
