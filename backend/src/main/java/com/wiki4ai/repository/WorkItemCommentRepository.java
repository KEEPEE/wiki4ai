package com.wiki4ai.repository;

import com.wiki4ai.model.WorkItemComment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/**
 * Read interface for the immutable comments of a work item (WIKI4AI-148).
 *
 * <h2>Immutability is enforced here, not just written down</h2>
 * A comment is written once and never edited or removed. A plain
 * {@code JpaRepository} would hand every caller {@code delete}, {@code deleteById},
 * {@code deleteAll}, {@code saveAndFlush} — methods whose names describe operations
 * this table does not allow. They are <b>overridden here with implementations that
 * throw</b>, all ten of them: five from {@code CrudRepository} and five more from
 * {@code JpaRepository}, the last of which ({@code deleteInBatch}) is declared only on
 * {@code JpaRepository} and is the one a refusal list copied from the CRUD names would
 * have missed:
 * <ul>
 *   <li>the call compiles, resolves to this interface and fails at runtime with a
 *   message naming the rule it broke;</li>
 *   <li>it fails the same way from any other bean, so the rule cannot be routed
 *   around by injecting this repository somewhere else;</li>
 *   <li>the bulk variants are refused too. {@code deleteAllInBatch} and
 *   {@code deleteAllByIdInBatch} send a {@code DELETE FROM} that loads no entity, so
 *   {@link WorkItemComment}'s {@code @PreRemove} never runs for them, and the trigger
 *   V22 installs is a {@code BEFORE UPDATE} trigger — a {@code BEFORE DELETE} trigger
 *   would break the {@code ON DELETE CASCADE} SPEC's own DDL declares. Left
 *   inherited, one call would erase the whole discussion of every item on the
 *   instance.</li>
 * </ul>
 * Behind this interface, {@link WorkItemComment}'s {@code @PreUpdate} /
 * {@code @PreRemove} refuse the same writes at the JPA provider level, which catches
 * the paths that never touch this interface (an EntityManager, a merge, a cascade),
 * and the V22 trigger catches an UPDATE issued by anything that reaches the
 * database. {@code save} is the one write that stays, and
 * {@code WorkItemCommentService.create} is the only code in the application that
 * calls it.
 * <p>
 * What is left to the database is the {@code ON DELETE CASCADE} from
 * {@code work_items} (SPEC's DDL): deleting an item takes its comments with it. That
 * is DDL-level and does not go through JPA.
 * <p>
 * All queries are plain derived queries — no {@code RETURNING}, no
 * {@code ON CONFLICT} (SPEC rule 12) — so they stay portable to the H2 test database.
 */
@Repository
public interface WorkItemCommentRepository extends JpaRepository<WorkItemComment, Long> {

    // ── Reads ─────────────────────────────────────────────────────────────────

    /**
     * Every comment of one work item, oldest first.
     * <p>
     * {@code id} is the tie-breaker and it is not decoration: {@code created_at} is a
     * TIMESTAMP(6), so two comments written in the same microsecond can share a
     * timestamp, and ordering by the timestamp alone would leave their order to the
     * database. Ids are assigned in insertion order, so the pair is deterministic —
     * the same reasoning {@code WorkItemEventRepository} uses for the timeline.
     */
    List<WorkItemComment> findByWorkItemIdOrderByCreatedAtAscIdAsc(Long workItemId);

    /** The same list, paginated (AC of WIKI4AI-148). */
    Page<WorkItemComment> findByWorkItemIdOrderByCreatedAtAscIdAsc(Long workItemId, Pageable pageable);

    /** Comments of one kind, oldest first. */
    List<WorkItemComment> findByWorkItemIdAndKindOrderByCreatedAtAscIdAsc(Long workItemId, String kind);

    /** Comments of one kind, paginated. */
    Page<WorkItemComment> findByWorkItemIdAndKindOrderByCreatedAtAscIdAsc(
            Long workItemId, String kind, Pageable pageable);

    /**
     * Whether an item has at least one comment of a kind. Not used by this story's
     * two endpoints; it is the lookup the {@code done}-requires-a-{@code result}
     * invariant of WIKI4AI-150 needs, and having it here means that story does not
     * have to invent a query or reach around this interface to write one.
     */
    boolean existsByWorkItemIdAndKind(Long workItemId, String kind);

