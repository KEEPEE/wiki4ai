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
 * JPA Entity representing an actor assigned to a project (WIKI4AI-132).
 * <p>
 * One row per (project, actor) pair — the DB UNIQUE constraint
 * {@code uq_project_actor} (V16) makes a second assignment of the same actor to
 * the same project impossible, which the service reports as 409 Conflict.
 * <p>
 * {@code role} is the project-scoped capability of the actor:
 * {@code viewer} &lt; {@code worker} &lt; {@code planner} &lt; {@code admin}. Only an
 * {@code admin} (or the project owner, or an ADMIN user) may change the project's
 * actors; that rule is enforced in {@code ProjectActorService}, not in
 * SecurityConfig, because the JWT filter grants ROLE_USER to everyone.
 * <p>
 * {@code projectId} and {@code actorId} are plain ids rather than JPA
 * associations, matching how {@link Actor#ownerUserId} references the users
 * table: the join rows stay readable and the DB owns the cascade behaviour
 * (ON DELETE CASCADE on both columns, V16).
 * <p>
 * Timestamps use {@link LocalDateTime}, matching the {@code TIMESTAMP} column of
 * the V16 migration — with {@code spring.jpa.hibernate.ddl-auto=validate} the
 * entity and the migration must agree on the JDBC type or the backend fails at
 * startup.
 */
@Entity
@Table(name = "project_actors")
@Getter
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class ProjectActor {

    /** Role: read-only access to the project's data. */
    public static final String ROLE_VIEWER = "viewer";

    /** Role: may do the work the project assigns. */
    public static final String ROLE_WORKER = "worker";

    /** Role: may plan and arrange work items. */
    public static final String ROLE_PLANNER = "planner";

    /** Role: may manage the project's actors (assign, change role, remove). */
    public static final String ROLE_ADMIN = "admin";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Project the actor is assigned to (projects table, ON DELETE CASCADE). */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** Assigned actor (actors table, ON DELETE CASCADE). */
    @Column(name = "actor_id", nullable = false)
    private Long actorId;

    /** 'viewer' | 'worker' | 'planner' | 'admin'. */
    @Column(name = "role", nullable = false, length = 16)
    private String role;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Set the project id. Assigned by the service from the path, never from the
     * request body.
     */
    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    /**
     * Set the actor id.
     */
    public void setActorId(Long actorId) {
        this.actorId = actorId;
    }

    /**
     * Set the role. The value is validated by the service layer before it
     * reaches here (an unknown role is a 400 Bad Request).
     */
    public void setRole(String role) {
        this.role = role;
    }

    /**
     * Set the id. Used mainly for testing and entity comparison.
     */
    public void setId(Long id) {
        this.id = id;
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        // Defensive default: the DB column carries DEFAULT 'viewer'. Keep the
        // Java side consistent so an entity built directly (tests, seeders)
        // cannot bypass the column default.
        if (role == null || role.isBlank()) {
            role = ROLE_VIEWER;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ProjectActor that = (ProjectActor) o;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return "ProjectActor{" +
                "id=" + id +
                ", projectId=" + projectId +
                ", actorId=" + actorId +
                ", role='" + role + '\'' +
                '}';
    }
}
