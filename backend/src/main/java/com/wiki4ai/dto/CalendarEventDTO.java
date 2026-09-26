package com.wiki4ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Data Transfer Object representing a calendar event (WIKI4AI-96).
 * Used in GET/POST/PUT /api/v1/calendar/events responses.
 * <p>
 * Time semantics: {@code startTime == null} means an all-day event (date only);
 * a non-null {@code startTime} marks a precise-time event, optionally bounded
 * by {@code endTime}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CalendarEventDTO {

    private Long id;

    private String title;

    private String description;

    /** Id of the event type (event_entity_types). */
    private Long eventTypeId;

    /** Name of the event type (denormalized for client convenience). */
    private String eventType;

    /** Optional hex color of the event type, for UI rendering. */
    private String eventColor;

    /** The calendar day of the event (always set). */
    private LocalDate eventDate;

    /** NULL = all-day event; non-null = precise start time. */
    private LocalTime startTime;

    /** Optional precise end time. */
    private LocalTime endTime;

    /** 'public' or 'private'. */
    private String visibility;

    /** Username of the event creator. */
    private String createdBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
