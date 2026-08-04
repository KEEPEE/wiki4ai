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
}
