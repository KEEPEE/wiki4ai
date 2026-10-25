package com.wiki4ai.repository;

import com.wiki4ai.model.Resource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for the {@link Resource} entity (WIKI4AI-155..157).
 * <p>
 * Beyond the CRUD plumbing it carries the two statements that are the whole point of
 * the capacity ledger: {@link #reserve} and {@link #decrementSlots}. Both are single
 * conditional UPDATEs, and both are the ONLY way {@code active_slots} ever changes —
 * the entity has no setter for it. That is what makes the counter trustworthy: there is
 * no code path that writes it from a value somebody computed somewhere else.
 *
 * <h2>Why reserve is one statement and not a read-then-write</h2>
 * The check "does this resource still have room" and the write "I took some of it" are
 * the same statement. Two callers racing for the last unit therefore cannot both win:
 * the loser's WHERE clause no longer holds and it updates 0 rows. A read-then-write
 * would let both read {@code active_slots = 0} and both write 1. This is the same shape
 * as {@code WorkItemRepository.claim} (WIKI4AI-141) and
 * {@code DocumentRepository}/{@code RefreshTokenRepository} before it.
 * <p>
 * Plain JPQL has no {@code RETURNING} (SPEC rule 12 — H2 supports neither), so the
 * statement reports a count, not the new row. A caller that needs the new values re-reads
 * the entity; {@code clearAutomatically = true} is what makes that re-read honest instead
 * of returning the stale copy the persistence context still held.
 */
@Repository
public interface ResourceRepository extends JpaRepository<Resource, Long> {

    /** Every resource, ordered by the identifier clients address it by. */
    List<Resource> findAllByOrderByKeyAsc();

    /** Lookup by the client-facing key. The reservation endpoints use this, not the id. */
    Optional<Resource> findByKey(String key);

    /** Whether a key is already taken (POST). */
    boolean existsByKey(String key);

    /** Whether a key is taken by a DIFFERENT resource (PUT). */
    boolean existsByKeyAndIdNot(String key, Long id);

    /**
     * Take {@code slots} units of one resource, atomically.
     * <p>
     * The WHERE clause is the entire precondition: the resource exists, it is enabled,
     * and it has room. There is no matching capacity check in Java and there must not be
     * one — a pre-check would be a read-then-write and would let two racing callers both
     * pass it.
     * <p>
     * {@code r.key} is the entity attribute; the column itself is quoted in the mapping
     * because {@code key} is a keyword in H2 2.x.
     *
     * @param key   client-facing key of the resource
     * @param slots how many units to take; {@code int}, never a boxed type, never a date
     * @return 1 when the reservation was made, 0 when the guard did not hold
     *         (disabled resource, or not enough room)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Resource r SET r.activeSlots = r.activeSlots + :slots "
            + "WHERE r.key = :key AND r.enabled = true AND r.activeSlots + :slots <= r.capacity")
    int reserve(@Param("key") String key, @Param("slots") int slots);

    /**
     * Give {@code slots} units back, guarded so it cannot underflow.
     * <p>
     * {@code active_slots >= :slots} is part of the statement on purpose. The DB CHECK
     * {@code active_slots >= 0} is the last line of defence and a test asserts that it
     * really rejects an underflow — but a guard that only exists in the database would
     * mean the application's normal path relies on catching an error to be correct. The
     * statement must not be able to go negative even if its caller is wrong about how
     * many slots the row held.
     * <p>
     * Callers must invoke this only when the matching {@code ReservationRepository.release}
     * returned 1, so that one released reservation moves the counter exactly once.
     *
     * @return 1 when the counter moved, 0 when the guard did not hold
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Resource r SET r.activeSlots = r.activeSlots - :slots "
            + "WHERE r.id = :id AND r.activeSlots >= :slots")
    int decrementSlots(@Param("id") Long id, @Param("slots") int slots);
}
