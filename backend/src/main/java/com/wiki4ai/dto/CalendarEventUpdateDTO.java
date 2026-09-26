package com.wiki4ai.dto;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Data Transfer Object for updating an existing calendar event (WIKI4AI-96).
 * Used exclusively for PUT /api/v1/calendar/events/{id} requests.
 * <p>
 * PATCH-like semantics: a field that is null (or absent) in the payload leaves
 * the current value unchanged; only provided fields are applied.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CalendarEventUpdateDTO {

    /** New title; null = no change. Blank values are rejected with 400. */
    @Size(max = 255, message = "Event title must be at most 255 characters")
    private String title;

    /** New description; null = no change. */
    @Size(max = 10000, message = "Description must be at most 10000 characters")
    private String description;

    /**
     * New event type reference (numeric id or name, case-insensitive);
     * null = no change. Unknown references are rejected with 400 including
     * the list of available types.
     */
    private String eventType;

    /** New calendar day; null = no change. */
    private LocalDate eventDate;

    /** New precise start time; null = no change (all-day events keep startTime null). */
    private LocalTime startTime;

    /** New precise end time; null = no change. */
    private LocalTime endTime;

    /** New visibility ('public' or 'private'); null = no change. */
    @Size(max = 10, message = "Visibility must be at most 10 characters")
    private String visibility;

    /**
     * Explicit signal to clear the precise time (WIKI4AI-97). Because PATCH-like
     * semantics treat null as "no change", a client cannot revert a timed event
     * back to all-day by sending null times. When true, both startTime and
     * endTime are set to null (all-day event); defaults to false.
     */
    private Boolean clearTime;
}
