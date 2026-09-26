package com.wiki4ai.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Objects;

/**
 * JPA Entity representing a calendar event (WIKI4AI-96).
 * <p>
 * Time semantics: {@code startTime == null} means an all-day event (the date
 * only); a non-null {@code startTime} marks a precise-time event, optionally
 * bounded by {@code endTime}.
 * <p>
 * Visibility: {@code public} events are visible to every authenticated user;
 * {@code private} events only to their creator and ADMIN users.
 */
@Entity
@Table(name = "calendar_events")
@Getter
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class CalendarEvent {

    /** Visibility value: visible to every authenticated user. */
    public static final String VISIBILITY_PUBLIC = "public";

    /** Visibility value: visible only to the creator and ADMIN users. */
    public static final String VISIBILITY_PRIVATE = "private";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    /** The event type this event belongs to (event_entity_types). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_type_id", nullable = false)
    private EventType eventType;

    /** The calendar day of the event. Always set — even for all-day events. */
    @Column(name = "event_date", nullable = false)
    private LocalDate eventDate;

    /** NULL = all-day event (date only); non-null = precise start time. */
    @Column(name = "start_time")
    private LocalTime startTime;

    /** Optional precise end time (only meaningful when startTime is set). */
    @Column(name = "end_time")
    private LocalTime endTime;

    /** 'public' or 'private' (DB CHECK constraint, V13). */
    @Column(nullable = false, length = 10)
    private String visibility;

    /** The user who created the event (users table). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    private User creator;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * Set the event title.
     */
    public void setTitle(String title) {
        this.title = title;
    }

    /**
     * Set the optional description.
     */
    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * Set the event type.
     */
    public void setEventType(EventType eventType) {
        this.eventType = eventType;
    }

    /**
     * Set the calendar day of the event.
     */
    public void setEventDate(LocalDate eventDate) {
        this.eventDate = eventDate;
    }

    /**
     * Set the precise start time (null = all-day event).
     */
    public void setStartTime(LocalTime startTime) {
        this.startTime = startTime;
    }

    /**
     * Set the optional precise end time.
     */
    public void setEndTime(LocalTime endTime) {
        this.endTime = endTime;
    }

    /**
     * Set the visibility ('public' or 'private').
     */
    public void setVisibility(String visibility) {
        this.visibility = visibility;
    }

    /**
     * Set the creator of the event.
     */
    public void setCreator(User creator) {
        this.creator = creator;
    }

    /**
     * Set the id. Used mainly for testing and entity comparison.
     */
    public void setId(Long id) {
        this.id = id;
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        updatedAt = LocalDateTime.now();
        // Defensive default: the DB column has DEFAULT 'public', keep Java side consistent.
        if (visibility == null || visibility.isBlank()) {
            visibility = VISIBILITY_PUBLIC;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * Whether this event is all-day (no precise start time).
     */
    public boolean isAllDay() {
        return startTime == null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CalendarEvent that = (CalendarEvent) o;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return "CalendarEvent{" +
                "id=" + id +
                ", title='" + title + '\'' +
                ", eventDate=" + eventDate +
                ", startTime=" + startTime +
                ", endTime=" + endTime +
                ", visibility='" + visibility + '\'' +
                '}';
    }
}
