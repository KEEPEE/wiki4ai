package com.wiki4ai.repository;

import com.wiki4ai.model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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
     * Check if a user with the given username exists.
     */
    boolean existsByUsername(String username);

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
