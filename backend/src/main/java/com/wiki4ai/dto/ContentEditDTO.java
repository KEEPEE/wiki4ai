package com.wiki4ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single find/replace edit applied to the current document content.
 * Edits are applied sequentially in list order — each edit sees the result of
 * the previous one (supports chained changes).
 *
 * Semantics:
 * - find is matched EXACTLY (case-sensitive, including whitespace).
 * - replace may be an empty string ("") — that DELETES the matched text.
 * - replaceAll defaults to false (null is treated as false). When false and
 *   find occurs more than once the update fails with 400 (no random choice);
 *   when true every occurrence is replaced.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "A single find/replace edit applied to the current document content. "
        + "Edits are applied sequentially in list order — each edit sees the result of the previous one.")
public class ContentEditDTO {

    @NotBlank(message = "find must not be blank")
    @Schema(description = "Exact text to find (case-sensitive, including whitespace).",
            example = "Old section heading")
    private String find;

    @NotNull(message = "replace must not be null; use an empty string to delete the matched text")
    @Schema(description = "Replacement text. An empty string \"\" deletes the matched text.",
            example = "New section heading")
    private String replace;

    @Schema(description = "When true, replace ALL occurrences of 'find'. "
            + "When false (default) and 'find' occurs more than once, the request fails with 400.",
            example = "false")
    private Boolean replaceAll;
}
