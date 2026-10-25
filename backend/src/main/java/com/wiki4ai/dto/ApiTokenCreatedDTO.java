package com.wiki4ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Data Transfer Object returned by POST /api/v1/tokens (WIKI4AI-135).
 * <p>
 * {@code token} is the plaintext secret and this is the ONE response that ever
 * contains it: the row stores only the SHA-256 digest, so the value cannot be
 * read back later. A client that loses it has to create a new token, and the UI
 * must show it exactly once. {@code metadata} carries everything else, including
 * the display prefix.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApiTokenCreatedDTO {

    /** Plaintext token ({@code w4a_} + 32 base62 characters). Returned once. */
    private String token;

    /** Everything about the token except its secret. */
    private ApiTokenDTO metadata;
}