    /**
     * Keyset page of the comments of a set of work items, oldest id first
     * (WIKI4AI-152, stream "comment").
     * <p>
     * {@code work_item_id IN (...) AND id > ? ORDER BY id} — the shape
     * {@code idx_wic_item_id (work_item_id, id)} of V24 exists for. The set of item ids is
     * resolved once per inbox read by {@code WorkItemRepository}, never once per item: a
     * comment query inside a loop over items is the N+1 this story is not allowed to have.
     * <p>
     * A derived query, like every other read of this interface. There is no {@code @Query}
     * here at all, which is what "the payload TEXT is never pattern-matched" means in this
     * codebase — see {@code WorkItemCommentFiltersIntegrationTest.Proofs}.
     */
    List<WorkItemComment> findByWorkItemIdInAndIdGreaterThanOrderByIdAsc(
            Collection<Long> workItemIds, Long id, Pageable pageable);

    /**
     * Keyset page of the comments of one kind over a set of work items, oldest id first
     * (WIKI4AI-152, stream "mention").
     * <p>
     * The kind is a whole-value equality, not a match: {@code kind = 'handoff'} is an index
     * lookup on V22's {@code (work_item_id, kind)}. Which of those handoffs are addressed to
     * the caller is decided afterwards, in the service, by decoding the payload JSON — the
     * same method WIKI4AI-151 used for {@code needs_human}.
     */
    List<WorkItemComment> findByWorkItemIdInAndKindAndIdGreaterThanOrderByIdAsc(
            Collection<Long> workItemIds, String kind, Long id, Pageable pageable);

    // ── The operations this table does not allow ──────────────────────────────

    /** Not supported: {@code work_item_comments} is immutable (WIKI4AI-148). */
    @Override
    default void delete(WorkItemComment entity) {
        throw immutable("DELETE");
    }

    /** Not supported: {@code work_item_comments} is immutable (WIKI4AI-148). */
    @Override
    default void deleteById(Long id) {
        throw immutable("DELETE BY ID");
    }

    /** Not supported: {@code work_item_comments} is immutable (WIKI4AI-148). */
    @Override
    default void deleteAllById(Iterable<? extends Long> ids) {
        throw immutable("DELETE ALL BY ID");
    }

    /** Not supported: {@code work_item_comments} is immutable (WIKI4AI-148). */
    @Override
    default void deleteAll(Iterable<? extends WorkItemComment> entities) {
        throw immutable("DELETE ALL");
    }

    /** Not supported: {@code work_item_comments} is immutable (WIKI4AI-148). */
    @Override
    default void deleteAll() {
        throw immutable("DELETE ALL");
    }

    /**
     * Not supported: {@code saveAndFlush} is the "write this exact state now"
     * method, and an immutable comment has no later state to write.
     */
    @Override
    default WorkItemComment saveAndFlush(WorkItemComment entity) {
        throw immutable("SAVE AND FLUSH");
    }

    /**
     * Not supported — and the reason the list of refusals has to be exhaustive
     * rather than plausible. {@code deleteAllInBatch} is not "delete, in bulk": it is
     * {@code DELETE FROM work_item_comments} sent straight to the database, with no
     * entities loaded, so no lifecycle callback runs for it. See the class comment.
     */
    @Override
    default void deleteAllInBatch() {
        throw immutable("DELETE ALL IN BATCH");
    }

    /** Not supported: {@code work_item_comments} is immutable (WIKI4AI-148). */
    @Override
    default void deleteAllInBatch(Iterable<WorkItemComment> entities) {
        throw immutable("DELETE ALL IN BATCH");
    }

    /**
     * Not supported: {@code work_item_comments} is immutable (WIKI4AI-148).
     * <p>
     * This one is easy to miss, which is exactly why the list is derived from the
     * interface instead of from memory: {@code deleteInBatch} is a tenth mutating
     * method, declared on {@code JpaRepository} and not on {@code CrudRepository}, so
     * a refusal list copied from the CRUD names leaves it inherited.
     */
    @Override
    default void deleteInBatch(Iterable<WorkItemComment> entities) {
        throw immutable("DELETE IN BATCH");
    }

    /** Not supported: {@code work_item_comments} is immutable (WIKI4AI-148). */
    @Override
    default void deleteAllByIdInBatch(Iterable<Long> ids) {
        throw immutable("DELETE ALL BY ID IN BATCH");
    }

    private static UnsupportedOperationException immutable(String operation) {
        return new UnsupportedOperationException(
                "work_item_comments is immutable (WIKI4AI-148): " + operation
                        + " is not available on WorkItemCommentRepository. Comments are written once by"
                        + " WorkItemCommentService.create(...) and never changed afterwards.");
    }
}
