package com.wiki4ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Output DTO returned by vault API endpoints.
 * Contains entry metadata and encrypted fields as Base64-encoded strings.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VaultEntryResponseDTO {

    private Long id;

    private String title;

    private EncryptedField usernameEncrypted;

    private EncryptedField passwordEncrypted;

    private EncryptedField notesEncrypted;

    private String url;

    private String groupPath;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /**
     * WIKI4AI-197 (D1): which crypto format this row's bytes are in. Absent from the
     * current frontend's type, which is exactly why adding it is safe: unknown JSON
     * properties are ignored, and D2 needs the value to know which key to reach for.
     */
    private Integer cryptoVersion;

    /**
     * WIKI4AI-197 (D1): the row identity that v2 folds into its additional
     * authenticated data. A v2 client has to know it before it can read the row.
     */
    private String entryUuid;

    /**
     * WIKI4AI-197 (D1): fingerprint of the DEK the v2 fields were written under.
     */
    private String keyId;

    /**
     * WIKI4AI-197 (D1): v2 ciphertexts, Base64 of {@code iv || ciphertext || tag}.
     * Plain strings rather than {@link EncryptedField} because each field carries its
     * own IV inside its own blob — there is no shared per-entry IV to report, and
     * reusing EncryptedField would invite a client to trust the v1 {@code iv} again.
     */
    private String usernameV2;

    private String passwordV2;

    private String notesV2;

    /**
     * WIKI4AI-197 (D1): SHA-256 over this row's v1 bytes, hex.
     *
     * This is the value {@code POST /vault/v2/migrate} compares against as
     * {@code expectedV1Digest}. It is reported here so the client can echo back what
     * the server itself computed, instead of re-deriving the byte layout from the
     * encrypted fields and getting it wrong in a way that only shows up as a 409.
     *
     * Not a secret: it is a digest of ciphertexts, computable by anyone who can read
     * the row, and it says nothing about the plaintext.
     */
    private String v1Digest;
}
