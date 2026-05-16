package com.wiki4ai.dto;

import java.util.List;

/**
 * Data Transfer Object for document content with rendered HTML and wiki links.
 * Used by the /content endpoint to return fully processed document data.
 */
public record DocumentContentDTO(
    Long id,
    String title,
    String htmlContent,      // Rendered markdown as HTML
    List<String> wikiLinks,   // Extracted [[WikiLink]] titles
    List<DocumentDTO> linkedDocuments  // Documents this one links to
) {}
