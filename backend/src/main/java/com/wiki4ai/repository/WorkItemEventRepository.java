package com.wiki4ai.repository;

import com.wiki4ai.model.WorkItemEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Read interface for the append-only audit trail of a work item (WIKI4AI-145,
 * WIKI4AI-146, WIKI4AI-147).
 *
 * <h2>Append-only is enforced here, not just written down</h2>
 * The table is a fact log: rows are inserted and never modified or removed. A
 * plain {@code JpaRepository} would hand every caller {@code delete},
 * {@code deleteById}, {@code deleteAll}, {@code saveAndFlush} — methods whose very
 * names describe operations this table does not allow. They are therefore
 * <b>overridden here with implementations that throw</b> — <b>all ten</b> mutating
 * methods {@code JpaRepository} puts in front of a caller, five from
 * {@code CrudRepository} ({@code delete}, {@code deleteById}, {@code deleteAllById},
 * {@code deleteAll(Iterable)}, {@code deleteAll()}) and five from {@code JpaRepository}
 * ({@code saveAndFlush}, {@code deleteAllInBatch()}, {@code deleteAllInBatch(Iterable)},
 * {@code deleteInBatch(Iterable)}, {@code deleteAllByIdInBatch(Iterable)}). The count is
 * not a claim made here: {@code AppendOnlyRepositoryGuardTest} (WIKI4AI-172) derives the
 * list reflectively from the interface and fails if this file is missing one. The effect
 * is not documentation:
 * <ul>
 *   <li>the call compiles, resolves to this interface and fails at runtime with a
 *   message naming the rule it broke;</li>
 *   <li>it fails the same way from any other bean, so the rule cannot be routed
 *   around by injecting this repository somewhere else;</li>
 *   <li>the bulk variants are refused too: {@code deleteAllInBatch} and
 *   {@code deleteAllByIdInBatch} send a {@code DELETE FROM} that loads no entity, so
 *   no lifecycle callback runs for them, and they are the one way to erase the whole
 *   trail in a single call;</li>
 *   <li>and behind it, {@link WorkItemEvent}'s {@code @PreUpdate} /
 *   {@code @PreRemove} refuse the same write at the JPA provider level, which
 *   catches the paths that never touch this interface (an EntityManager, a
 *   different repository, a cascade).</li>
 * </ul>
 * Four layers guard the same rule, and none of them is redundant, because each
 * catches a path the others cannot reach. The schema trigger V21 installs catches an
 * UPDATE issued by anything that reaches the database, including hand-written SQL —
 * but it is a {@code BEFORE UPDATE} trigger, because a {@code BEFORE DELETE} trigger
 * would break the {@code ON DELETE CASCADE} SPEC's own DDL declares, so removals are
 * refused above the database rather than inside it. The entity's lifecycle callbacks
 * catch a managed entity changed through a field, a listener or a merge. The
 * repository refusals catch the API an application developer would actually reach
 * for, and the batch refusals catch the statements that skip the entity lifecycle
 * altogether.
 * {@code save} is the one write that stays, and {@code WorkItemEventService.record}
 * is the only code in the application that calls it.
 *
 * <p>
 * What is left to the database is the {@code ON DELETE CASCADE} from
 * {@code work_items} (SPEC's DDL): deleting an item takes its timeline with it.
 * That is DDL-level and does not go through JPA.
 */
@Repository
public interface WorkItemEventRepository extends JpaRepository<WorkItemEvent, Long> {

    // ── Reads ─────────────────────────────────────────────────────────────────

    /**
     * Full timeline of one work item, oldest first.
     * <p>
     * {@code id} is the tie-breaker and it is not decoration: {@code created_at} is
     * a TIMESTAMP(6), so two events written in the same microsecond — a claim and
     * its audit row, or a sweep touching one item twice — can share a timestamp.
     * Ordering by the timestamp alone would then leave their order to the database,
     * and a timeline whose order is not fully determined is a bug, not a detail.
     * Ids are assigned in insertion order, so the pair is deterministic.
     */
    List<WorkItemEvent> findByWorkItemIdOrderByCreatedAtAscIdAsc(Long workItemId);

    /** The same timeline, paginated (AC of WIKI4AI-146). */
    Page<WorkItemEvent> findByWorkItemIdOrderByCreatedAtAscIdAsc(Long workItemId, Pageable pageable);

    /** Timeline of one item inside a time window, oldest first. */
    List<WorkItemEvent> findByWorkItemIdAndCreatedAtBetweenOrderByCreatedAtAscIdAsc(
            Long workItemId, LocalDateTime from, LocalDateTime to);

    /**
     * Every event in a time window, grouped by item and oldest first — the input of
     * the metrics of WIKI4AI-147.
     * <p>
     * Grouping by {@code workItemId} in the query is what makes the aggregation
     * possible at all: a metric like "time spent in a state" is the distance between
     * two consecutive events <i>of the same item</i>, and the aggregation walks them
     * in order.
     */
    List<WorkItemEvent> findByCreatedAtBetweenOrderByWorkItemIdAscCreatedAtAscIdAsc(
            LocalDateTime from, LocalDateTime to);

    /**
     * Every event in a time window whose work item is one of {@code workItemIds},
     * grouped by item and oldest first — the input of a project-narrowed metrics window
     * (WIKI4AI-173).
     * <p>
     * Derived, and that is a constraint, not a preference. Two guard tests
     * ({@code ActorInboxIntegrationTest.Proofs} and {@code ActorInboxCursorIntegrationTest.NoOffset})
     * assert that this repository and {@link WorkItemCommentRepository} declare no
     * {@code @Query} at all, because a hand-written query on these two tables is exactly
     * where a {@code LIKE} over the comment {@code payload} TEXT would appear — the rule
     * WIKI4AI-151 proved and WIKI4AI-154 made permanent. So the project membership is
     * not expressed here as a subquery over {@code work_items}: the caller resolves
     * "which items belong to this project" and passes the ids as <b>values</b>. The
     * predicate stays a whole-value {@code IN} on {@code work_item_id}, the same kind of
     * match {@link #findByWorkItemIdInAndIdGreaterThanOrderByIdAsc} already uses, and the
     * payload text is still never pattern-matched.
     * <p>
     * The ordering is the ordering of the unfiltered read above, for the same reason: the
     * aggregation walks one item's events at a time, and {@code id} breaks the ties a
     * TIMESTAMP(6) cannot.
     */
    List<WorkItemEvent> findByWorkItemIdInAndCreatedAtBetweenOrderByWorkItemIdAscCreatedAtAscIdAsc(
            Collection<Long> workItemIds, LocalDateTime from, LocalDateTime to);

    /**
     * Keyset page of the events attributed to one actor, oldest first (WIKI4AI-152,
     * WIKI4AI-153).
     * <p>
     * {@code actor_id = ? AND id > ? ORDER BY id} is the shape the index
     * {@code idx_wie_actor_id (actor_id, id)} of V24 exists for. The primary key on
     * {@code id} alone would make PostgreSQL walk the whole id index and filter the actor
     * column row by row.
     */
    List<WorkItemEvent> findByActorIdAndIdGreaterThanOrderByIdAsc(Long actorId, Long id, Pageable pageable);

    /**
     * Keyset page of the events of one actor restricted to a set of event names — the
     * "items that slipped away from me" stream of the inbox (WIKI4AI-152).
     * <p>
     * The event names are passed in, not hard-coded here: which names belong to that
     * stream is a rule about the vocabulary of {@link WorkItemEvent}, and vocabulary rules
     * live in the service (SPEC rule 7). This interface only states how the rows are found.
     */
    List<WorkItemEvent> findByActorIdAndEventInAndIdGreaterThanOrderByIdAsc(
            Long actorId, Collection<String> events, Long id, Pageable pageable);

    /**
     * Keyset page of the events of a set of work items, oldest first (WIKI4AI-153).
     * <p>
     * Served by {@code idx_wie_item_id (work_item_id, id)} of V24.
     */
    List<WorkItemEvent> findByWorkItemIdInAndIdGreaterThanOrderByIdAsc(
            Collection<Long> workItemIds, Long id, Pageable pageable);

    /** The newest event of one actor, or empty when the actor has none. */
    Optional<WorkItemEvent> findFirstByActorIdOrderByIdDesc(Long actorId);

    /** The newest event of a set of work items, or empty when they have none. */
    Optional<WorkItemEvent> findFirstByWorkItemIdInOrderByIdDesc(Collection<Long> workItemIds);

    // ── The operations this table does not allow ──────────────────────────────

    /** Not supported: {@code work_item_events} is append-only (WIKI4AI-145). */
    @Override
    default void delete(WorkItemEvent entity) {
        throw appendOnly("DELETE");
    }

    /** Not supported: {@code work_item_events} is append-only (WIKI4AI-145). */
    @Override
    default void deleteById(Long id) {
        throw appendOnly("DELETE BY ID");
    }

    /** Not supported: {@code work_item_events} is append-only (WIKI4AI-145). */
    @Override
    default void deleteAllById(Iterable<? extends Long> ids) {
        throw appendOnly("DELETE ALL BY ID");
    }

    /** Not supported: {@code work_item_events} is append-only (WIKI4AI-145). */
    @Override
    default void deleteAll(Iterable<? extends WorkItemEvent> entities) {
        throw appendOnly("DELETE ALL");
    }

    /** Not supported: {@code work_item_events} is append-only (WIKI4AI-145). */
    @Override
    default void deleteAll() {
        throw appendOnly("DELETE ALL");
    }

    /**
     * Not supported: {@code saveAndFlush} is the "write this exact state now"
     * method, and an event has no later state to write.
     */
    @Override
    default WorkItemEvent saveAndFlush(WorkItemEvent entity) {
        throw appendOnly("SAVE AND FLUSH");
    }

    /**
     * Not supported — and this one is the reason the list of refusals has to be
     * exhaustive rather than plausible.
     * <p>
     * {@code deleteAllInBatch} is not "delete, in bulk". It is a different SQL
     * statement: {@code DELETE FROM work_item_events}, issued straight against the
     * database. It does not load entities, so {@link WorkItemEvent}'s
     * {@code @PreRemove} never fires — a JPA lifecycle annotation has nothing to say
     * about a query the provider sends without entities. And the guard V21 installs in
     * the schema is a {@code BEFORE UPDATE} trigger, because a {@code BEFORE DELETE}
     * trigger would break the {@code ON DELETE CASCADE} that SPEC's own DDL declares;
     * so the database does not refuse it either.
     * <p>
     * Left inherited, one call would erase the entire audit trail of every work item
     * on the instance — exactly the operation WIKI4AI-145 exists to make impossible.
     * The test {@code noDeleteIsInheritedUnchanged} is what found it.
     */
    @Override
    default void deleteAllInBatch() {
        throw appendOnly("DELETE ALL IN BATCH");
    }

    /** Not supported: {@code work_item_events} is append-only (WIKI4AI-145). */
    @Override
    default void deleteAllInBatch(Iterable<WorkItemEvent> entities) {
        throw appendOnly("DELETE ALL IN BATCH");
    }

    /**
     * Not supported — and the reason WIKI4AI-172 exists.
     * <p>
     * {@code deleteInBatch} is a <b>tenth</b> mutating method of {@code JpaRepository}.
     * It is declared on {@code JpaRepository} and not on {@code CrudRepository} or
     * {@code ListCrudRepository}, so a refusal list copied from the CRUD names leaves it
     * inherited. In Spring Data JPA 3.2 it happens to be a {@code default} method whose
     * body delegates to {@link #deleteAllInBatch(Iterable)}, which this interface does
     * refuse — so the call failed <i>by accident</i>, through a body of code this
     * interface does not own. That is not a guard, it is a dependency on somebody else's
     * implementation detail: the day that default stops delegating, the audit trail
     * becomes deletable again and nothing in this file says so.
     * {@code AppendOnlyRepositoryGuardTest} derived the list from the interface instead
     * of from memory, found the gap, and this override closes it — the refusal is now
     * stated here, next to the other nine.
     */
    @Override
    default void deleteInBatch(Iterable<WorkItemEvent> entities) {
        throw appendOnly("DELETE IN BATCH");
    }

    /** Not supported: {@code work_item_events} is append-only (WIKI4AI-145). */
    @Override
    default void deleteAllByIdInBatch(Iterable<Long> ids) {
        throw appendOnly("DELETE ALL BY ID IN BATCH");
    }

    private static UnsupportedOperationException appendOnly(String operation) {
        return new UnsupportedOperationException(
                "work_item_events is append-only (WIKI4AI-145): " + operation
                        + " is not available on WorkItemEventRepository. Events are written once by"
                        + " WorkItemEventService.record(...) and never changed afterwards.");
    }
}
