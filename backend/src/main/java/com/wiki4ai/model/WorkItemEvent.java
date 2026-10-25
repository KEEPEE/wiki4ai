package com.wiki4ai.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreRemove;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * JPA Entity representing one entry of the append-only audit trail of a work item
 * (WIKI4AI-145, migration V21).
 *
 * <h2>Append-only, and what that means in this class</h2>
 * An event is a fact that happened. Facts are not edited after the fact, so this
 * entity is written once and never changed:
 * <ul>
 *   <li>it has <b>no setters</b> — the only way to build one is
 *   {@link WorkItemEventBuilder}, and every field is fixed at construction;</li>
 *   <li>{@link #refuseUpdate()} and {@link #refuseRemove()} are JPA lifecycle
 *   guards: Hibernate calls them before it writes an UPDATE or issues a DELETE for
 *   this entity, from <i>any</i> path — a repository, an EntityManager, a cascade —
 *   and they throw. An attempt to modify or remove an event is therefore not a
 *   convention somebody might forget; it is a failure.</li>
 * </ul>
 * The only removal that still happens is the database's own
 * {@code ON DELETE CASCADE} from {@code work_items} (SPEC's DDL), which is DDL-level
 * and never passes through JPA.
 *
 * <h2>No {@code @Version}</h2>
 * Optimistic locking protects a value that several writers compete over. A row of
 * an append-only table has no such value: it is written once by exactly one
 * transaction and never written again. A version column here would be a column that
 * can never legally change.
 *
 * <h2>Plain ids, not associations</h2>
 * {@code workItemId} and {@code actorId} are plain {@link Long} columns, exactly
 * like the attribution columns of {@link WorkItem} and {@link Document}: the real
 * constraint lives in the database ({@code ON DELETE CASCADE} / {@code ON DELETE
 * SET NULL}), and the entity deliberately does not model it as a JPA relationship.
 * That is also what keeps the audit out of Hibernate's cascade graph — no JPA path
 * can remove an event as a side effect of touching an item.
 *
 * <h2>Time</h2>
 * {@link LocalDateTime}, mapped to the {@code TIMESTAMP} (no time zone) column of
 * V21 — SPEC rule 11b. {@code java.time.Instant} would map to
 * {@code TIMESTAMP WITH TIME ZONE}, the postgres profile runs with
 * {@code ddl-auto: validate}, and the backend on dev .219 would not start.
 */
@Entity
@Table(name = "work_item_events")
@Getter
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class WorkItemEvent {

    /** The item was created. No predecessor state: {@code fromStatus} is null. */
    public static final String EVENT_CREATED = "created";

    /** A legal state transition performed through an update. */
    public static final String EVENT_STATUS_CHANGED = "status_changed";

    /** The atomic claim succeeded: the item moved from 'ready' to 'claimed'. */
    public static final String EVENT_CLAIMED = "claimed";

    /** A transition that dropped a live claim triple (assignee, token, lease). */
    public static final String EVENT_CLAIM_RELEASED = "claim_released";

    /** The holder renewed its lease. {@code fromStatus} and {@code toStatus} are the same. */
    public static final String EVENT_LEASE_EXTENDED = "lease_extended";

    /** The reaper released a lease whose TTL had passed. Automatic: no request caused it. */
    public static final String EVENT_LEASE_EXPIRED = "lease_expired";

    /** The heal sweep removed a claim triple that was sitting on a claim-free state. */
    public static final String EVENT_STALE_CLAIM_CLEARED = "stale_claim_cleared";

    /** The item was deleted. No successor state: {@code toStatus} is null. */
    public static final String EVENT_DELETED = "deleted";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The work item this event belongs to (V20). Plain id, not an association —
     * see the class comment. NOT NULL: an event that belongs to no item is not a
     * fact about anything.
     */
    @Column(name = "work_item_id", nullable = false)
    private Long workItemId;

    /**
     * The actor the event is attributed to (V15 registry), or null. Nullable by
     * design: the lease reaper runs on a timer and carries no credential, so for
     * its events there is no caller to name — what is named instead is the actor
     * whose lease or claim was lost. Never taken from a request body, a query
     * parameter or a header.
     */
    @Column(name = "actor_id")
    private Long actorId;

    /** One of the {@code EVENT_*} constants of this class. */
    @Column(name = "event", nullable = false, length = 32)
    private String event;

    /** State before, or null when the event has no predecessor ('created', backfilled rows). */
    @Column(name = "from_status", length = 16)
    private String fromStatus;

    /** State after, or null when the event has no successor ('deleted'). */
    @Column(name = "to_status", length = 16)
    private String toStatus;

    /**
     * Optional JSON detail. TEXT, like {@code work_items.body}. Carries facts
     * (which lease expired, which assignee was cleared) and never a claim token:
     * the token is the secret that authorises work on an item.
     */
    @Column(name = "payload", columnDefinition = "TEXT")
    private String payload;

    /** When it happened. {@code updatable = false} states the same rule the guards enforce. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * JPA lifecycle guard: an UPDATE of an event row is refused.
     * <p>
     * Throwing here is deliberate. A silent no-op would let a caller believe its
     * write succeeded; an exception names the operation that is not allowed, and
     * the transaction that attempted it is rolled back, so no half-written audit
     * survives.
     */
    @PreUpdate
    protected void refuseUpdate() {
        throw new IllegalStateException(
                "work_item_events is append-only: event " + id + " ('" + event
                        + "') cannot be updated");
    }

    /**
     * JPA lifecycle guard: a DELETE of an event row through JPA is refused.
     * <p>
     * The database's own {@code ON DELETE CASCADE} from {@code work_items} is not
     * affected — it is DDL-level and never reaches this callback.
     */
    @PreRemove
    protected void refuseRemove() {
        throw new IllegalStateException(
                "work_item_events is append-only: event " + id + " ('" + event
                        + "') cannot be deleted");
    }

    @PrePersist
    protected void onCreate() {
        // Set only when it was not supplied: the backfill of V21 and any future
        // importer need to state the moment the fact happened, not the moment the
        // row was inserted. Everything the application records leaves this null and
        // gets "now".
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        WorkItemEvent that = (WorkItemEvent) o;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return "WorkItemEvent{" +
                "id=" + id +
                ", workItemId=" + workItemId +
                ", actorId=" + actorId +
                ", event='" + event + '\'' +
                ", fromStatus='" + fromStatus + '\'' +
                ", toStatus='" + toStatus + '\'' +
                ", createdAt=" + createdAt +
                '}';
    }
}
