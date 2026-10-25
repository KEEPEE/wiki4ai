package com.wiki4ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Data Transfer Object for creating a new API token (WIKI4AI-135).
 * Used exclusively for POST /api/v1/tokens requests.
 * <p>
 * The owner is always the authenticated caller — {@code userId} is set by the
 * service, never accepted from the request body. There is deliberately no field
 * for the token itself: the secret is generated server-side and returned once.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TokenCreateRequestDTO {

    /** Human-friendly name of this token. Required. */
    @NotBlank(message = "Token name is required")
    @Size(max = 255, message = "Token name must be at most 255 characters")
    private String name;

    /** Optional expiry. Null means the token never expires. Must not be in the past. */
    private LocalDateTime expiresAt;

    /**
     * Optional scopes, e.g. {@code ["read", "write"]}. Null or empty means the
     * default scopes. An unknown scope is rejected with 400; asking for
     * {@code admin} on a token bound to an agent actor is rejected with 400 too.
     */
    private List<String> scopes;

    /**
     * Optional actor this token acts as (id from the V15 actors registry). The
     * actor must exist and belong to the caller (or be a system actor the caller
     * may not use at all) — otherwise 400.
     */
    private Long actorId;
}
