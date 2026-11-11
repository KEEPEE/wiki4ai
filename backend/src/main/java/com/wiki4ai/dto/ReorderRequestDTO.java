package com.wiki4ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body of the reorder endpoints (WIKI4AI-184):
 * {@code POST /projects/{slug}/reorder} and
 * {@code POST /projects/{projectSlug}/documents/{docSlug}/reorder}.
 *
 * <p>Exactly one of the three fields must be present. The three spellings exist
 * because the three things a user does with a list are different: drag an item above
 * another one ({@code beforeSlug}), drop it below another one ({@code afterSlug}), or
 * send it to an absolute index ({@code position}). A drag-and-drop frontend knows its
 * neighbours and sends a slug; a "move to top" button knows only an index.</p>
 *
 * <p>The body is deliberately not a {@code {"sortOrder": N}} patch: a raw index would
 * let two items end up with the same number and leave the list in an order nobody
 * asked for. The service resolves the requested slot against the current order and
 * renumbers the whole list densely, so the stored state is always a permutation of
 * 0..n-1.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReorderRequestDTO {

    @Schema(description = "Presun na pozíciu tesne pred projekt/dokument s týmto slugom.")
    private String beforeSlug;

    @Schema(description = "Presun na pozíciu tesne po projekte/dokumente s týmto slugom.")
    private String afterSlug;

    @Schema(description = "Cieľová pozícia v zozname (0-indexovaná; hodnota mimo rozsahu sa zovrie na hranicu.")
    private Integer position;
}
