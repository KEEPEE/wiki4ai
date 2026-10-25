package com.wiki4ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request payload of POST /api/v1/work-items/{id}/lease (WIKI4AI-142).
 * <p>
 * It carries one optional field. The lease length is not a client choice: it is
 * the server's configured TTL ({@code wiki4ai.work-items.lease-minutes}), because
 * a TTL an agent can extend by asking for a bigger one is not a TTL.
 * <p>
 * {@code claimToken} is optional. The holder is proven primarily by identity — the
 * caller's actor, taken from the verified credential, must be the item's
 * {@code assigneeActorId}. When the token is sent it must match the token of the
 * lease, which adds a second factor for an actor that holds several credentials.
 * <p>
 * There is no {@code actorId} field and there never will be: attribution and
 * ownership come from the credential, never from the body (SPEC rule 7).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Predlzanie lease work itemu; claimToken je volitelny dokaz drzitela")
public class WorkItemLeaseRequestDTO {

    /** The claim token returned by the claim. Optional; when present it must match. */
    @Size(max = 64, message = "Claim token must be at most 64 characters")
    private String claimToken;
}
