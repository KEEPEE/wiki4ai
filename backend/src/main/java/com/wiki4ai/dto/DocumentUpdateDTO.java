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

    /**
     * WIKI4AI-72: optimistic locking. When set, the update is only applied if the
     * document's current version equals this value; otherwise the request fails
     * with 409 Conflict (DocumentVersionConflictException) before any change is
     * made. Optional for backward compatibility — old clients that never send it
     * keep working (concurrent writers are still protected by the JPA @Version
     * safety net, which surfaces as 409 at commit time).
     */
    @Schema(description = "Optional expected document version for optimistic locking. "
            + "When provided and different from the current version, the update is rejected "
            + "with 409 Conflict (the document was modified by another writer). "
            + "Omit to keep the legacy behavior.")
    private Long expectedVersion;
}
