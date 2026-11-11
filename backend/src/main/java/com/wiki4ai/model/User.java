package com.wiki4ai.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;
import java.util.Objects;

import static jakarta.persistence.EnumType.STRING;

/**
 * JPA Entity representing a registered User in the Wiki4AI platform.
 */
@Entity
@Table(name = "users", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"username"}),
    @UniqueConstraint(columnNames = {"email"})
})
@Getter
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "username", nullable = false, unique = true)
    private String username;

    @Column(name = "email", nullable = false, unique = true)
    private String email;

    @Column(name = "password", nullable = false)
    private String password;

    @Enumerated(STRING)
    @Column(name = "role", nullable = false)
    @Builder.Default
    private Role role = Role.USER;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * WIKI4AI-73: preferred UI language of this user ("en" or "sk").
     * Defaults to 'en' (DB column default + builder default), so existing
     * accounts see the English UI until they explicitly pick otherwise.
     */
    @Column(name = "language", nullable = false, length = 5)
    @Builder.Default
    private String language = "en";

    @Column(name = "vault_master_password_hash")
    private String vaultMasterPasswordHash;

    /**
     * Base64-encoded PBKDF2 salt used by clients to derive the vault encryption key.
     * Not secret - only slows down precomputed-hash attacks - but synced server-side
     * so any authenticated client (webui, MCP) can derive the same key from the master
     * password without depending on a single browser's localStorage.
     */
    @Column(name = "vault_salt")
    private String vaultSalt;

    /**
     * WIKI4AI-197 (D1): server-generated random salt for the v2 key hierarchy, Base64.
     *
     * Write-once. The v2 key derivation is only sound while this value never changes
     * after a wrapped key exists: re-deriving the KEK from a new salt makes every
     * DEK already wrapped under the old one unrecoverable. VaultV2Service therefore
     * writes it with {@code WHERE vault_v2_salt IS NULL} and never updates it again.
     *
     * The salt itself is not the protection. What matters is that the v2 KDF takes the
     * RAW master password, never the SHA-256 of it that v1 sends to this server — a KEK
     * derived from that digest would be recomputable server-side, which is exactly what
     * v2 is meant to stop.
     */
    @Column(name = "vault_v2_salt", length = 64)
    private String vaultV2Salt;

    /**
     * WIKI4AI-197 (D1): the parameters the client needs to reproduce the KEK, as a
     * JSON string, e.g. {@code {"alg":"PBKDF2-HMAC-SHA256","iter":600000,"dkLen":256}}.
     *
     * Stored next to the salt rather than hardcoded, so a later change of the work
     * factor does not silently invalidate an existing wrapped key.
     */
    @Column(name = "vault_v2_kdf", length = 255)
    private String vaultV2Kdf;

    /**
     * WIKI4AI-197 (D1): the random data-encryption key (DEK) that actually encrypts
     * vault entries, wrapped under the KEK derived from the user's raw password.
     *
     * The server stores and returns this blob but cannot open it: opening it needs the
     * password, which the server never receives in the v2 flow. Changing the master
     * password later means re-wrapping this one value, not re-encrypting the vault.
     */
    @Column(name = "vault_v2_wrapped_key")
    private String vaultV2WrappedKey;

    /**
     * WIKI4AI-197 (D1): fingerprint of the DEK, recorded on every v2 entry so the
     * server can tell a row encrypted under this user's key from one encrypted under
     * another. It is an identifier, not a secret and not a key.
     */
    @Column(name = "vault_v2_key_id", length = 64)
    private String vaultV2KeyId;

    /**
     * One-to-one relationship with RefreshToken.
     * A user has at most one active refresh token.
     */
    @OneToOne(mappedBy = "user", cascade = {CascadeType.PERSIST, CascadeType.MERGE}, orphanRemoval = true)
    private RefreshToken refreshToken;

    /**
     * Set the username.
     */
    public void setUsername(String username) {
        this.username = username;
    }

    /**
     * Set the email.
     */
    public void setEmail(String email) {
        this.email = email;
    }

    /**
     * Set the password (hashed by caller).
     */
    public void setPassword(String password) {
        this.password = password;
    }

    /**
     * Set the role.
     */
    public void setRole(Role role) {
        this.role = role;
    }

    /**
     * Set the user id. Used mainly for testing and entity comparison.
     */
    public void setId(Long id) {
        this.id = id;
    }

    /**
     * Set the refresh token (for persistence across deploys).
     */
    public void setRefreshToken(RefreshToken refreshToken) {
        this.refreshToken = refreshToken;
    }

    /**
     * Set the vault master password hash.
     */
    public void setVaultMasterPasswordHash(String vaultMasterPasswordHash) {
        this.vaultMasterPasswordHash = vaultMasterPasswordHash;
    }

    /**
     * Set the vault encryption salt (Base64-encoded).
     */
    public void setVaultSalt(String vaultSalt) {
        this.vaultSalt = vaultSalt;
    }

    /**
     * Set the preferred UI language ("en" or "sk").
     */
    public void setLanguage(String language) {
        this.language = language;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (this.role == null) {
            this.role = Role.USER;
        }
        // WIKI4AI-73: never persist a blank language — fall back to the default.
        if (this.language == null || this.language.isBlank()) {
            this.language = "en";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        User user = (User) o;
        return id != null && Objects.equals(id, user.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return "User{" +
                "id=" + id +
                ", username='" + username + '\'' +
                ", email='" + email + '\'' +
                ", role=" + role +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                '}';
    }
}
