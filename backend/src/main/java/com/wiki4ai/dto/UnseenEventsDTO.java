package com.wiki4ai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Data Transfer Object of {@code GET /api/v1/actors/{id}/inbox/unseen} and the answer of
 * {@code POST /api/v1/actors/{id}/inbox/mark-seen} (WIKI4AI-153).
 * <p>
 * Manual conversion — this project does not use MapStruct (SPEC rule 13).
 *
 * <h2>What "unseen" is measured against</h2>
 * One column: {@code actors.last_seen_event_id} (V23). It is a position in the
 * {@code work_item_events} id sequence, and an event is unseen when its id is greater
 * than it. A null marker means "this actor has never read its inbox" — it is NOT 0, and
 * the difference is visible here: with a null marker every event of that actor is unseen,
 * which is the honest answer, whereas 0 would be a marker somebody had to invent.
 *
 * <h2>{@code count} is exact, {@code eventIds} is capped</h2>
 * The count is a COUNT over the actor's event stream. The id list is capped at
 * {@code ActorInboxService.MAX_UNSEEN_EVENT_IDS} so one response cannot grow without
 * bound; when the two disagree, {@code count} is the truth and the ids are the oldest
 * ones. The cap is stated here rather than hidden because a client that gets 200 ids for
 * a count of 900 has to know it is looking at a window.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Co pribudlo do inboxu aktora (WIKI4AI-153)")
public class UnseenEventsDTO {

    @Schema(description = "Pocet eventov, ktore actor este nevidel (countBy na work_item_events)")
    private long count;

    @Schema(description = "Id unseen eventov, najstarsie prve. Capped: ak je count vacsi, "
            + "toto je iba najstarsia cast.")
    private List<Long> eventIds;

    @Schema(description = "Aktualny marker actors.last_seen_event_id. Null znamena 'tentho "
            + "actor este nikdy necital inbox' - nie 0.")
    private Long lastSeenEventId;

    @Schema(description = "Najvyssi id v eventovom prude tohto aktora. Null, ak actor ziadne "
            + "eventy nema. Je to strop, na ktory server ostrihne eventId poslany klientom.")
    private Long newestEventId;
}
