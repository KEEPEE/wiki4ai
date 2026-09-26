package com.wiki4ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Data Transfer Object for creating a new calendar event (WIKI4AI-96).
 * Used exclusively for POST /api/v1/calendar/events requests.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CalendarEventCreateDTO {

    @NotBlank(message = "Event title is required")
    @Size(max = 255, message = "Event title must be at most 255 characters")
    private String title;

    @Size(max = 10000, message = "Description must be at most 10000 characters")
    private String description;

    /**
     * Event type reference: either the numeric type id or the type name
     * (matched case-insensitively). Unknown references are rejected with 400
     * including the list of available types.
     */
    @NotBlank(message = "Event type is required")
    private String eventType;

    @NotNull(message = "Event date is required")
    private LocalDate eventDate;

    /** Optional precise start time; null/absent = all-day event (date only). */
    private LocalTime startTime;

    /** Optional precise end time (only meaningful when startTime is set). */
    private LocalTime endTime;

    /** 'public' (default) or 'private'. */
    @Size(max = 10, message = "Visibility must be at most 10 characters")
    private String visibility;
}
