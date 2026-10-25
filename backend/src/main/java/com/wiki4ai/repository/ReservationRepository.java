package com.wiki4ai.repository;

import com.wiki4ai.model.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Repository interface for the {@link Reservation} entity (WIKI4AI-155..157).
 * <p>
 * It carries the read side of the ledger (which holdings a resource has, how many are
 * still live) and the one write that ends a holding: {@link #release}.
 *
 * <h2>Idempotency is the guard, not a pre-check</h2>
 * {@link #release} carries {@code releasedAt IS NULL} in its WHERE clause. A reservation
 * that is already released therefore matches nothing and the statement updates 0 rows —
 * no Java-side "is it already released?" test is involved, and none would be correct:
 * a pre-check plus a write is a read-modify-write, and two callers that both pass the
 * pre-check would both release and both decrement. With the guard, exactly one of them
 * moves the row and exactly one of them touches the resource counter.
 * <p>
 * {@code now} is a {@link LocalDateTime}, NOT an {@code Instant} (SPEC rule 11b):
 * {@code released_at} and {@code expires_at} are TIMESTAMP without a time zone in V25 and
 * the postgres profile runs with {@code ddl-auto: validate}.
 */
@Repository
public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    /** Every holding of one resource, newest first (the history view). */
    List<Reservation> findByResourceIdOrderByCreatedAtDesc(Long resourceId);

    /** The holdings of one resource that are still held, ordered by the expiry. */
    List<Reservation> findByResourceIdAndReleasedAtIsNullOrderByExpiresAtAsc(Long resourceId);

    /** How many holdings of one resource are still held. Guards the DELETE of the resource. */
    long countByResourceIdAndReleasedAtIsNull(Long resourceId);

    /**
     * The candidates of one reaper sweep, before anything is written.
     * <p>
     * Read first because JPQL has no {@code RETURNING}: the sweep has to know the
     * {@code slots} of each released reservation to lower the resource counter by exactly
     * that amount, and a bulk UPDATE reports a count, not a set. This is the same
     * SELECT-then-per-row-UPDATE shape {@code WorkItemLeaseReaper} uses since WIKI4AI-145.
     * <p>
     * The candidate list is only a list of ids to try. It is not a read-modify-write:
     * every write is still a conditional UPDATE whose WHERE clause repeats the candidate
     * test ({@link #release}), so a row that stopped being a candidate between the two
     * statements — because its own holder released it, or because another instance swept
     * it — is not written here and lowers nothing.
     * <p>
     * Deliberately unconditional about the reservation's work item: an expired holding is
     * expired whether or not the item it was held for is still alive, exactly as the lease
     * reaper releases {@code claimed} and {@code running} alike.
     */
    @Query("SELECT r FROM Reservation r WHERE r.releasedAt IS NULL AND r.expiresAt < :now "
            + "ORDER BY r.id ASC")
    List<Reservation> findExpiredCandidates(@Param("now") LocalDateTime now);

    /**
     * Release ONE reservation, with the candidate test in the WHERE clause.
     *
     * @return 1 when this call ended this holding — and only this call may then lower the
     *         resource counter; 0 when the reservation was already released or does not exist
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Reservation r SET r.releasedAt = :now WHERE r.id = :id AND r.releasedAt IS NULL")
    int release(@Param("id") Long id, @Param("now") LocalDateTime now);
}
