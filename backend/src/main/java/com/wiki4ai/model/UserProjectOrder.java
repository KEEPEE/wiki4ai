package com.wiki4ai.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * One viewer's ordering of one project (WIKI4AI-200, PER-1, migration V28).
 *
 * <h2>The problem this row exists to solve</h2>
 * {@code projects.pinned} and {@code projects.sort_order} are a single global state.
 * Every user who created a project holds MANAGE on it, so pinning moved that project to
 * the top of <i>everybody's</i> dashboard and a reorder renumbered other people's
 * projects. A row here means "this viewer has customised this one project": their own pin
 * flag and their own position, invisible to everyone else.
 *
 * <h2>Absence is a value</h2>
 * No row does not mean "uninitialised" — it means "this viewer has customised nothing, so
 * they see the global value from V26". That is what makes the feature ship with an empty
 * table and no backfill: nobody's list changes on the day this deploys, and the global
 * order stays a live default an administrator can curate rather than a snapshot frozen at
 * upgrade time. The read path therefore LEFT JOINs this table and falls back, and the
 * write path materialises only what the viewer actually touched.
 *
 * <h2>Why plain ids and not JPA associations</h2>
 * Same convention as {@link WorkItem} and {@link Reservation}: the database carries the
 * real keys with {@code ON DELETE CASCADE} on both sides (deleting a project takes every
 * viewer's row for it, deleting a user takes their whole customisation), and the service
 * loads what it needs explicitly. Mapping {@code @ManyToOne Project} here would add a
 * second way to reach the project row without adding any capability, and would make the
 * cascade a JPA concern in a design where it is a database one.
 *
 * <h2>Why {@code pinned} and {@code sortOrder} ARE updatable</h2>
 * The two global columns on {@link Project} are {@code updatable = false} (WIKI4AI-197)
 * because a content save of the project used to write their stale loaded values back. That
 * argument does not apply here: this entity has no other writer. A save of this row IS the
 * write, so the columns are ordinary and mutable. The reorder path still uses a guarded
 * JPQL UPDATE rather than a save, because the guard is a compare-and-set on the position
 * the caller read, not an optimistic-lock version.
 */
@Entity
@Table(name = "user_project_order", uniqueConstraints = {
        // One row per (viewer, project) is the whole feature: a viewer has exactly one
        // position and one pin state for a project. Declared on the entity as well as in
        // V28 so the H2 schema the suite builds from the entities enforces it too.
        @UniqueConstraint(name = "uk_user_project_order", columnNames = {"user_id", "project_id"})
})
@Getter
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class UserProjectOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The viewer this ordering belongs to. Never from client input: it is always the
     * authenticated caller, resolved in the service layer.
     */
    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    /** The project this row orders for {@link #userId}. */
    @Column(name = "project_id", nullable = false, updatable = false)
    private Long projectId;

    /**
     * Whether this viewer pins the project to the top of their list. The project's own
     * global {@code pinned} is the fallback while this row does not exist, and is the
     * value materialised into here the first time this viewer writes.
     */
    @Setter
    @Column(name = "pinned", nullable = false)
    @Builder.Default
    private boolean pinned = false;

    /**
     * This viewer's position in their manual order. Dense 0..n-1 across the projects the
     * viewer reordered; ties with projects they have no row for are broken by {@code id},
     * exactly as they are for the global column.
     */
    @Setter
    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private int sortOrder = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    /**
     * Only reached by an entity save. The reorder and pin writes are JPQL UPDATEs that set
     * {@code updated_at} explicitly, because a bulk UPDATE does not run lifecycle
     * callbacks — leaving it out would freeze the column at insert time.
     */
    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UserProjectOrder)) return false;
        UserProjectOrder that = (UserProjectOrder) o;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
