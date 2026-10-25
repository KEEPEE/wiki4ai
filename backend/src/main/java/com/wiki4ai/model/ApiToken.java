package com.wiki4ai.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

/**
 * JPA Entity representing a named API token.
 * <p>
 * Two generations of tokens live in this one table (WIKI4AI-135 rework, V18):
 * <ul>
 *   <li><b>legacy</b> — a JWT stored in clear text in {@code tokenValue}, issued by
 *       {@code AuthService.generateNamedApiToken}. Still functional, still accepted
 *       by the auth filter through signature verification. Not revocable, which is
 *       exactly what the rework fixes.</li>
 *   <li><b>hashed</b> — an opaque secret ({@code w4a_} + 32 base62 characters) whose
 *       plaintext is returned once at creation and never stored. Only
 *       {@code tokenHash} (SHA-256 hex) and {@code tokenPrefix} are persisted, so a
 *       database leak cannot hand out working credentials and the filter can
 *       actually enforce {@code revokedAt} (WIKI4AI-136).</li>
 * </ul>
 * <p>
 * Uniqueness of {@code tokenValue} and {@code tokenHash} is enforced by the
 * migration, not declared on the entity: both columns must accept many NULLs so
 * legacy rows and hashed rows coexist, and Hibernate's schema validator does not
 * check constraints anyway.
 * <p>
 * Timestamps use {@link LocalDateTime} against the {@code TIMESTAMP} columns of
 * V5/V18 — the postgres profile runs with {@code ddl-auto=validate}, so a
 * time-zone column type would fail validation and stop the backend at startup.
 */
@Entity
@Table(name = "api_tokens")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApiToken {

    /** Prefix of every hashed (stateful, revocable) token. Chosen for secret scanners. */
    public static final String HASHED_TOKEN_PREFIX = "w4a_";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "name", nullable = false)
    private String name;

    /**
     * Legacy column. Clear-text JWT for the tokens issued before the V18 rework;
     * {@code null} for hashed tokens, which never store their plaintext. V18 relaxed
     * the NOT NULL so existing rows keep working.
     */
    @Column(name = "token_value")
    private String tokenValue;

    /**
     * SHA-256 of the plaintext token as 64 lowercase hex characters. {@code null}
     * for legacy rows. This is what the auth filter looks a hashed token up by.
     */
    @Column(name = "token_hash", length = 64)
    private String tokenHash;

    /**
     * First 8 characters of the random part of a hashed token (without the
     * {@code w4a_} prefix), shown in the UI so a token can be recognised without
     * being reconstructable. Always exactly 8 characters for a hashed token;
     * {@code null} for legacy rows (never an empty string — V18 puts a CHECK on
     * this column that accepts only NULL or a 8-character value).
     */
    @Column(name = "token_prefix", length = 8)
    private String tokenPrefix;

    /**
     * Space-separated scope names granted to this token (e.g. {@code "read write"}).
     * The allowed set is validated by {@code ApiTokenService} (400 for an unknown
     * scope) rather than by a DB CHECK, so adding a scope is a code change and not
     * a migration.
     */
    @Column(name = "scopes", columnDefinition = "TEXT")
    private String scopes;

    /**
     * Id of the actor this token acts as (V15 actors). Kept as a plain id rather
     * than a JPA association so the token row stays readable after the actor is
     * removed (the DB column is {@code ON DELETE SET NULL}).
     */
    @Column(name = "actor_id")
    private Long actorId;

    /**
     * Soft revoke. Non-null means the token is dead: the auth filter answers 401.
     * The row is kept (not deleted) so revocation stays auditable.
     */
    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    /**
     * Last time this token authenticated a request. Written throttled (30 s
     * window, same idea as the WIKI4AI-133 heartbeat) so authenticating a request
     * does not cost a database write every time.
     */
    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

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

    /**
     * Whether this row is a hashed (stateful, revocable) token rather than a
     * legacy clear-text JWT.
     */
    public boolean isHashed() {
        return tokenHash != null && !tokenHash.isBlank();
    }

    /**
     * Whether this token has been revoked. A revoked token must never authenticate
     * a request again (WIKI4AI-136).
     */
    public boolean isRevoked() {
        return revokedAt != null;
    }

    /**
     * Revoke this token: stamp {@code revokedAt} once and never overwrite it, so
     * the original revocation moment survives a repeated delete.
     *
     * @param at moment of the revocation
     * @return true when this call changed the row, false when it was already revoked
     */
    public boolean revoke(LocalDateTime at) {
        if (revokedAt != null) {
            return false;
        }
        revokedAt = at;
        return true;
    }

    /**
     * Record that this token just authenticated a request.
     */
    public void markUsed(LocalDateTime at) {
        lastUsedAt = at;
    }

    /**
     * Replace the granted scopes. Called by the service after validation.
     */
    public void setScopes(String scopes) {
        this.scopes = scopes;
    }

    /**
     * Attach this token to an actor.
     */
    public void setActorId(Long actorId) {
        this.actorId = actorId;
    }

    @PrePersist
    protected void onCreate() {
        // Only fill in when the caller did not provide a value, so an imported or
        // test-constructed row keeps its own creation time (same pattern as Actor).
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
