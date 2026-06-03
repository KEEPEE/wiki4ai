package com.wiki4ai.repository;

import com.wiki4ai.model.ApiToken;
import jakarta.persistence.EntityManager;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for ApiToken entity operations.
 */
@Repository
public interface ApiTokenRepository extends JpaRepository<ApiToken, Long> {

    /**
     * Find all API tokens for a specific user.
     */
    List<ApiToken> findByUserId(Long userId);

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
     * Find an API token by its exact token value.
     */
    Optional<ApiToken> findByTokenValue(String tokenValue);
}
