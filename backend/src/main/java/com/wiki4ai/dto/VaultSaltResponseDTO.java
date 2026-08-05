package com.wiki4ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO carrying the vault encryption salt for the current user.
 * The salt is not secret - it only strengthens PBKDF2 against precomputed-hash attacks.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VaultSaltResponseDTO {
    private String salt;
}
