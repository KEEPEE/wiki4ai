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
}
