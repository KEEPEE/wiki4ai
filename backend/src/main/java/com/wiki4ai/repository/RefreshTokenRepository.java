package com.wiki4ai.repository;

import com.wiki4ai.model.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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
}
