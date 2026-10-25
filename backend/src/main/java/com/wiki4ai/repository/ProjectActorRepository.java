package com.wiki4ai.repository;

import com.wiki4ai.model.ProjectActor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for the ProjectActor entity (WIKI4AI-132).
 * Provides CRUD plus the lookups the project-actor API needs.
 * <p>
 * All queries are plain derived queries — no JPQL, no {@code RETURNING}, no
 * {@code ON CONFLICT} — so they stay portable to the H2 test database.
 */
@Repository
public interface ProjectActorRepository extends JpaRepository<ProjectActor, Long> {

    /**
     * All assignments of a project.
     *
     * @param projectId id of the project
     * @return assignment rows
     */
    List<ProjectActor> findByProjectId(Long projectId);

    /**
     * The assignment of one actor to one project. At most one row exists
     * (UNIQUE constraint uq_project_actor, V16).
     *
     * @param projectId id of the project
     * @param actorId   id of the actor
     * @return the assignment, if it exists
     */
    Optional<ProjectActor> findByProjectIdAndActorId(Long projectId, Long actorId);

    /**
     * All projects an actor is assigned to.
     *
     * @param actorId id of the actor
     * @return assignment rows
     */
    List<ProjectActor> findByActorId(Long actorId);

    /**
     * Whether an actor is already assigned to a project. Used to reject a
     * duplicate assignment with 409 Conflict.
     *
     * @param projectId id of the project
     * @param actorId   id of the actor
     * @return true when the assignment exists
     */
    boolean existsByProjectIdAndActorId(Long projectId, Long actorId);

    /**
     * All assignments of a project ordered by role (stable list order for the
     * API). Role ordering is alphabetical here: admin, planner, viewer, worker.
     *
     * @param projectId id of the project
     * @return assignment rows
     */
    List<ProjectActor> findAllByProjectIdOrderByRoleAsc(Long projectId);

    /**
     * All assignments of a project with a given role. Used by the role filter of
     * the list endpoint and by the "does the caller hold an admin actor in this
     * project" check.
     *
     * @param projectId id of the project
     * @param role      'viewer' | 'worker' | 'planner' | 'admin'
     * @return assignment rows
     */
    List<ProjectActor> findByProjectIdAndRole(Long projectId, String role);
}
