package com.wiki4ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * DTO for encrypted vault entries.
 * Contains only non-sensitive metadata (title, url, groupPath) and encrypted blobs.
 * The service layer never decrypts - it just stores/retrieves encrypted data.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EncryptedVaultEntryDTO {

    private Long id;

    private String title;

    private byte[] usernameEncrypted;

    private byte[] passwordEncrypted;

    private byte[] notesEncrypted;

    private String url;

    private String groupPath;

    private byte[] iv;
}
