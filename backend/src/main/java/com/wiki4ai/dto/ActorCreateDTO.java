package com.wiki4ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Data Transfer Object for creating a new actor (WIKI4AI-131).
 * Used exclusively for POST /api/v1/actors requests.
 * <p>
 * The owner is always the authenticated caller — {@code ownerUserId} is set by
 * the service, never accepted from the request body.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ActorCreateDTO {

    /**
     * Globally unique handle. Lowercase/uppercase letters, digits, dot, dash
     * and underscore. The service normalizes it to lowercase; a handle that is
     * already taken is rejected with 409 Conflict.
     */
    @NotBlank(message = "Actor handle is required")
    @Size(max = 64, message = "Actor handle must be at most 64 characters")
    @Pattern(regexp = "[A-Za-z0-9._-]+", message = "Actor handle may contain only letters, digits, dot, dash and underscore")
    private String handle;

    /** Optional human-friendly name. */
    @Size(max = 128, message = "Display name must be at most 128 characters")
    private String displayName;

    /** 'agent' (default) | 'human' | 'service'. */
    @Size(max = 16, message = "Kind must be at most 16 characters")
    private String kind;

    /** Optional label for built-in/system actors. */
    @Size(max = 128, message = "System label must be at most 128 characters")
    private String systemLabel;

    /** Free-form capabilities (comma-separated tags or JSON). */
    private String capabilities;

    /** Optional callback URL. */
    @Size(max = 512, message = "Webhook URL must be at most 512 characters")
    private String webhookUrl;

    /** 'active' (default) | 'paused' | 'archived'. */
    @Size(max = 16, message = "Status must be at most 16 characters")
    private String status;
}
