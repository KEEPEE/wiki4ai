package com.wiki4ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * DTO for token generation requests.
 * Allows specifying an optional expiration date for the generated tokens.
 * If expiresAt is null or not provided, the refresh token will have infinite lifetime.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TokenGenerationRequestDTO {

    /**
     * Optional expiration date/time for the generated tokens.
     * When null, the refresh token will never expire (infinite lifetime).
     */
    private LocalDateTime expiresAt;
}

