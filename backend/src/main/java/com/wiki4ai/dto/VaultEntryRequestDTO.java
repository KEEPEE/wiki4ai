package com.wiki4ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Input DTO for creating or updating vault entries.
 * Encrypted fields are provided as Base64-encoded strings wrapped in EncryptedField objects.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VaultEntryRequestDTO {

    @NotBlank(message = "Title is required")
    @Size(max = 255, message = "Title must not exceed 255 characters")
    private String title;

    private EncryptedField usernameEncrypted;

    @NotNull(message = "Password encrypted field is required")
    private EncryptedField passwordEncrypted;

    private EncryptedField notesEncrypted;

    @Size(max = 2048, message = "URL must not exceed 2048 characters")
    private String url;

    @Size(max = 512, message = "Group path must not exceed 512 characters")
    private String groupPath;

    // ==================== WIKI4AI-197 (D2): the v2 half of a write ====================

    /**
     * Which crypto format the encrypted fields in this request are in.
     *
     * <p>{@code null} and {@code 1} mean v1 and keep the pre-D2 behaviour byte for byte,
     * which is what lets a rolled-back image and the MCP server keep writing while the
     * vault is still half v1. {@code 2} means the {@code *_v2} fields below carry the
     * data and the v1 fields are absent.
     *
     * <p>The server never chooses a format by guessing from which fields are present:
     * an absent {@code passwordEncrypted} on a v1 write is a bug, not a v2 write (see
     * {@code VaultService.validateUpdateDTO}), so the marker has to be explicit.
     */
    private Integer cryptoVersion;

    /**
     * Fingerprint of the vault key the {@code *_v2} blobs were encrypted under.
     *
     * <p>Required on a v2 write. It is not a secret and the server cannot use it to
     * decrypt anything; it is what lets the server refuse a row written under a key
     * this user's vault does not hold, instead of storing a blob nothing can read.
     */
    @Size(max = 64, message = "Key id must not exceed 64 characters")
    private String keyId;

    /**
     * Row identity the v2 AAD is built from, supplied by the client.
     *
     * <p>The id column is {@code IDENTITY}, so it does not exist before the INSERT, and
     * the AAD has to be computed before the encryption. The client therefore generates
     * the uuid and sends it. Omitting it is fine — the server generates one — but then
     * the client cannot know it before the row comes back, which only matters for a
     * row it encrypts itself.
     */
    private String entryUuid;

    /** v2 username: {@code iv(12) || ciphertext || tag}, Base64. */
    private String usernameV2;

    /** v2 password: {@code iv(12) || ciphertext || tag}, Base64. Required on a v2 write. */
    private String passwordV2;

    /** v2 notes: {@code iv(12) || ciphertext || tag}, Base64. */
    private String notesV2;

    /** True when this request writes the v2 columns rather than the v1 ones. */
    public boolean isV2Write() {
        return cryptoVersion != null && cryptoVersion == 2;
    }
}
