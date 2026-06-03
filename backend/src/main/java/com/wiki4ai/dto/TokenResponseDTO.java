package com.wiki4ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * DTO for API token responses.
 * Contains the generated access token and metadata (name, expiration).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TokenResponseDTO {

    /** The generated JWT access token string */
    private String accessToken;

    /** The name of this token (for user identification) */
    private String name;

    /** Optional expiration date/time. Null means the token never expires (infinite lifetime). */
    private LocalDateTime expiresAt;

    /** When the token was created */
    private LocalDateTime createdAt;

    /** Internal ID of this API token in the database */
    private Long tokenId;
}
