package com.wiki4ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * DTO for updating an existing Document.
 * Title, content and contentEdits are all optional, but at least one must be
 * provided (enforced in DocumentService). Omitted fields remain unchanged.
 *
 * Semantics:
 * - content -> full replacement of the document content (backward compatible)
 * - contentEdits -> incremental find/replace edits applied sequentially to the
 *   current content
 * - Allowed combinations: title-only, content-only, contentEdits-only,
 *   title+content, title+contentEdits
 * - content + contentEdits in the same request is rejected with 400
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocumentUpdateDTO {

    @Schema(description = "New title for the document (optional). When changed, the slug is regenerated.")
    private String title;

    @Schema(description = "Full replacement of the document content (optional). Mutually exclusive with contentEdits.")
    private String content;

    @Valid
    @Schema(description = "Incremental find/replace edits applied sequentially to the current content (optional). "
            + "Mutually exclusive with content.")
    private List<ContentEditDTO> contentEdits;
}
