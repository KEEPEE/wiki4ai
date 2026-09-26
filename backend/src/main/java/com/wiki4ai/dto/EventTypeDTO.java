package com.wiki4ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Data Transfer Object representing a calendar event type (WIKI4AI-96).
 * Used in GET/POST /api/v1/calendar/event-types responses.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventTypeDTO {

    private Long id;

    private String name;

    /** Optional hex color used by the UI (e.g. "#4f8cff"). */
    private String color;

    private LocalDateTime createdAt;
}
