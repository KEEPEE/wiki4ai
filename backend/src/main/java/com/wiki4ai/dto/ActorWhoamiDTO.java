package com.wiki4ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response of {@code GET /api/v1/actors/whoami} (WIKI4AI-134): the identity of
 * the caller, as the verified credential describes it.
 * <p>
 * Nothing here is taken from the request — no body, no parameter, no header. The
 * user comes from the authenticated principal, the actor from the API token's
 * details. A caller whose credential names no actor (a legacy JWT, or a token
 * with no actor bound) gets {@code actorId} and {@code handle} null: that is a
 * valid answer, not an error.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ActorWhoamiDTO {

    /** Id of the authenticated user, or null when it cannot be resolved. */
    private Long userId;

    /** Authenticated principal name. */
    private String username;

    /**
     * Actor the caller acts as, or null when the caller carries no actor.
     * Read-only: it is the id the token names, never a value the client sent.
     */
    private Long actorId;

    /** Handle of that actor, or null when there is no actor. */
    private String handle;

    /** Kind of that actor ('agent' | 'human' | 'service'), or null. */
    private String kind;

    /** Scopes of the token used for this call. Empty for a legacy JWT. */
    private List<String> scopes;
}
