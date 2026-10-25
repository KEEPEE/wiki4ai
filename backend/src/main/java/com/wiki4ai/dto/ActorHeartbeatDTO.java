package com.wiki4ai.dto;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Data Transfer Object for an actor heartbeat (WIKI4AI-133).
 * Used exclusively for POST /api/v1/actors/{id}/heartbeat requests.
 * <p>
 * Both fields are optional — a heartbeat with an empty body (or no body at all)
 * is a valid "I am alive" ping. PATCH-like semantics apply when a write happens:
 * a null field leaves the current value unchanged. Inside the throttle window
 * nothing is written at all, so neither field has any effect.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ActorHeartbeatDTO {

    /** Presence status: 'idle' | 'working' | 'busy'; null = no change. */
    @Size(max = 16, message = "Presence status must be at most 16 characters")
    private String status;

    /** Optional free-text note; null = no change. Max 256 characters. */
    @Size(max = 256, message = "Presence note must be at most 256 characters")
    private String note;
}
