package com.wiki4ai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Wrapper for an encrypted field containing ciphertext, IV, and optional salt.
 * All values are Base64-encoded strings for JSON serialization.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EncryptedField {

    @NotBlank(message = "Ciphertext is required")
    private String ciphertext;

    @NotBlank(message = "IV is required")
    private String iv;

    private String salt;
}
