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
 * JPA Entity representing one comment on a work item (WIKI4AI-148, migration V22).
 *
 * <h2>Immutable, and what that means in this class</h2>
 * A comment is a statement somebody made at a moment. Editing it afterwards would
 * rewrite the record of what was said, so a comment is written once and never
 * changed. This is enforced, not declared:
 * <ul>
 *   <li>the class has <b>no setters</b> — the only way to build one is
 *   {@link WorkItemCommentBuilder}, and every value is fixed at construction;</li>
 *   <li>there is <b>no {@code updated_at} column</b>: an immutable row has no later
 *   moment to record, and a column that can never legally change is a column that
 *   should not exist;</li>
 *   <li>{@link #refuseUpdate()} and {@link #refuseRemove()} are JPA lifecycle guards.
 *   Hibernate calls them before it writes an UPDATE or issues a DELETE for this
 *   entity, from <i>any</i> path — a repository, an EntityManager, a merge, a
 *   cascade — and they throw. Modifying or removing a comment is therefore not a
 *   convention somebody might forget; it is a failure that rolls back its
 *   transaction.</li>
 * </ul>
 * The same refusals are installed a second time in
 * {@link com.wiki4ai.repository.WorkItemCommentRepository}, which catches the bulk
 * statements ({@code deleteAllInBatch} and friends) that send a {@code DELETE FROM}
 * without loading an entity and therefore never reach a lifecycle callback. The
 * trigger V22 installs in the schema catches an UPDATE issued by anything that
 * reaches the database. The API has no PUT, PATCH or DELETE endpoint at all. Four
 * layers, none of them redundant: each catches a path the others cannot reach.
 * <p>
 * The only removal that still happens is the database's own {@code ON DELETE
 * CASCADE} from {@code work_items} (SPEC's DDL), which is DDL-level and never
 * passes through JPA.
 *
 * <h2>No {@code @Version}</h2>
 * Optimistic locking protects a value several writers compete over. An immutable row
 * has no such value: it is written once by exactly one transaction and never written
 * again.
 *
 * <h2>Plain ids, not associations</h2>
 * {@code workItemId} and {@code authorActorId} are plain {@link Long} columns, like
 * every other reference in this layer: the real constraint lives in the database
 * ({@code ON DELETE CASCADE} / {@code ON DELETE SET NULL}), and not modelling it as
 * a JPA relationship is also what keeps comments out of Hibernate's cascade graph —
 * no JPA path can remove a comment as a side effect of touching an item.
 *
 * <h2>Time</h2>
 * {@link LocalDateTime}, mapped to the {@code TIMESTAMP} (no time zone) column of
 * V22 — SPEC rule 11b. {@code java.time.Instant} would map to
 * {@code TIMESTAMP WITH TIME ZONE}, the postgres profile runs with
 * {@code ddl-auto: validate}, and the backend on dev .219 would not start.
 */
@Entity
@Table(name = "work_item_comments")
@Getter
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class WorkItemComment {

    /** A comment stating where the work stands now. No required payload fields. */
    public static final String KIND_STATUS = "status";

    /** A comment saying something is in the way. */
    public static final String KIND_BLOCKER = "blocker";

    /** A comment reporting what was produced. */
    public static final String KIND_RESULT = "result";

    /** A comment passing the work to another actor. */
    public static final String KIND_HANDOFF = "handoff";

    /** A plain note. No required payload fields. */
    public static final String KIND_NOTE = "note";

    /** A comment recording a decision and its reason. */
    public static final String KIND_DECISION = "decision";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The work item this comment belongs to (V20). Plain id, not an association —
     * see the class comment. NOT NULL: a comment about no item is not a comment.
     */
    @Column(name = "work_item_id", nullable = false)
    private Long workItemId;

    /**
     * The actor (V15 registry) that wrote the comment, or null.
     * <p>
     * Nullable in the schema so a comment outlives the actor leaving the registry
     * ({@code ON DELETE SET NULL}). Not nullable through the API:
     * {@code WorkItemCommentService} refuses a POST whose credential names no actor.
     * The value is written exclusively from {@code RequestActorResolver} — never
     * from a body field, a query parameter or a header, because "who wrote this" is
     * exactly the thing a caller would most like to fake.
     */
    @Column(name = "author_actor_id")
    private Long authorActorId;

    /**
     * One of the {@code KIND_*} constants of this class. Validated in the service
     * layer (SPEC rule 7); V22 adds no CHECK constraint, exactly as V21 added none
     * for {@code work_item_events.event}.
     */
    @Column(name = "kind", nullable = false, length = 16)
    private String kind;

    /**
     * The comment text. TEXT, like {@code work_items.body}. NOT NULL in the schema
     * and required by the service: a comment with no text is not a comment.
     */
    @Column(name = "body", nullable = false, columnDefinition = "TEXT")
    private String body;

    /**
     * The kind's structured detail, stored as JSON TEXT — the same arrangement
     * {@code work_items.labels} uses for its list (TEXT in the database,
     * {@code List<String>} in the DTO; here {@code Map<String,Object>}). The
     * contract of each kind is enforced by {@code WorkItemCommentContracts} before
     * the row is written, never by the database and never by a prompt to the author.
     * Null only for the kinds that require no payload at all.
     */
    @Column(name = "payload", columnDefinition = "TEXT")
    private String payload;

    /**
     * When it was written. {@code updatable = false} states the same rule the
     * lifecycle guards enforce. There is no {@code updated_at}: see the class
     * comment.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * JPA lifecycle guard: an UPDATE of a comment row is refused.
     * <p>
     * Throwing is deliberate. A silent no-op would let a caller believe its write
     * succeeded; an exception names the operation that is not allowed, and the
     * transaction that attempted it is rolled back, so no half-edited comment
     * survives.
     */
    @PreUpdate
    protected void refuseUpdate() {
        throw new IllegalStateException(
                "work_item_comments is immutable: comment " + id + " ('" + kind
                        + "') cannot be updated");
    }

    /**
     * JPA lifecycle guard: a DELETE of a comment row through JPA is refused.
     * <p>
     * The database's own {@code ON DELETE CASCADE} from {@code work_items} is not
     * affected — it is DDL-level and never reaches this callback.
     */
    @PreRemove
    protected void refuseRemove() {
        throw new IllegalStateException(
                "work_item_comments is immutable: comment " + id + " ('" + kind
                        + "') cannot be deleted");
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        WorkItemComment that = (WorkItemComment) o;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return "WorkItemComment{" +
                "id=" + id +
                ", workItemId=" + workItemId +
                ", authorActorId=" + authorActorId +
                ", kind='" + kind + '\'' +
                ", body='" + body + '\'' +
                ", createdAt=" + createdAt +
                '}';
    }
}
