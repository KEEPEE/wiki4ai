package com.wiki4ai.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

/**
 * JPA Entity representing a named API token (JWT access token) for the user.
 * <p>
 * Unlike RefreshToken which is used for re-authentication, ApiToken represents
 * a generated JWT access token that users can name and manage independently.
 * Each token has an optional expiration date — when null, it never expires.
 */
@Entity
@Table(name = "api_tokens")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApiToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "token_value", nullable = false, unique = true)
    private String tokenValue;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Check if this API token has expired.
     * Returns false when expiresAt is null (infinite-expiry tokens never expire).
     */
    public boolean isExpired() {
        if (expiresAt == null) {
            return false; // Infinite-expiry tokens never expire
        }
        return LocalDateTime.now().isAfter(expiresAt);
    }

    /**
     * Check if this API token has an infinite lifetime (no expiration).
     */
    public boolean isInfinite() {
        return expiresAt == null;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
