package com.wiki4ai.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Data Transfer Object for updating an existing actor (WIKI4AI-131).
 * Used exclusively for PUT /api/v1/actors/{id} requests.
 * <p>
 * PATCH-like semantics: a field that is null (or absent) in the payload leaves
 * the current value unchanged; only provided fields are applied.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ActorUpdateDTO {

    /**
     * New handle; null = no change. A handle already used by a DIFFERENT actor
     * is rejected with 409 Conflict.
     */
    @Size(max = 64, message = "Actor handle must be at most 64 characters")
    @Pattern(regexp = "[A-Za-z0-9._-]+", message = "Actor handle may contain only letters, digits, dot, dash and underscore")
    private String handle;

    /** New display name; null = no change. */
    @Size(max = 128, message = "Display name must be at most 128 characters")
    private String displayName;

    /** New kind ('agent' | 'human' | 'service'); null = no change. */
    @Size(max = 16, message = "Kind must be at most 16 characters")
    private String kind;

    /** New system label; null = no change. */
    @Size(max = 128, message = "System label must be at most 128 characters")
    private String systemLabel;

    /** New capabilities text; null = no change. */
    private String capabilities;

    /** New webhook URL; null = no change. */
    @Size(max = 512, message = "Webhook URL must be at most 512 characters")
    private String webhookUrl;

    /** New last-seen timestamp (heartbeat); null = no change. */
    private LocalDateTime lastSeenAt;

    /** New status ('active' | 'paused' | 'archived'); null = no change. */
    @Size(max = 16, message = "Status must be at most 16 characters")
    private String status;
}
