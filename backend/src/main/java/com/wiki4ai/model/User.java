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

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (this.role == null) {
            this.role = Role.USER;
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
