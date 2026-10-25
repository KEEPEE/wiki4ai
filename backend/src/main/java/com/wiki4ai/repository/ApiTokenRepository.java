package com.wiki4ai.repository;

import com.wiki4ai.model.ApiToken;
import jakarta.persistence.EntityManager;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository for ApiToken entity operations.
 * <p>
 * WIKI4AI-135 added the hashed-token lookups. All queries stay plain derived
 * queries / JPQL — no {@code RETURNING}, no {@code ON CONFLICT} — so they remain
 * portable to the H2 test database.
 */
@Repository
public interface ApiTokenRepository extends JpaRepository<ApiToken, Long> {

    /**
     * Find all API tokens for a specific user.
     */
    List<ApiToken> findByUserId(Long userId);

    /**
     * All tokens of a user, newest first — the order the token-management UI lists.
     */
    List<ApiToken> findByUserIdOrderByCreatedAtDesc(Long userId);

    /**
     * Check if an API token exists with the given token value.
     */
    boolean existsByTokenValue(String tokenValue);

    /**
     * Delete all API tokens for the given user ID using explicit JPQL query.
     */
    @Query("DELETE FROM ApiToken a WHERE a.user.id = :userId")
    void deleteByUserIdExplicit(Long userId);

    /**
     * Find an API token by its exact token value (legacy clear-text JWT).
     */
    Optional<ApiToken> findByTokenValue(String tokenValue);

    /**
     * Find a hashed token by its SHA-256 hex digest. This is the lookup the auth
     * filter performs for a {@code w4a_} token (WIKI4AI-136) — the plaintext is
     * never stored, so the hash is the only searchable form.
     */
    Optional<ApiToken> findByTokenHash(String tokenHash);

    /**
     * All tokens issued for a given actor.
     */
    List<ApiToken> findByActorId(Long actorId);

    /**
     * Throttled {@code last_used_at} update (WIKI4AI-136).
     * <p>
     * The throttle lives in the WHERE clause instead of a read-modify-write in the
     * service: a row whose {@code last_used_at} is already newer than
     * {@code throttleBefore} is not touched at all, so authenticating requests
     * inside the window costs zero writes and concurrent instances cannot fight
     * over the same row. Same idea as the WIKI4AI-133 heartbeat window.
     *
     * @return 1 when the timestamp was written, 0 when throttling skipped the write
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ApiToken a SET a.lastUsedAt = :now WHERE a.id = :id "
            + "AND (a.lastUsedAt IS NULL OR a.lastUsedAt < :throttleBefore)")
    int touchLastUsedThrottled(@Param("id") Long id,
                               @Param("now") LocalDateTime now,
                               @Param("throttleBefore") LocalDateTime throttleBefore);
}
