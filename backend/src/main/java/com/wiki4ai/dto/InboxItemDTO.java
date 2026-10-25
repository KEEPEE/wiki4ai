package com.wiki4ai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Data Transfer Object for one entry of an actor's inbox (WIKI4AI-152).
 * Response element of {@code GET /api/v1/actors/{id}/inbox}.
 * <p>
 * Manual conversion — this project does not use MapStruct (SPEC rule 13).
 *
 * <h2>One shape for four different kinds of thing</h2>
 * The inbox merges four streams into one list, and the four streams are rows of three
 * different tables. This DTO is deliberately the union of what those rows have in common
 * rather than four DTOs with a type discriminator, because the client of an inbox reads
 * one list: {@code stream} says which stream an entry came from, {@code kind} says what
 * the row is inside that stream, and the rest is the content.
 *
 * <h2>{@code id} is stream-local, and that is not a bug</h2>
 * {@code id} is the primary key of the row in <b>its own table</b>: a {@code work_items}
 * id for {@code item}, a {@code work_item_comments} id for {@code comment} and
 * {@code mention}, a {@code work_item_events} id for {@code released}. Those are three
 * independent sequences. {@code work_items.id = 5} and {@code work_item_comments.id = 5}
 * are not the same moment and not the same row — they are unrelated facts that happen to
 * share a number. The id is therefore a key <i>within</i> its stream (it is what the
 * cursor of that stream advances over), never a key across streams. See
 * {@link ActorInboxService} for the ordering rule this implies.
 *
 * <h2>{@code createdAt} is a {@link LocalDateTime}</h2>
 * Every timestamp column of this schema is {@code TIMESTAMP} without a time zone and
 * every entity maps it to {@code LocalDateTime} (SPEC rule 11b). {@code Instant} would
 * map to {@code TIMESTAMP WITH TIME ZONE}, the postgres profile runs with
 * {@code ddl-auto: validate}, and the backend on dev .219 would not start.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Jedna polozka inboxu aktora (WIKI4AI-152)")
public class InboxItemDTO {

    /** Stream: a work item currently assigned to this actor. */
    public static final String STREAM_ITEM = "item";

    /** Stream: a comment on a work item this actor created or holds. */
    public static final String STREAM_COMMENT = "comment";

    /** Stream: a {@code handoff} comment addressed to this actor. */
    public static final String STREAM_MENTION = "mention";

    /** Stream: a claim this actor held was taken back from it. */
    public static final String STREAM_RELEASED = "released";

    @Schema(description = "item | comment | mention | released", example = "comment")
    private String stream;

    @Schema(description = "id riadku v tabulke daneho prudu (work_items / work_item_comments"
            + " / work_item_events). NIE je to id zdielene napriec prudmi.", example = "42")
    private Long id;

    @Schema(description = "Work item, ktoreho sa polozka tyka")
    private Long workItemId;

    @Schema(description = "Vyznam 'kind' podla prudu: status ulohy (item), kind komentara"
            + " (comment, mention), nazov eventu (released)")
    private String kind;

    @Schema(description = "Titulok work itemu, ktoreho sa polozka tyka")
    private String title;

    @Schema(description = "Text polozky: telo komentara, telo work itemu; null pri 'released'")
    private String body;

    @Schema(description = "Structured payload of the comment or of the event, as JSON object."
            + " For 'mention' this is where 'to_handle' lives (WIKI4AI-149).")
    private Map<String, Object> payload;

    @Schema(description = "Kedy to vzniklo. TIMESTAMP bez casovej zony (SPEC rule 11b)")
    private LocalDateTime createdAt;
}
