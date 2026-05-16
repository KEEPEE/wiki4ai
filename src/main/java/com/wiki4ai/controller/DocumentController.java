package com.wiki4ai.controller;

import com.wiki4ai.dto.DocumentDTO;
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
 * REST Controller for Document CRUD operations.
 * Provides endpoints for managing wiki documents with Swagger documentation.
 */
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
@Tag(name = "Document", description = "API for managing Wiki Documents")
public class DocumentController {

    private final DocumentService documentService;

    @Operation(summary = "Get all documents in a project", description = "Returns a list of all documents belonging to a specific project.")
    @ApiResponse(responseCode = "200", description = "List of documents retrieved successfully")
    @GetMapping("/project/{projectId}")
    public ResponseEntity<List<DocumentDTO>> getDocumentsByProject(
            @Parameter(description = "Project ID") @PathVariable Long projectId) {
        return ResponseEntity.ok(documentService.getDocumentsByProject(projectId));
    }

    @Operation(summary = "Get document by ID", description = "Returns a single document by its ID.")
    @ApiResponse(responseCode = "200", description = "Document retrieved successfully")
    @ApiResponse(responseCode = "404", description = "Document not found")
    @GetMapping("/{id}")
    public ResponseEntity<DocumentDTO> getDocumentById(
            @Parameter(description = "Document ID") @PathVariable Long id) {
        return ResponseEntity.ok(documentService.getDocumentById(id));
    }

    @Operation(summary = "Create a new document", description = "Creates a new document within a project.")
    @ApiResponse(responseCode = "201", description = "Document created successfully")
    @PostMapping("/project/{projectId}")
    public ResponseEntity<DocumentDTO> createDocument(
            @Parameter(description = "Project ID") @PathVariable Long projectId,
            @Valid @RequestBody DocumentDTO dto) {
        DocumentDTO created = documentService.createDocument(projectId, dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @Operation(summary = "Update a document", description = "Updates an existing document by ID.")
    @ApiResponse(responseCode = "200", description = "Document updated successfully")
    @ApiResponse(responseCode = "404", description = "Document not found")
    @PutMapping("/{id}")
    public ResponseEntity<DocumentDTO> updateDocument(
            @Parameter(description = "Document ID") @PathVariable Long id,
            @Valid @RequestBody DocumentDTO dto) {
        return ResponseEntity.ok(documentService.updateDocument(id, dto));
    }

    @Operation(summary = "Delete a document", description = "Deletes a document by ID.")
    @ApiResponse(responseCode = "204", description = "Document deleted successfully")
    @ApiResponse(responseCode = "404", description = "Document not found")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteDocument(@Parameter(description = "Document ID") @PathVariable Long id) {
        documentService.deleteDocument(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Search documents", description = "Searches for documents by keyword within a project.")
    @ApiResponse(responseCode = "200", description = "Search results retrieved successfully")
    @GetMapping("/project/{projectId}/search")
    public ResponseEntity<List<DocumentDTO>> searchDocuments(
            @Parameter(description = "Project ID") @PathVariable Long projectId,
            @Parameter(description = "Search keyword") @RequestParam String keyword) {
        return ResponseEntity.ok(documentService.searchDocuments(projectId, keyword));
    }
}
