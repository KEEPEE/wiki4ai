package com.wiki4ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Response view of a {@code Reservation} (WIKI4AI-155..157).
 * <p>
 * {@code resourceKey} is carried alongside {@code resourceId} on purpose: the reservation
 * endpoints are addressed by key, and a client that read {@code /resources/{key}/reservations}
 * should not have to look the resource up again by id to know what it is looking at.
 * <p>
 * {@code releasedAt == null} means the holding is live. A released row is returned rather
 * than hidden, because "what did this resource hold, and when did it end" is the audit
 * question the ledger exists to answer.
 * <p>
 * All three time fields are {@link LocalDateTime} (TIMESTAMP without time zone in V25,
 * SPEC rule 11b).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReservationDTO {

    private Long id;

    private Long resourceId;

    /** The key the caller used to reach this reservation. */
    private String resourceKey;

    /** The actor that holds it, resolved from the verified credential. */
    private Long actorId;

    /** The work item it is held for, when the caller named one. */
    private Long workItemId;

    private Integer slots;

    private String reason;

    private LocalDateTime expiresAt;

    /** Null while the holding is live. Omitted from the JSON when null. */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private LocalDateTime releasedAt;

    private LocalDateTime createdAt;
}
