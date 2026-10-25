package com.wiki4ai.repository;

import com.wiki4ai.model.Actor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for the Actor entity (WIKI4AI-131).
 * Provides CRUD operations plus the lookups the actor API needs.
 * <p>
 * All queries are plain derived queries / JPQL — no {@code RETURNING}, no
 * {@code ON CONFLICT}, so they stay portable to the H2 test database.
 */
@Repository
public interface ActorRepository extends JpaRepository<Actor, Long> {

    /**
     * Find an actor by its exact handle. Handles are stored normalized to
     * lowercase by the service, so the lookup is exact (the DB UNIQUE
     * constraint is case-sensitive).
     *
     * @param handle normalized handle
     * @return the actor, if it exists
     */
    Optional<Actor> findByHandle(String handle);

    /**
     * Whether an actor with this exact handle already exists. Used to reject a
     * duplicate handle with 409 Conflict.
     *
     * @param handle normalized handle
     * @return true when the handle is taken
     */
    boolean existsByHandle(String handle);

    /**
     * All actors ordered by handle (stable, pagination-friendly list order).
     *
     * @return all actors
     */
    List<Actor> findAllByOrderByHandleAsc();

    /**
     * All actors with a given status, ordered by handle.
     *
     * @param status 'active' | 'paused' | 'archived'
     * @return matching actors
     */
    List<Actor> findByStatusOrderByHandleAsc(String status);

    /**
     * All actors owned by a given user, ordered by handle.
     *
     * @param ownerUserId id of the owning user
     * @return matching actors
     */
    List<Actor> findByOwnerUserIdOrderByHandleAsc(Long ownerUserId);

    /**
     * The user's own actor of a given kind, lowest id first (WIKI4AI-134). Used
     * to attribute a human's write to the {@code kind='human'} actor they
     * registered — never to an actor belonging to somebody else, because the
     * lookup is restricted to {@code owner_user_id}.
     *
     * @param ownerUserId id of the owning user
     * @param kind        'agent' | 'human' | 'service'
     * @return the actor, if the user has one of that kind
     */
    Optional<Actor> findFirstByOwnerUserIdAndKindOrderByIdAsc(Long ownerUserId, String kind);
}
