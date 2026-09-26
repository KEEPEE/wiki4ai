package com.wiki4ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Data Transfer Object for creating a new calendar event type (WIKI4AI-96).
 * Used exclusively for POST /api/v1/calendar/event-types requests.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventTypeCreateDTO {

    @NotBlank(message = "Event type name is required")
    @Size(max = 100, message = "Event type name must be at most 100 characters")
    private String name;

    /** Optional hex color used by the UI (e.g. "#4f8cff"). */
    @Size(max = 20, message = "Color must be at most 20 characters")
    private String color;
}
