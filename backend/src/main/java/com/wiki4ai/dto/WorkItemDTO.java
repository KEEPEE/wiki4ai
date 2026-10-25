package com.wiki4ai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Data Transfer Object for a WorkItem (WIKI4AI-140). Response payload of
 * /api/v1/work-items.
 * <p>
 * Manual conversion — this project does not use MapStruct (SPEC rule 13).
 * <p>
 * The attribution and claim fields are OUTPUT ONLY. They are absent from
 * {@link WorkItemRequestDTO}, so no request body can name an actor, a lease or a
 * claim token; Jackson drops unknown properties and the values always come from
 * the verified credential and from the claim operation.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkItemDTO {

    private Long id;

    private Long projectId;

    private String title;

    private String body;

    /** 'backlog' | 'ready' | 'claimed' | 'running' | 'review' | 'done' | 'blocked' | 'cancelled'. */
    private String status;

    private String priority;

    /** Labels as a list; the DB stores them as TEXT and the service encodes them. */
    private List<String> labels;

    private Long parentId;

    private String externalRef;

    /** Actor currently holding the item, or null. */
    private Long assigneeActorId;

    /**
     * Claim token of the current lease.
     * <p>
     * <b>Never filled by the application since WIKI4AI-177.</b> This DTO answers every
     * read path of the work-items API, and a read path is answered for whoever may read
     * the project — so filling it put a 64-character lease proof into
     * {@code GET /work-items/{id}} and into one list response covering every claimed
     * item, including for a legacy web JWT. The token is returned by
     * {@link WorkItemClaimDTO} alone, from the two endpoints that issue or renew a
     * lease.
     * <p>
     * The field itself stays: callers that assemble a {@code WorkItemDTO} by hand are
     * unaffected by removing it, and {@code NON_NULL} means an unfilled field is simply
     * absent from the JSON rather than a null a client has to tolerate.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String claimToken;

    /** When the current lease expires. Omitted when there is no claim. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private LocalDateTime leaseExpiresAt;

    /** User that created the item, or null. */
    private Long createdByUserId;

    /** Actor that created the item (V19 attribution pattern). Omitted when null. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long createdByActorId;

    /** Actor that performed the last write. Omitted when null. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long updatedByActorId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /** Optimistic locking version; clients may rely on it to detect a concurrent write. */
    private Integer version;
}
