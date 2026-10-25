package com.wiki4ai.repository;

import com.wiki4ai.model.WorkItem;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * Repository interface for the {@link WorkItem} entity (WIKI4AI-140).
 * <p>
 * Beyond the CRUD plumbing it carries the one operation of WIKI4AI-141 that must
 * be atomic: {@link #claim}. It is a single conditional UPDATE — the check
 * "is this item still ready" and the write "it is claimed by me" are the same
 * statement, so two agents racing for the same item cannot both win. A plain
 * read-then-write would let both read 'ready' and both write.
 */
@Repository
public interface WorkItemRepository extends JpaRepository<WorkItem, Long> {

    /** Items of one project, newest first. */
    List<WorkItem> findByProjectIdOrderByCreatedAtDesc(Long projectId);

    /** Items of one project in one state, newest first. */
    List<WorkItem> findByProjectIdAndStatusOrderByCreatedAtDesc(Long projectId, String status);

    /** Items in one state across every project, newest first. */
    List<WorkItem> findByStatusOrderByCreatedAtDesc(String status);

    /** Every item, newest first (the service filters it by project visibility). */
    List<WorkItem> findAllByOrderByCreatedAtDesc();

    /** Direct children of a work item. */
    List<WorkItem> findByParentId(Long parentId);

    /** Whether an external reference is already taken by any item. */
    boolean existsByExternalRef(String externalRef);

    /** Whether an external reference is taken by a DIFFERENT item (update path). */
    boolean existsByExternalRefAndIdNot(String externalRef, Long id);

    // ── List filters (WIKI4AI-144) ────────────────────────────────────────────
    //
    // Four derived queries, one per combination of the two filters that can be
    // pushed into SQL. They are derived methods rather than one optional-parameter
    // JPQL on purpose: `(:param IS NULL OR w.x = :param)` forces Hibernate to
    // infer the type of a null parameter, and the derived names cannot get that
    // wrong. `assignee_actor_id` and `status` are exactly the columns V20 indexed
    // (idx_work_items_assignee, idx_work_items_project_status).
    //
    // `label` is deliberately NOT here: labels are a comma-encoded TEXT column and
    // no LIKE can match one label without matching a substring of another. The
    // service decodes the column and compares whole tokens — see WorkItemService.hasLabel.

    /** Items held by one actor, newest first. */
    List<WorkItem> findByAssigneeActorIdOrderByCreatedAtDesc(Long assigneeActorId);

    /** Items in one state and held by one actor, newest first. */
    List<WorkItem> findByStatusAndAssigneeActorIdOrderByCreatedAtDesc(String status, Long assigneeActorId);

    /** Items of one project held by one actor, newest first. */
    List<WorkItem> findByProjectIdAndAssigneeActorIdOrderByCreatedAtDesc(Long projectId, Long assigneeActorId);

    /** Items of one project, in one state, held by one actor, newest first. */
    List<WorkItem> findByProjectIdAndStatusAndAssigneeActorIdOrderByCreatedAtDesc(
            Long projectId, String status, Long assigneeActorId);

    /**
     * Keyset page of the items one actor currently holds, oldest id first
     * (WIKI4AI-152, stream "item").
     * <p>
     * {@code assignee_actor_id = ? AND id > ? ORDER BY id}. The index that serves the
     * equality part already exists — {@code idx_work_items_assignee (assignee_actor_id,
     * status)} of V20:69, shipped with WIKI4AI-140 — and WIKI4AI-154 deliberately does not
     * create a second one over the same column.
     */
    List<WorkItem> findByAssigneeActorIdAndIdGreaterThanOrderByIdAsc(
            Long assigneeActorId, Long id, Pageable pageable);

    /**
     * Ids of the work items that belong to one actor's inbox: held by the actor, or created
     * by it (WIKI4AI-152 stream "comment", WIKI4AI-153 unseen events).
     * <p>
     * A projection of ids, not entities: the inbox needs the ids to scope one comment
     * query, and loading the rows twice — once here and once per item there — is the N+1
     * this must not have. Ordered by id so the set is stable across calls.
     */
    @Query("SELECT w.id FROM WorkItem w WHERE w.assigneeActorId = :actorId OR w.createdByActorId = :actorId ORDER BY w.id")
    List<Long> findIdsAssignedOrCreatedBy(@Param("actorId") Long actorId);

    /**
     * Ids of the work items of a set of projects (WIKI4AI-152, scope of the "mention"
     * stream).
     * <p>
     * Also a projection of ids, for the same reason. An actor is a member of few projects,
     * so the parameter list is small and bounded by the actor; the result is what the
     * single handoff-comment query is scoped by.
     */
    @Query("SELECT w.id FROM WorkItem w WHERE w.projectId IN :projectIds ORDER BY w.id")
    List<Long> findIdsOfProjects(@Param("projectIds") Collection<Long> projectIds);

    // ── Lease reaper (WIKI4AI-142, per-row since WIKI4AI-145) ─────────────────

    /**
     * The candidates of one sweep, before anything is written.
     * <p>
     * WIKI4AI-145 is why this query exists. The reaper used to be a single bulk
     * UPDATE and that was enough while its only job was to move rows; the audit
     * trail of WIKI4AI-146 has to say <i>which</i> rows it moved and for whom, and
     * a bulk UPDATE reports a count, not a set. Plain JPQL has no {@code RETURNING}
     * (SPEC rule 12 — H2 supports neither), so the set has to be read first.
     * <p>
     * Reading the candidates does not make the sweep a read-modify-write: the
     * candidate list is only a list of ids to try. Every write is still a
     * conditional UPDATE whose WHERE clause is the whole candidate test
     * ({@link #releaseExpiredLease}), so a row that stopped being a candidate
     * between the two statements — because another instance, or its own holder,
     * wrote it — is not written by this sweep and produces no event.
     * <p>
     * {@code now} is a {@link LocalDateTime}, NOT an {@code Instant} (SPEC rule
     * 11b): {@code lease_expires_at} is TIMESTAMP without a time zone in V20 and
     * the postgres profile runs with {@code ddl-auto: validate}.
     */
    @Query("SELECT w FROM WorkItem w WHERE w.status IN ('claimed', 'running') "
            + "AND w.leaseExpiresAt IS NOT NULL AND w.leaseExpiresAt < :now ORDER BY w.id ASC")
    List<WorkItem> findExpiredLeaseCandidates(@Param("now") LocalDateTime now);

    /**
     * Release the lease of ONE item, with the same guard the old bulk statement
     * carried — plus the id.
     * <p>
     * Atomicity is unchanged and it is the guard, not the candidate list, that
     * provides it: a row is written only while it is still in a claim-holding state
     * with a lease in the past. Two backends sweeping at the same moment therefore
     * cannot both release one item (the loser updates 0 rows), and an item whose
     * holder extended its lease one millisecond before the sweep is not released at
     * all.
     * <p>
     * The return value is what makes the audit honest: 1 means this statement moved
     * this row, so an event may be written for it; 0 means somebody else did or
     * nothing happened, and writing an event for a row whose state did not change
     * would be a false fact in a table whose entire purpose is to hold no false
     * facts.
     * <p>
     * {@code updatedAt} is written by the statement because a bulk UPDATE bypasses
     * the entity's {@code @PreUpdate}.
     *
     * @return 1 when this sweep released this item, 0 when the guard no longer held
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE WorkItem w SET w.status = 'ready', w.assigneeActorId = null, "
            + "w.claimToken = null, w.leaseExpiresAt = null, w.updatedAt = :now, "
            + "w.version = w.version + 1 "
            + "WHERE w.id = :id "
            + "AND w.status IN ('claimed', 'running') "
            + "AND w.leaseExpiresAt IS NOT NULL AND w.leaseExpiresAt < :now")
    int releaseExpiredLease(@Param("id") Long id, @Param("now") LocalDateTime now);

    /**
     * Candidates of the heal sweep: an item in a claim-free state ({@code ready},
     * {@code blocked}, {@code cancelled}) that still carries an assignee, a claim
     * token or a live lease — the rows WIKI4AI-170 left behind.
     * <p>
     * Read before writing for the same reason as {@link #findExpiredLeaseCandidates}:
     * the event has to name the actor whose stale claim was cleared, and after the
     * write that actor is gone from the row.
     */
    @Query("SELECT w FROM WorkItem w WHERE w.status IN ('ready', 'blocked', 'cancelled') "
            + "AND (w.assigneeActorId IS NOT NULL OR w.claimToken IS NOT NULL "
            + "OR w.leaseExpiresAt IS NOT NULL) ORDER BY w.id ASC")
    List<WorkItem> findStaleClaimCandidates();

    /**
     * Clear the stale claim triple of ONE item, guarded by the same test that made
     * it a candidate.
     * <p>
     * Idempotent for the same reason the bulk statement was: the write removes the
     * very condition that made the row a candidate, so a second sweep finds nothing.
     * Time-independent on purpose — a stale claim is wrong whether or not its lease
     * has expired.
     *
     * @return 1 when this sweep cleaned this row, 0 when it no longer qualifies
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE WorkItem w SET w.assigneeActorId = null, w.claimToken = null, "
            + "w.leaseExpiresAt = null, w.version = w.version + 1 "
            + "WHERE w.id = :id "
            + "AND w.status IN ('ready', 'blocked', 'cancelled') "
            + "AND (w.assigneeActorId IS NOT NULL OR w.claimToken IS NOT NULL "
            + "OR w.leaseExpiresAt IS NOT NULL)")
    int clearStaleClaim(@Param("id") Long id);

    // ── Lease extension (WIKI4AI-142) ─────────────────────────────────────────

    /**
     * Atomic lease renewal for the holder of a claim.
     * <p>
     * The guard carries the whole holder check — the state must still be a
     * claim-holding one, the assignee must still be this actor, and the token must
     * still be the one issued — so the renewal cannot land on a lease that the
     * reaper or an update already released: 0 rows, and the service turns that
     * into 409.
     *
     * @return 1 when the lease was renewed, 0 when this caller no longer holds it
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE WorkItem w SET w.leaseExpiresAt = :lease, w.updatedByActorId = :actorId, "
            + "w.updatedAt = :now, w.version = w.version + 1 "
            + "WHERE w.id = :id AND w.status IN ('claimed', 'running') "
            + "AND w.assigneeActorId = :actorId AND w.claimToken = :token")
    int extendLease(@Param("id") Long id, @Param("actorId") Long actorId,
                    @Param("token") String token, @Param("lease") LocalDateTime lease,
                    @Param("now") LocalDateTime now);

    /**
     * Atomic claim of a ready work item (WIKI4AI-141).
     * <p>
     * Plain JPQL — no {@code RETURNING}, no {@code ON CONFLICT} (SPEC rule 12:
     * the test schema is H2, which supports neither). The guard is the WHERE
     * clause: the statement matches only while the row is still 'ready', so the
     * loser of a race updates 0 rows and the service turns that into 409.
     * <p>
     * {@code lease} is a {@link LocalDateTime}, NOT an {@code Instant}: every
     * time column of this project is TIMESTAMP without a time zone (SPEC rule 11b)
     * and the postgres profile runs with {@code ddl-auto: validate}. An Instant
     * here would map to "timestamp with time zone" and the backend on dev .219
     * would fail to start.
     * <p>
     * {@code clearAutomatically} empties the persistence context after the bulk
     * update, so the caller MUST re-read the entity — the in-memory instance it
     * held before is detached and stale. {@code version} is incremented by the
     * statement itself (bulk updates bypass the JPA @Version mechanism) so that
     * readers can still see that the row moved.
     *
     * @return the number of rows claimed: 1 on success, 0 when the item is not
     *         'ready' (already claimed, running, cancelled, ...)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE WorkItem w SET w.status = 'claimed', w.assigneeActorId = :actorId, "
            + "w.leaseExpiresAt = :lease, w.claimToken = :token, w.version = w.version + 1 "
            + "WHERE w.id = :id AND w.status = 'ready'")
    int claim(@Param("id") Long id, @Param("actorId") Long actorId,
              @Param("lease") LocalDateTime lease, @Param("token") String token);
}
