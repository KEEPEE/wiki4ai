package com.wiki4ai.repository;

import com.wiki4ai.model.User;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Repository for User entity operations.
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * Find a user by username.
     */
    Optional<User> findByUsername(String username);

    /**
     * Lock one user row for the rest of the transaction (WIKI4AI-200, PER-1).
     *
     * <p>This is the mutex that serialises the writers of {@code user_project_order}. The
     * obvious alternative — locking the viewer's existing rows — does not work, because a
     * viewer who has customised nothing has no rows and {@code SELECT ... FOR UPDATE} over
     * an empty result set locks nothing. Two first-time pins by the same account would then
     * both read {@code min(sort_order)} from nothing, both take the same slot, and the
     * second insert would either duplicate the position or die on
     * {@code uk_user_project_order}. The user row always exists, so it always locks.</p>
     *
     * <p>It is also strictly better than the global {@code ProjectRepository
     * .findAllForUpdate()} this replaces for projects: two different viewers pinning at the
     * same instant take two different user rows and never wait for each other, which was
     * the whole complaint the story is about.</p>
     *
     * <p>The timeout is a JDBC statement timeout, not a {@code FOR UPDATE} timeout —
     * PostgreSQL has {@code NOWAIT} and {@code SKIP LOCKED} but no {@code FOR UPDATE WAIT
     * n}. Without a bounded wait a stuck transaction holds a pooled connection, and OSIV is
     * on, so the request holds one too. Same spelling as the two project locks of
     * WIKI4AI-197.</p>
     *
     * @param id the user whose row is the mutex
     * @return the user, or empty when the account is gone
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.query.timeout", value = "5000"))
    @Query("SELECT u FROM User u WHERE u.id = :id")
    Optional<User> findUserByIdForUpdate(@Param("id") Long id);

    /**
     * Check if a user with the given username exists.
     */
    boolean existsByUsername(String username);

    /**
     * Id and username of every account whose name collides with an internal marker
     * (WIKI4AI-197, REV-2 P0 #4).
     * <p>
     * Projected to {@code (id, username)} rather than {@code User}: the caller only
     * reports these rows, it never edits them, and loading the entity would drag in the
     * vault columns for no reason. The lowercase comparison is what makes the check
     * match {@code Anonymous} as well as {@code anonymous}, which is the form the
     * sentinel is written in.
     * <p>
     * This exists for a startup diagnostic. Rows created BEFORE the rule was added are
     * still in the table, and they now authenticate as 401 — fail-closed, which is the
     * right outcome, but silent unless somebody is told which id to rename.
     */
    @Query("SELECT u.id, u.username FROM User u WHERE LOWER(u.username) IN :names")
    List<Object[]> findIdAndUsernameByLowercasedUsernameIn(@Param("names") Collection<String> names);

    /**
     * Find a user by email.
     */
    Optional<User> findByEmail(String email);

    /**
     * Check if a user with the given email exists.
     */
    boolean existsByEmail(String email);

    /**
     * Find users whose username or email contains the given search term (case-insensitive).
     * Supports pagination via Pageable.
     *
     * @param searchTerm the term to search for in username or email
     * @param pageable   pagination information
     * @return a page of matching users
     */
    Page<User> findByUsernameContainingIgnoreCaseOrEmailContainingIgnoreCase(String searchTerm, String searchTerm2, Pageable pageable);
}
