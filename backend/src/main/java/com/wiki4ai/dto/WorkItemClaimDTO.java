package com.wiki4ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Response payload of POST /api/v1/work-items/{id}/claim (WIKI4AI-141).
 * <p>
 * The claimant needs exactly three things back, and this is the only place the
 * claim token is ever returned: who holds the item ({@code assigneeActorId}),
 * the proof of the claim ({@code claimToken}) and how long it is valid for
 * ({@code leaseExpiresAt}). {@code status} and {@code version} are included so
 * the caller can see the state it just moved the row into without a second read.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Vysledok atomickeho claimu work itemu")
public class WorkItemClaimDTO {

    private Long id;

    /** Always 'claimed' — a claim that produced any other state did not happen. */
    private String status;

    private Long assigneeActorId;

    private String claimToken;

    private LocalDateTime leaseExpiresAt;

    private Integer version;

    private LocalDateTime updatedAt;
}
