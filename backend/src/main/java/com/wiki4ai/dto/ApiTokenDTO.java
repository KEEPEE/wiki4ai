package com.wiki4ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Data Transfer Object describing an API token WITHOUT its secret (WIKI4AI-135).
 * <p>
 * This is the shape every read endpoint returns. It carries neither the plaintext
 * token nor the hash: the plaintext exists only in the creation response and the
 * hash is a lookup key, not something a client needs. {@code prefix} is what the
 * UI shows instead — the first 8 characters of the random part, enough to
 * recognise a token, useless for reconstructing it.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApiTokenDTO {

    /** Internal id of the token row. */
    private Long id;

    /** Name given by the owner. */
    private String name;

    /** First 8 characters of the token body (without the w4a_ prefix). Null for legacy tokens. */
    private String prefix;

    /** Granted scopes. Null for legacy tokens, which have no scope concept. */
    private List<String> scopes;

    /** Actor this token acts as. Null for legacy tokens and unbound tokens. */
    private Long actorId;

    /** Expiry, or null for a token that never expires. */
    private LocalDateTime expiresAt;

    /** When the token was revoked. Null while it is still usable. */
    private LocalDateTime revokedAt;

    /** Last time this token authenticated a request (throttled, so it can lag). */
    private LocalDateTime lastUsedAt;

    /** Creation time. */
    private LocalDateTime createdAt;

    /**
     * Whether this row is a hashed (stateful, revocable) token or a legacy
     * clear-text JWT. Lets the UI explain why an old token cannot be revoked.
     */
    private boolean hashed;
}
