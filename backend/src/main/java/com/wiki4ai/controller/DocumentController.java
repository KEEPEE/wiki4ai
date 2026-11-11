package com.wiki4ai.controller;

import com.wiki4ai.dto.CopyRequestDTO;
import com.wiki4ai.dto.DocumentContentDTO;
import com.wiki4ai.dto.DocumentCreateDTO;
import com.wiki4ai.dto.DocumentDTO;
import com.wiki4ai.dto.DocumentSummaryDTO;
import com.wiki4ai.dto.DocumentUpdateDTO;
import com.wiki4ai.dto.LinkCreateDTO;
import com.wiki4ai.dto.MoveRequestDTO;
import com.wiki4ai.dto.ReorderRequestDTO;
import com.wiki4ai.service.DocumentService;
import com.wiki4ai.service.ProjectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
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
     * Resolve projectId from projectSlug. Visibility-aware (WIKI4AI-99): a foreign
     * private project resolves to 404, so every document endpoint nested under it
     * is unreachable for non-owner/non-ADMIN callers.
     */
    private Long resolveProjectId(String projectSlug) {
        return projectService.getProjectBySlug(projectSlug, getCurrentUsername()).getId();
    }

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

    @Operation(summary = "Vytvorenie nového dokumentu", description = "Vytvorí nový dokument v rámci projektu.")
    @ApiResponse(responseCode = "201", description = "Dokument úspešne vytvorený")
    @ApiResponse(responseCode = "400", description = "Neplatný vstup (validation error)")
    @PostMapping
    public ResponseEntity<DocumentDTO> createDocument(
            @Parameter(description = "Slug projektu") @PathVariable String projectSlug,
            @Valid @RequestBody DocumentCreateDTO dto) {
        Long projectId = resolveProjectId(projectSlug);
        String username = getCurrentUsername();
        DocumentDTO created = documentService.createDocument(projectId, dto, username);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @Operation(summary = "Zoznam dokumentov v projekte (paginovaný, filter + poradie)", description = """
            Vráti paginovaný zoznam dokumentov v rámci projektu. Podporuje `page` a `size` pre navigáciu
            a parametre z WIKI4AI-184: `sort` = manual | name_asc | name_desc | created_asc | created_desc |
            updated_desc (default) a `q` = case-insensitive filter na názov aj obsah.
            Vždy sú pripnuté (pinned) dokumenty na vrchu a poradie je stabilné aj medzi stránkami.
            Odozva neobsahuje 'content' pole pre menšiu veľkosť response.""")
    @ApiResponse(responseCode = "200", description = "Paginovaný zoznam dokumentov úspešne načítaný")
    @ApiResponse(responseCode = "400", description = "Neplatná hodnota parametra sort")
    @GetMapping
    public ResponseEntity<Page<DocumentSummaryDTO>> getDocuments(
            @Parameter(description = "Slug projektu") @PathVariable String projectSlug,
            @Parameter(description = "Číslo stránky (0-indexed, default 0)") @RequestParam(required = false, defaultValue = "0") int page,
            @Parameter(description = "Veľkosť stránky (default 50, max 100)") @RequestParam(required = false, defaultValue = "50") int size,
            @Parameter(description = "Poradie: manual | name_asc | name_desc | created_asc | created_desc | updated_desc")
            @RequestParam(required = false) String sort,
            @Parameter(description = "Case-insensitive filter na názov a obsah dokumentu")
            @RequestParam(required = false) String q) {

        Long projectId = resolveProjectId(projectSlug);
        String username = getCurrentUsername();

        // Clamp size to reasonable bounds. The ordering is built by the service from
        // `sort` (WIKI4AI-184) — it has to be, because the sort must reach the database
        // for the pages to be stable.
        int pageSize = Math.min(Math.max(size, 1), 100);

        return ResponseEntity.ok(
                documentService.getDocumentsByProjectPaginated(projectId, page, pageSize, sort, q, username));
    }

    // ── Pin to top + manual order (WIKI4AI-184) ───────────────────────────────
    //
    // Dedicated POST endpoints, consistent with the project ones. Neither pin nor
    // reorder bumps the document's optimistic-locking version, so an editor working on
    // the same document is not pushed into a 409 by a sidebar drag.

    @Operation(summary = "Pripnutie dokumentu na vrch", description = """
            Pripne dokument na vrch zoznamu v rámci projektu (WIKI4AI-184). Pri prvom pripnutí
            získa aj pozíciu nad súčasnou prvou položkou manuálneho poradia projektu.
            `version` ani `updated_at` sa nemení. Vyžaduje UPDATE oprávnenie v projekte.""")
    @ApiResponse(responseCode = "200", description = "Dokument pripnutý")
    @ApiResponse(responseCode = "403", description = "Chýba UPDATE oprávnenie")
    @ApiResponse(responseCode = "404", description = "Dokument alebo projekt nebol nájdený")
    @PostMapping("/{docSlug}/pin")
    public ResponseEntity<DocumentDTO> pinDocument(
            @Parameter(description = "Slug projektu") @PathVariable String projectSlug,
            @Parameter(description = "Slug dokumentu") @PathVariable String docSlug) {
        Long projectId = resolveProjectId(projectSlug);
        return ResponseEntity.ok(documentService.pinDocument(projectId, docSlug, getCurrentUsername()));
    }

    @Operation(summary = "Odopnutie dokumentu", description = """
            Odoberie dokument z vrchu zoznamu (WIKI4AI-184). Manuálna pozícia zostáva zachovaná.
            `version` ani `updated_at` sa nemení. Vyžaduje UPDATE oprávnenie v projekte.""")
    @ApiResponse(responseCode = "200", description = "Dokument odopnutý")
    @ApiResponse(responseCode = "403", description = "Chýba UPDATE oprávnenie")
    @ApiResponse(responseCode = "404", description = "Dokument alebo projekt nebol nájdený")
    @PostMapping("/{docSlug}/unpin")
    public ResponseEntity<DocumentDTO> unpinDocument(
            @Parameter(description = "Slug projektu") @PathVariable String projectSlug,
            @Parameter(description = "Slug dokumentu") @PathVariable String docSlug) {
        Long projectId = resolveProjectId(projectSlug);
        return ResponseEntity.ok(documentService.unpinDocument(projectId, docSlug, getCurrentUsername()));
    }

    @Operation(summary = "Zmena poradia dokumentu", description = """
            Presunie dokument v manuálnom poradí projektu (WIKI4AI-184). Práve jedno z telies:
            `{"beforeSlug": "..."}`, `{"afterSlug": "..."}` alebo `{"position": N}`.
            Poradie dokumentov projektu sa prečísla husto 0..n-1. `version` sa nenavyšuje,
            takže prebiehajúca editácia obsahu neskončí 409. Vyžaduje UPDATE oprávnenie.""")
    @ApiResponse(responseCode = "200", description = "Dokument presunutý, vráti sa s novou pozíciou")
    @ApiResponse(responseCode = "400", description = "Telo neobsahuje práve jeden selektor (beforeSlug/afterSlug/position)")
    @ApiResponse(responseCode = "403", description = "Chýba UPDATE oprávnenie")
    @ApiResponse(responseCode = "404", description = "Dokument alebo kotva (beforeSlug/afterSlug) nebol nájdený")
    @PostMapping("/{docSlug}/reorder")
    public ResponseEntity<DocumentDTO> reorderDocument(
            @Parameter(description = "Slug projektu") @PathVariable String projectSlug,
            @Parameter(description = "Slug dokumentu") @PathVariable String docSlug,
            @RequestBody ReorderRequestDTO request) {
        Long projectId = resolveProjectId(projectSlug);
        return ResponseEntity.ok(
                documentService.reorderDocument(projectId, docSlug, request, getCurrentUsername()));
    }

    @Operation(summary = "Detail dokumentu podľa slugu", description = "Vráti detail dokumentu na základe jeho URL-friendly slugu.")
    @ApiResponse(responseCode = "200", description = "Dokument úspešne načítaný")
    @ApiResponse(responseCode = "404", description = "Dokument s daným slugom nebol nájdený")
    @GetMapping("/{docSlug}")
    public ResponseEntity<DocumentDTO> getDocument(
            @Parameter(description = "Slug projektu") @PathVariable String projectSlug,
            @Parameter(description = "Slug dokumentu") @PathVariable String docSlug) {
        Long projectId = resolveProjectId(projectSlug);
        String username = getCurrentUsername();
        return ResponseEntity.ok(documentService.getDocument(projectId, docSlug, username));
    }

    @Operation(summary = "Obsah dokumentu s renderovaným markdownom", description = "Vráti dokument s renderovaným HTML obsahom a prepojenými wiki odkazmi.")
    @ApiResponse(responseCode = "200", description = "Obsah dokumentu úspešne načítaný")
    @ApiResponse(responseCode = "404", description = "Dokument s daným slugom nebol nájdený")
    @GetMapping("/{docSlug}/content")
    public ResponseEntity<DocumentContentDTO> getContent(
            @Parameter(description = "Slug projektu") @PathVariable String projectSlug,
            @Parameter(description = "Slug dokumentu") @PathVariable String docSlug) {
        Long projectId = resolveProjectId(projectSlug);
        String username = getCurrentUsername();
        return ResponseEntity.ok(documentService.getDocumentContent(projectId, docSlug, username));
    }

    @Operation(summary = "Aktualizácia dokumentu", description = "Aktualizuje existujúci dokument podľa slugu. "
            + "Voliteľné expectedVersion (optimistic locking, WIKI4AI-72): ak je zadané a nezhodí sa "
            + "s aktuálnou verziou dokumentu, vráti 409 Conflict — dokument upravil iný autor.")
    @ApiResponse(responseCode = "200", description = "Dokument úspešne aktualizovaný")
    @ApiResponse(responseCode = "404", description = "Dokument s daným slugom nebol nájdený")
    @ApiResponse(responseCode = "409", description = "Konflikt verzií — dokument bol od posledného čítania upravený iným autorom (expectedVersion nezodpovedá aktuálnej verzii, alebo súbežný zápis). Znovu načítajte a skúste znova.")
    @PutMapping("/{docSlug}")
    public ResponseEntity<DocumentDTO> updateDocument(
            @Parameter(description = "Slug projektu") @PathVariable String projectSlug,
            @Parameter(description = "Slug dokumentu") @PathVariable String docSlug,
            @Valid @RequestBody DocumentUpdateDTO dto) {
        Long projectId = resolveProjectId(projectSlug);
        String username = getCurrentUsername();
        return ResponseEntity.ok(documentService.updateDocumentBySlug(projectId, docSlug, dto, username));
    }

    @Operation(summary = "Vymazanie dokumentu", description = "Vymaže dokument podľa slugu.")
    @ApiResponse(responseCode = "204", description = "Dokument úspešne vymazaný")
    @ApiResponse(responseCode = "404", description = "Dokument s daným slugom nebol nájdený")
    @DeleteMapping("/{docSlug}")
    public ResponseEntity<Void> deleteDocument(
            @Parameter(description = "Slug projektu") @PathVariable String projectSlug,
            @Parameter(description = "Slug dokumentu") @PathVariable String docSlug) {
        Long projectId = resolveProjectId(projectSlug);
        String username = getCurrentUsername();
        documentService.deleteDocumentBySlug(projectId, docSlug, username);
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
        Long projectId = resolveProjectId(projectSlug);
        DocumentDTO sourceDoc = documentService.getDocument(projectId, docSlug, getCurrentUsername());
        String username = getCurrentUsername();
        DocumentDTO updated = documentService.addLink(sourceDoc.getId(), dto.getTargetDocumentId(), username);
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
        Long projectId = resolveProjectId(projectSlug);
        DocumentDTO sourceDoc = documentService.getDocument(projectId, docSlug, getCurrentUsername());
        String username = getCurrentUsername();
        documentService.removeLink(sourceDoc.getId(), targetDocId, username);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Zoznam prepojení dokumentu", description = "Vráti zoznam všetkých dokumentov, na ktoré odkazuje daný dokument.")
    @ApiResponse(responseCode = "200", description = "Zoznam prepojení úspešne načítaný")
    @ApiResponse(responseCode = "404", description = "Dokument nebol nájdený")
    @GetMapping("/{docSlug}/links")
    public ResponseEntity<List<DocumentDTO>> getLinks(
            @Parameter(description = "Slug projektu") @PathVariable String projectSlug,
            @Parameter(description = "Slug dokumentu") @PathVariable String docSlug) {
        Long projectId = resolveProjectId(projectSlug);
        DocumentDTO sourceDoc = documentService.getDocument(projectId, docSlug, getCurrentUsername());
        String username = getCurrentUsername();
        return ResponseEntity.ok(documentService.getLinkedDocuments(sourceDoc.getId(), username));
    }

    @Operation(summary = "Backlinky dokumentu", description = "Vráti zoznam všetkých dokumentov, ktoré odkazujú NA daný dokument (reverse links).")
    @ApiResponse(responseCode = "200", description = "Zoznam backlinkov úspešne načítaný")
    @ApiResponse(responseCode = "404", description = "Dokument nebol nájdený")
    @GetMapping("/{docSlug}/backlinks")
    public ResponseEntity<List<DocumentDTO>> getBacklinks(
            @Parameter(description = "Slug projektu") @PathVariable String projectSlug,
            @Parameter(description = "Slug dokumentu") @PathVariable String docSlug) {
        Long projectId = resolveProjectId(projectSlug);
        DocumentDTO sourceDoc = documentService.getDocument(projectId, docSlug, getCurrentUsername());
        String username = getCurrentUsername();
        return ResponseEntity.ok(documentService.getBacklinks(sourceDoc.getId(), username));
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
        String username = getCurrentUsername();
        List<DocumentDTO> results = documentService.searchDocuments(projectId, keyword.trim(), username);
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
        String username = getCurrentUsername();

        // Read file content as text
        String content = new String(file.getBytes(), StandardCharsets.UTF_8);

        DocumentDTO created = documentService.uploadDocument(projectId, filename, content, username);
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

    // ==================== Move Document Endpoint ====================

    @Operation(summary = "Presunúť dokument do iného projektu", description = "Presunie dokument z aktuálneho projektu do cieľového projektu. Všetky prepojenia sú odstránené.")
    @ApiResponse(responseCode = "200", description = "Dokument úspešne presunutý")
    @ApiResponse(responseCode = "404", description = "Dokument alebo cieľový projekt nebol nájdený")
    @ApiResponse(responseCode = "409", description = "Dokument s rovnakým názvom už existuje v cieľovom projekte")
    @PostMapping("/{docSlug}/move")
    public ResponseEntity<DocumentDTO> moveDocument(
            @Parameter(description = "Slug projektu") @PathVariable String projectSlug,
            @Parameter(description = "Slug dokumentu") @PathVariable String docSlug,
            @Valid @RequestBody MoveRequestDTO dto) {

        Long sourceProjectId = resolveProjectId(projectSlug);
        String username = getCurrentUsername();
        DocumentDTO moved = documentService.moveDocument(sourceProjectId, docSlug, dto.getTargetProjectSlug(), username);
        return ResponseEntity.ok(moved);
    }

    // ==================== Copy Document Endpoint ====================

    @Operation(summary = "Kopírovať dokument do iného projektu", description = "Vytvorí kópiu dokumentu v cieľovom projekte (alebo v rovnakom, ak nie je špecifikovaný cieľ). Kópia má názov \"{title} (copy)\" a nemá žiadne prepojenia.")
    @ApiResponse(responseCode = "201", description = "Kópia dokumentu úspešne vytvorená")
    @ApiResponse(responseCode = "404", description = "Dokument alebo cieľový projekt nebol nájdený")
    @PostMapping("/{docSlug}/copy")
    public ResponseEntity<DocumentDTO> copyDocument(
            @Parameter(description = "Slug projektu") @PathVariable String projectSlug,
            @Parameter(description = "Slug dokumentu") @PathVariable String docSlug,
            @RequestBody(required = false) CopyRequestDTO dto) {

        Long sourceProjectId = resolveProjectId(projectSlug);
        String targetProjectSlug = (dto != null) ? dto.getTargetProjectSlug() : null;
        String username = getCurrentUsername();
        DocumentDTO copied = documentService.copyDocument(sourceProjectId, docSlug, targetProjectSlug, username);
        return ResponseEntity.status(HttpStatus.CREATED).body(copied);
    }
}
