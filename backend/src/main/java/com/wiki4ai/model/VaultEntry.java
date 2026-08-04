package com.wiki4ai.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(name = "vault_entries", indexes = {
    @Index(name = "idx_vault_user_id", columnList = "user_id"),
    @Index(name = "idx_vault_group_path", columnList = "group_path")
})
@Getter
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class VaultEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "username_encrypted")
    private byte[] usernameEncrypted;

    @Column(name = "password_encrypted", nullable = false)
    private byte[] passwordEncrypted;

    @Column(name = "notes_encrypted")
    private byte[] notesEncrypted;

    @Column(name = "url", length = 2048)
    private String url;

    @Column(name = "group_path", length = 512)
    private String groupPath;

    @Column(name = "iv", nullable = false)
    private byte[] iv;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public void setId(Long id) {
        this.id = id;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public void setUsernameEncrypted(byte[] usernameEncrypted) {
        this.usernameEncrypted = usernameEncrypted;
    }

    public void setPasswordEncrypted(byte[] passwordEncrypted) {
        this.passwordEncrypted = passwordEncrypted;
    }

    public void setNotesEncrypted(byte[] notesEncrypted) {
        this.notesEncrypted = notesEncrypted;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public void setGroupPath(String groupPath) {
        this.groupPath = groupPath;
    }

    public void setIv(byte[] iv) {
        this.iv = iv;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        VaultEntry that = (VaultEntry) o;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return "VaultEntry{" +
                "id=" + id +
                ", userId=" + userId +
                ", title='" + title + '\'' +
                ", groupPath='" + groupPath + '\'' +
                ", url='" + url + '\'' +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                '}';
    }
}
