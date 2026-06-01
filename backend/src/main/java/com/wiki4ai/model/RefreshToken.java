package com.wiki4ai.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

/**
 * JPA Entity representing a persisted Refresh Token.
 * <p>
 * Refresh tokens are stored in the database so they survive across deploys.
 * Each user has at most one active refresh token (the latest one generated).
 */
@Entity
@Table(name = "refresh_tokens")
@Getter
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(name = "token", nullable = false, unique = true)
    private String token;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Check if this refresh token has expired.
     * Returns false when expiresAt is null (infinite-expiry tokens never expire).
     */
    public boolean isExpired() {
        if (expiresAt == null) {
            return false; // Infinite-expiry tokens never expire
        }
        return LocalDateTime.now().isAfter(expiresAt);
    }

    /**
     * Check if this refresh token has an infinite lifetime (no expiration).
     */
    public boolean isInfinite() {
        return expiresAt == null;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
