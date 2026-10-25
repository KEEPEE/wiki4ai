package com.wiki4ai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Data Transfer Object for one entry of the work item timeline (WIKI4AI-146).
 * Response payload of {@code GET /api/v1/work-items/{id}/events}.
 * <p>
 * Manual conversion — this project does not use MapStruct (SPEC rule 13).
 * <p>
 * The payload is the event's own JSON detail, passed through as the string it is
 * stored as. It is never parsed here: the service that wrote it chose its shape,
 * and re-serialising it would either lose fields the writer added or invent a
 * schema the audit trail does not have. It never contains a claim token.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Jeden zaznam append-only timelineu work itemu")
public class WorkItemEventDTO {

    private Long id;

    @Schema(description = "Work item, ktorého sa týka")
    private Long workItemId;

    @Schema(description = "Actor, ktorému sa zmena pripisuje; null pre automatické zmeny bez volajúceho")
    private Long actorId;

    @Schema(description = "created | status_changed | claimed | claim_released | lease_extended"
            + " | lease_expired | stale_claim_cleared | deleted")
    private String event;

    @Schema(description = "Stav pred zmenou; null pre 'created'")
    private String fromStatus;

    @Schema(description = "Stav po zmene; null pre 'deleted'")
    private String toStatus;

    @Schema(description = "Voliteľné JSON detaily zmeny")
    private String payload;

    @Schema(description = "Kedy sa zmena stala")
    private LocalDateTime createdAt;
}
