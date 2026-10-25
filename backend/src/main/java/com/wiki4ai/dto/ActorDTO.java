package com.wiki4ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Data Transfer Object representing an actor (WIKI4AI-131).
 * Used in GET/POST/PUT /api/v1/actors responses. Mirrors the columns of the
 * V15 {@code actors} table.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ActorDTO {

    private Long id;

    /** Globally unique, normalized (lowercase) handle. */
    private String handle;

    /** Human-friendly name, or null when not set. */
    private String displayName;

    /** 'agent' | 'human' | 'service'. */
    private String kind;

    /** Id of the user who registered the actor; null for system/service actors. */
    private Long ownerUserId;

    /** Optional label for built-in/system actors. */
    private String systemLabel;

    /** Free-form capabilities text. */
    private String capabilities;

    /** Optional callback URL. */
    private String webhookUrl;

    /** Last time this actor was seen; null when never seen. */
    private LocalDateTime lastSeenAt;

    /** 'active' | 'paused' | 'archived'. */
    private String status;

    /** Presence from the last heartbeat: 'idle' | 'working' | 'busy' (WIKI4AI-133). */
    private String presenceStatus;

    /** Optional free-text note sent with the last heartbeat; null when not set. */
    private String presenceNote;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
