package com.wiki4ai.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * JPA entity of one holding of a resource (WIKI4AI-155..157, migration V25).
 * <p>
 * A reservation is the <b>truth</b> of the capacity ledger. {@code Resource.activeSlots}
 * is a denormalised count of exactly these rows — the rows are the fact, the counter is
 * a cache of it, and the invariant tests of WIKI4AI-156 compare the two directly so a
 * drift between them cannot hide.
 * <p>
 * A reservation is live while {@code releasedAt IS NULL AND expiresAt > now}. It stops
 * being live in exactly two ways, both of which are a guarded UPDATE on the row itself:
 * the TTL reaper ({@code expiresAt < now}) and the manual release. The guard
 * {@code releasedAt IS NULL} in the WHERE clause is what makes both idempotent — a
 * second release updates 0 rows and therefore decrements nothing.
 *
 * <h2>Why the setters are almost absent</h2>
 * {@code slots}, {@code resourceId}, {@code actorId}, {@code workItemId},
 * {@code expiresAt} and {@code releasedAt} have no setter. A reservation is written once,
 * by the service, inside the same transaction as the {@code reserve} UPDATE that raised
 * the resource's counter; and it is ended only by the guarded statements of
 * {@code ReservationRepository}. A setter on {@code slots} or {@code releasedAt} would let
 * code change the ledger without changing the counter, which is the exact drift this
 * story exists to make impossible.
 *
 * <h2>Time columns</h2>
 * {@link LocalDateTime} for {@code expiresAt}, {@code releasedAt}, {@code createdAt} —
 * {@code TIMESTAMP} without a time zone in V25 (SPEC rule 11b). No {@code Instant}.
 * <p>
 * {@code actorId} and {@code workItemId} are plain ids rather than JPA associations,
 * exactly like every foreign key of {@code WorkItem}: the DB carries the real key with
 * {@code ON DELETE SET NULL}, and the service loads what it needs explicitly.
 */
@Entity
@Table(name = "reservations")
@Getter
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
@Check(name = "chk_reservations_slots", constraints = "slots >= 1")
public class Reservation {

    /** Default holding of one unit (same default as the DB column). */
    public static final int DEFAULT_SLOTS = 1;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Owning resource. {@code ON DELETE CASCADE} in V25. */
    @Column(name = "resource_id", nullable = false)
    private Long resourceId;

    /**
     * The actor that holds the reservation (V15 registry). Written from the verified
     * credential only — never from a body field, a parameter or a header — exactly like
     * the attribution columns of {@code WorkItem}. A reservation is per-actor, which is
     * why a credential that names no actor cannot make one.
     */
    @Column(name = "actor_id")
    private Long actorId;

    /** Optional work item this holding is held for. {@code ON DELETE SET NULL} in V25. */
    @Column(name = "work_item_id")
    private Long workItemId;

    /** How many units are held. At least 1 (DB CHECK). */
    @Column(name = "slots", nullable = false)
    private Integer slots;

    /** Free-text justification. */
    @Column(name = "reason", length = 255)
    private String reason;

    /** When the holding expires on its own. Required — a reservation without a TTL would never end. */
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    /** When the holding ended. {@code NULL} means live. Written only by a guarded UPDATE. */
    @Column(name = "released_at")
    private LocalDateTime releasedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Set the reason. The only mutable field: it says nothing about capacity and is the
     * one thing a holder may legitimately amend.
     */
    public void setReason(String reason) {
        this.reason = reason;
    }

    /** Whether this row is part of the held total right now. */
    public boolean isLive(LocalDateTime now) {
        return releasedAt == null && expiresAt != null && !expiresAt.isBefore(now);
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (slots == null) {
            this.slots = DEFAULT_SLOTS;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Reservation that = (Reservation) o;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return "Reservation{" +
                "id=" + id +
                ", resourceId=" + resourceId +
                ", actorId=" + actorId +
                ", workItemId=" + workItemId +
                ", slots=" + slots +
                ", expiresAt=" + expiresAt +
                ", releasedAt=" + releasedAt +
                '}';
    }
}
