package com.wiki4ai.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

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

    /**
     * WIKI4AI-197 (D2): nullable here, because V29 dropped the {@code NOT NULL} — a v2
     * row has no v1 password to store.
     *
     * <p>The guarantee did not go away, it moved: {@code ck_vault_v1_complete} still
     * refuses a v1 row without a password ciphertext, and says so per version rather
     * than unconditionally. What the mapping does decide is the H2 schema the tests
     * build, and leaving {@code nullable = false} here made the test database stricter
     * than production — a v2 write inserts fine on Postgres and fails on H2, which is
     * the least useful possible way to be inconsistent.
     */
    @Column(name = "password_encrypted")
    private byte[] passwordEncrypted;

    @Column(name = "notes_encrypted")
    private byte[] notesEncrypted;

    @Column(name = "url", length = 2048)
    private String url;

    @Column(name = "group_path", length = 512)
    private String groupPath;

    /**
     * The v1 initialisation vector, shared by all three v1 fields of a row. Nullable
     * since V29 for the same reason as {@code passwordEncrypted}, and never reused by
     * v2, which folds a per-field IV into each blob.
     */
    @Column(name = "iv")
    private byte[] iv;

    /**
     * WIKI4AI-197 (D1): which crypto format the bytes on THIS row are in — 1 for the
     * original client-side AES-GCM, 2 for the v2 key hierarchy.
     *
     * Per row and not per user, because users unlock at different times and the
     * re-encryption is done by the client, so a vault is legitimately a mix of both
     * for as long as D1 and D2 coexist. It is monotonic: 2 never goes back to 1, and
     * the database enforces that (V29 trigger) because the v1 copy of a v2 row may
     * already be gone by the time anyone would ask.
     *
     * INTEGER, not Short: the column is INTEGER and ddl-auto: validate rejects int2
     * behind an Integer field, which would stop the backend from starting. H2 builds
     * its test schema from this class, so only the postgres profile can catch it.
     */
    @Column(name = "crypto_version", nullable = false)
    @Builder.Default
    private Integer cryptoVersion = 1;

    /**
     * WIKI4AI-197 (D1): stable identity of the row, generated here rather than by the
     * database because v2 folds it into the additional authenticated data — the value
     * has to exist before the bytes are encrypted, and the IDENTITY primary key does
     * not exist until the INSERT has run.
     *
     * Assigned on the builder, so every entry this application creates carries one.
     * V29 still gives the column a {@code DEFAULT gen_random_uuid()} for the INSERTs
     * that never mention it at all, which is what a rolled-back image does: its
     * version of this class has no such field.
     *
     * Not updatable: the AAD is computed over this value, so changing it would make
     * the stored ciphertext unreadable rather than merely renamed.
     */
    @Column(name = "entry_uuid", nullable = false, updatable = false)
    @Builder.Default
    private UUID entryUuid = UUID.randomUUID();

    /**
     * WIKI4AI-197 (D1): fingerprint of the DEK the v2 fields were encrypted under.
     * Null on v1 rows. The server compares it against the user's current key so a
     * request cannot claim a row belongs to a key it was not written under.
     */
    @Column(name = "key_id", length = 64)
    private String keyId;

    /**
     * WIKI4AI-197 (D1): v2 ciphertexts, one column per field, each with its own IV
     * folded in. Deliberately separate columns from the v1 ones — see the header of
     * V29__vault_crypto_v2.sql for why reusing them loses data on rollback.
     *
     * The server never decrypts these and never will: they are wrapped under a key
     * the server is not given.
     */
    @Column(name = "username_v2")
    private byte[] usernameV2;

    @Column(name = "password_v2")
    private byte[] passwordV2;

    @Column(name = "notes_v2")
    private byte[] notesV2;

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

    // ==================== WIKI4AI-197 (D2): the v2 write path ====================

    /**
     * Set the v2 username ciphertext.
     *
     * <p>Setters exist for the v2 columns because a v2 write goes through JPA, unlike
     * the re-encryption batch in {@code VaultV2Service}, which deliberately does not.
     * A user edit has to move {@code updated_at} — the user really did change the row —
     * and that is exactly what the {@code @PreUpdate} below is for.
     */
    public void setUsernameV2(byte[] usernameV2) {
        this.usernameV2 = usernameV2;
    }

    public void setPasswordV2(byte[] passwordV2) {
        this.passwordV2 = passwordV2;
    }

    public void setNotesV2(byte[] notesV2) {
        this.notesV2 = notesV2;
    }

    public void setKeyId(String keyId) {
        this.keyId = keyId;
    }

    /**
     * Move the row to crypto version 2.
     *
     * <p>There is no setter that goes the other way on purpose: 1 → 2 is the only
     * direction, and the V29 trigger refuses the reverse even for a writer that
     * bypasses this class.
     */
    public void markV2() {
        this.cryptoVersion = 2;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    /**
     * WIKI4AI-197 (D1): this callback is why the v2 re-encryption does NOT go through
     * {@code save()}. A batch that re-encrypts a whole vault under a new key changes
     * no user-visible field, yet a managed-entity flush would run through here and
     * stamp "last modified" onto every row in it. VaultV2Service writes those columns
     * with native SQL, which never loads an entity and so never fires this callback.
     */
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
