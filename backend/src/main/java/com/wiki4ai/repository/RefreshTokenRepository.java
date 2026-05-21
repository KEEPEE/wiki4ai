package com.wiki4ai.repository;

import com.wiki4ai.model.RefreshToken;
import com.wiki4ai.model.User;
import jakarta.persistence.EntityManager;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Repository for RefreshToken entity operations.
 */
@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    /**
     * Find a refresh token by its exact token string.
     */
    Optional<RefreshToken> findByToken(String token);

    /**
     * Check if a refresh token exists for the given user.
     */
    boolean existsByUserId(Long userId);

    /**
     * Find a refresh token by the associated User entity.
     */
    Optional<RefreshToken> findByUser(User user);

    /**
     * Delete all refresh tokens for the given user ID using explicit JPQL query.
     * This ensures proper transaction participation (derived delete methods can fail
     * with TransactionRequiredException in certain Spring configurations).
     */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRED)
    @Query("DELETE FROM RefreshToken r WHERE r.user.id = :userId")
    int deleteByUserIdExplicit(Long userId);

    /**
     * Delete refresh token by user ID using EntityManager directly.
     * This bypasses Spring Data JPA's derived method limitations for transactions.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    default void deleteByUserIdViaEntityManager(EntityManager em, Long userId) {
        em.createNativeQuery("DELETE FROM refresh_tokens WHERE user_id = :userId")
            .setParameter("userId", userId)
            .executeUpdate();
    }
}
