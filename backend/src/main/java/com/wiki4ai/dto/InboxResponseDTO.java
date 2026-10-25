package com.wiki4ai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Data Transfer Object of {@code GET /api/v1/actors/{id}/inbox} (WIKI4AI-152).
 *
 * <h2>Why there is one cursor and not four</h2>
 * The inbox reads four streams, and each stream is paginated on its own keyset
 * ({@code id > position}). A client therefore has to send four positions back. They are
 * packed into one opaque string — {@code nextCursor} — so the client has exactly one
 * value to remember and return, and cannot accidentally advance one stream while
 * rewinding another. The encoding and its reasoning are in {@link ActorInboxService}.
 *
 * <h2>{@code items} is chronological, {@code nextCursor} is per-stream</h2>
 * Those two orderings are different on purpose: what a reader wants to see is the order
 * things happened, what a poller needs is a position in each stream. Merging the streams
 * by {@code id} would order a work item against a comment by two numbers that count
 * unrelated things.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Inbox aktora: styri prudy, jeden kurzor (WIKI4AI-152)")
public class InboxResponseDTO {

    @Schema(description = "Polozky, chronologicky podla created_at, s id ako tie-break")
    private List<InboxItemDTO> items;

    @Schema(description = "Nepriehladny kurzor pre dalsie volanie. Vrat ho presne tak, ako"
            + " prišiel. Null iba v pripade, ze inbox sa nedá kurzorovat (nestane sa).")
    private String nextCursor;

    @Schema(description = "Limit, ktory sa skutocne pouzil (default 50, strop 50)", example = "50")
    private int limit;

    @Schema(description = "Pocet poloziek v tejto odpovedi (sucet cez styri prudy)")
    private int totalElements;
}
