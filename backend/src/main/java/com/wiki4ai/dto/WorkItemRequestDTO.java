package com.wiki4ai.dto;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Request payload of /api/v1/work-items — POST (create) and PUT (update)
 * (WIKI4AI-140).
 * <p>
 * One class for both verbs on purpose: PUT has PATCH semantics (a null field
 * means "do not change it"), so a separate update DTO would only duplicate the
 * same fields. Required-ness is decided in {@code WorkItemService}, not by bean
 * validation, because what is required depends on the verb.
 * <p>
 * <b>What is deliberately NOT here.</b> There is no {@code createdByActorId},
 * {@code updatedByActorId}, {@code assigneeActorId}, {@code claimToken},
 * {@code leaseExpiresAt} or {@code version} field. Attribution comes from the
 * verified credential ({@code RequestActorResolver}), the claim triple is written
 * by the claim operation, and the version is managed by JPA. A client cannot
 * name an actor, forge a lease or pick a version — see
 * {@code WorkItemServiceTest}, which asserts this shape.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkItemRequestDTO {

    /** Target project. Required on POST; ignored on PUT (an item does not move projects). */
    private Long projectId;

    @Size(max = 255, message = "Work item title must be at most 255 characters")
    private String title;

    private String body;

    /**
     * Requested state. On POST it may be any state except 'claimed'
     * (default 'backlog'). On PUT it must be a legal transition from the current
     * state, and 'claimed' is never accepted — it is reachable only through
     * POST /api/v1/work-items/{id}/claim.
     */
    @Size(max = 16, message = "Status must be at most 16 characters")
    private String status;

    @Size(max = 16, message = "Priority must be at most 16 characters")
    private String priority;

    /**
     * Labels. On PUT, null means "keep the current labels" and an empty list
     * means "remove all labels".
     */
    private List<String> labels;

    private Long parentId;

    @Size(max = 128, message = "External reference must be at most 128 characters")
    private String externalRef;
}
