package com.wiki4ai.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * JPA Entity representing a unit of work in the coordination layer
 * (WIKI4AI-140, migration V20).
 * <p>
 * A work item belongs to exactly one project and moves through a state machine:
 * {@code backlog -> ready -> claimed -> running -> review -> done}, with
 * {@code blocked} and {@code cancelled} as side states. The transition into
 * {@code claimed} is the one transition that must be atomic — two agents racing
 * for the same item may not both win — so it is the only state a plain update is
 * not allowed to set; it is reached through the conditional UPDATE of
 * {@code WorkItemRepository.claim} (WIKI4AI-141). The rules themselves live in
 * {@code WorkItemService}, like every other ownership rule of this project
 * (SPEC rule 7), not in SecurityConfig.
 * <p>
 * Time columns are {@link LocalDateTime}, matching the {@code TIMESTAMP}
 * (no time zone) columns of V20 and every other entity here — SPEC rule 11b.
 * {@code java.time.Instant} would map to {@code TIMESTAMP WITH TIME ZONE} and
 * the postgres profile ({@code ddl-auto: validate}) would refuse to start.
 */
@Entity
@Table(name = "work_items")
@Getter
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class WorkItem {

    /** State: not yet scheduled for work. */
    public static final String STATUS_BACKLOG = "backlog";

    /** State: scheduled and available to be claimed. The only claimable state. */
    public static final String STATUS_READY = "ready";

    /** State: claimed by an actor under a lease. Reachable only via the claim endpoint. */
    public static final String STATUS_CLAIMED = "claimed";

    /** State: an actor is working on it. */
    public static final String STATUS_RUNNING = "running";

    /** State: work is finished and waiting for review. */
    public static final String STATUS_REVIEW = "review";

    /** State: finished. Terminal. */
    public static final String STATUS_DONE = "done";

    /** State: something external prevents progress. */
    public static final String STATUS_BLOCKED = "blocked";

    /** State: dropped. Terminal. */
    public static final String STATUS_CANCELLED = "cancelled";

    /** Default state of a new work item (same default as the DB column). */
    public static final String DEFAULT_STATUS = STATUS_BACKLOG;

    /** Default priority (same default as the DB column). */
    public static final String DEFAULT_PRIORITY = "medium";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Owning project. Plain id rather than a JPA association, exactly like the
     * actor columns below: the DB carries the real foreign key
     * ({@code ON DELETE CASCADE}), and the service loads the project explicitly
     * whenever it has to enforce visibility — which is every time it is needed.
     */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "title", nullable = false)
    private String title;

    /** Free-form description. TEXT, like documents.content and actors.capabilities. */
    @Column(name = "body", columnDefinition = "TEXT")
    private String body;

    /**
     * 'backlog' | 'ready' | 'claimed' | 'running' | 'review' | 'done'
     * | 'blocked' | 'cancelled'. Validated and transition-checked in
     * WorkItemService.
     */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** Ordering value; default 'medium'. Validated in WorkItemService. */
    @Column(name = "priority", nullable = false, length = 16)
    private String priority;

    /**
     * Labels as raw TEXT. The entity deliberately stores a String, not a
     * {@code List}: the encoding is the service's business (SPEC: labels are a
     * TEXT column), and a converted collection here would have no column to live in.
     */
    @Column(name = "labels", columnDefinition = "TEXT")
    private String labels;

    /** Parent work item (self reference), or null. Validated in the service. */
    @Column(name = "parent_id")
    private Long parentId;

    /**
     * Globally unique reference to the same item in an external tracker
     * (DB UNIQUE constraint, V20). {@code unique = true} mirrors it in the
     * Hibernate-created test schema so the duplicate rule is testable on H2 too.
     */
    @Column(name = "external_ref", length = 128, unique = true)
    private String externalRef;

    /**
     * The actor currently holding the item (V15 registry), or null. Written by
     * the atomic claim. Plain id, not an association — the same reason as the
     * attribution columns of Document (V19): the reference must survive the
     * actor leaving the registry, and the value is never taken from client input.
     */
    @Column(name = "assignee_actor_id")
    private Long assigneeActorId;

    /**
     * Opaque token proving the claim. Generated by the service, returned to the
     * claimant once; it is what later stories use to prove "I am the one holding
     * this lease". Never accepted as input.
     */
    @Column(name = "claim_token", length = 64)
    private String claimToken;

    /**
     * When the claim expires. {@link LocalDateTime} — the column is TIMESTAMP
     * without a time zone (SPEC rule 11b), NOT TIMESTAMPTZ.
     */
    @Column(name = "lease_expires_at")
    private LocalDateTime leaseExpiresAt;

    /** Authenticated user that created the item, or null (unattributable caller). */
    @Column(name = "created_by_user_id")
    private Long createdByUserId;

    /**
     * The actor (V15 registry) that created this work item (WIKI4AI-140). NULL
     * when the caller carries no actor — a legacy JWT with no human actor of its
     * own. Same rule as {@code Document.createdByActorId} (V19): resolved from
     * the authenticated request in the service layer, never from a body field,
     * a query parameter or a header.
     */
    @Column(name = "created_by_actor_id")
    private Long createdByActorId;

    /**
     * The actor (V15 registry) that performed the last write on this work item,
     * including the claim itself. Overwritten on every update; NULL when the
     * updater carried no actor. Same rules as {@code createdByActorId}.
     */
    @Column(name = "updated_by_actor_id")
    private Long updatedByActorId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * Optimistic locking (AC of WIKI4AI-140). Hibernate appends
     * {@code WHERE version = ?} to every entity UPDATE and increments it on
     * success, so a concurrent writer holding a stale state fails with
     * OptimisticLockException (mapped to HTTP 409 by GlobalExceptionHandler)
     * instead of silently overwriting the other write. New rows start at 0
     * (Hibernate initialises a null numeric @Version; the DB column defaults to 0).
     * <p>
     * The atomic claim is a bulk JPQL UPDATE, which bypasses this check by
     * design — its safety comes from the {@code WHERE ... status = 'ready'}
     * condition instead, and it increments the version itself so readers can
     * still tell that the row changed.
     */
    @Version
    @Column(name = "version")
    private Integer version;

    // ── Setters (service layer only; the entity has no blanket @Setter) ───────

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public void setBody(String body) {
        this.body = body;
    }

    /**
     * Set the state. Callers must go through WorkItemService, which validates
     * the value and the transition; this setter performs no validation of its own.
     */
    public void setStatus(String status) {
        this.status = status;
    }

    public void setPriority(String priority) {
        this.priority = priority;
    }

    public void setLabels(String labels) {
        this.labels = labels;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId;
    }

    public void setExternalRef(String externalRef) {
        this.externalRef = externalRef;
    }

    /** Set the claim assignee. Written only by the claim flow. */
    public void setAssigneeActorId(Long assigneeActorId) {
        this.assigneeActorId = assigneeActorId;
    }

    /** Set the claim token. Written only by the claim flow, never read from input. */
    public void setClaimToken(String claimToken) {
        this.claimToken = claimToken;
    }

    /** Set the lease expiry. Written only by the claim flow. */
    public void setLeaseExpiresAt(LocalDateTime leaseExpiresAt) {
        this.leaseExpiresAt = leaseExpiresAt;
    }

    /** Set the creating user. Service layer only, from the authenticated caller. */
    public void setCreatedByUserId(Long createdByUserId) {
        this.createdByUserId = createdByUserId;
    }

    /**
     * Set the creating actor id (WIKI4AI-140). Called only by the service layer,
     * only from the authenticated request context — never from a request body,
     * query parameter or header. Null means "this caller carries no actor".
     */
    public void setCreatedByActorId(Long createdByActorId) {
        this.createdByActorId = createdByActorId;
    }

    /**
     * Set the last-updating actor id. Same rule as {@link #setCreatedByActorId}:
     * service layer only, authenticated context only.
     */
    public void setUpdatedByActorId(Long updatedByActorId) {
        this.updatedByActorId = updatedByActorId;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        // Defensive defaults: the DB columns have these defaults, keep the Java
        // side consistent so a freshly built entity is already valid.
        if (status == null || status.isBlank()) {
            this.status = DEFAULT_STATUS;
        }
        if (priority == null || priority.isBlank()) {
            this.priority = DEFAULT_PRIORITY;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        WorkItem workItem = (WorkItem) o;
        return id != null && Objects.equals(id, workItem.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return "WorkItem{" +
                "id=" + id +
                ", projectId=" + projectId +
                ", title='" + title + '\'' +
                ", status='" + status + '\'' +
                ", priority='" + priority + '\'' +
                ", assigneeActorId=" + assigneeActorId +
                ", leaseExpiresAt=" + leaseExpiresAt +
                ", version=" + version +
                '}';
    }
}
