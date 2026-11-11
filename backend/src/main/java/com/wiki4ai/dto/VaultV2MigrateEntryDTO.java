package com.wiki4ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * WIKI4AI-197 (D1): one row in a re-encryption batch — the v2 ciphertexts the client
 * produced, plus the statement of which v1 bytes they were produced from.
 *
 * <p>{@code expectedV1Digest} is what makes the batch safe to send at all. The client
 * read the row, decrypted it and re-encrypted it, and between that read and this
 * request anything could have written the row: the user's other browser, a rolled
 * back image, the MCP server. Those writers do not touch a version column, so a
 * version check would not see them. Comparing the v1 bytes does.
 *
 * <p>The v2 fields are Base64 of {@code iv || ciphertext || tag}, each field with its
 * own IV. The server never decodes them beyond re-encoding for storage.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VaultV2MigrateEntryDTO {

    @NotNull(message = "Entry id is required")
    private Long id;

    /**
     * Hex SHA-256 of the row's v1 bytes as the client saw them — the value
     * {@code GET /vault/entries} reported in {@code v1Digest}. A mismatch is a
     * conflict, never a warning.
     */
    @NotBlank(message = "Expected v1 digest is required")
    private String expectedV1Digest;

    private String usernameV2;

    /**
     * Required. The database will not accept a v2 row without a password field
     * (ck_vault_v2_complete), and a client that could not decrypt one field of a row
     * is expected to leave the whole row out of the batch rather than migrate it
     * half-done.
     */
    @NotBlank(message = "Encrypted v2 password is required")
    private String passwordV2;

    private String notesV2;
}
