package com.wiki4ai.controller;

import com.wiki4ai.service.EmbeddingClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Public (authenticated) availability status of the embedding sidecar
 * (WIKI4AI-35/36, epic WIKI4AI-26). The WebUI uses this to show a
 * "semantic search unavailable" hint instead of silently text-only results.
 */
@RestController
@RequestMapping("/api/v1/embeddings")
@Tag(name = "Embeddings", description = "Embedding sidecar status")
public class EmbeddingStatusController {

    private final EmbeddingClient embeddingClient;

    public EmbeddingStatusController(EmbeddingClient embeddingClient) {
        this.embeddingClient = embeddingClient;
    }

    @GetMapping("/status")
    @Operation(summary = "Embedding sidecar availability",
            description = "Whether semantic (vector) search is currently active. "
                    + "When unavailable, /search still works via text matching only.")
    public ResponseEntity<Map<String, Object>> status() {
        Map<String, Object> body = new HashMap<>();
        boolean available = embeddingClient.isAvailable();
        body.put("available", available);
        body.put("model", "Qwen3-Embedding-0.6B");
        body.put("dim", 1024);
        return ResponseEntity.ok(body);
    }
}
