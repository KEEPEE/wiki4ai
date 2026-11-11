package com.wiki4ai.service;

import com.wiki4ai.dto.CalendarEventCreateDTO;
import com.wiki4ai.dto.CalendarEventDTO;
import com.wiki4ai.dto.CalendarEventUpdateDTO;
import com.wiki4ai.dto.EventTypeCreateDTO;
import com.wiki4ai.dto.EventTypeDTO;
import com.wiki4ai.exception.BadRequestException;
import com.wiki4ai.model.CalendarEvent;
import com.wiki4ai.model.EventType;
import com.wiki4ai.model.Role;
import com.wiki4ai.model.User;
import com.wiki4ai.repository.CalendarEventRepository;
import com.wiki4ai.repository.EventTypeRepository;
import com.wiki4ai.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Service layer for calendar business logic (WIKI4AI-96).
 * <p>
 * Handles CRUD of calendar events and event types, visibility rules
 * (private events are visible only to their creator and ADMIN users —
 * other users get 404 so existence is not revealed), default date range
 * (current month) and ordering (date, then time with all-day events first).
 * <p>
 * <b>Who may change what.</b> An event belongs to its creator: only the creator or an
 * ADMIN may update or delete it. An <i>event type</i> belongs to nobody and everybody —
 * one global list with no creator column — so it follows the rule for a shared resource:
 * any authenticated user may add one (additive, and the MCP's
 * {@code calendar_create_event_type} runs as an ordinary user), only an administrator may
 * remove one (WIKI4AI-197 commit 15).
 * <p>
 * Note that calendar events have no project. There is no project-scoped calendar in this
 * application, so no project permission is consulted here; the ownership and ADMIN rules
 * above are the whole model.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CalendarEventService {

    private final CalendarEventRepository calendarEventRepository;
    private final EventTypeRepository eventTypeRepository;
    private final UserRepository userRepository;
    private final EffectiveAdmin effectiveAdmin;

    // ── Current-user resolution ───────────────────────────────────────────────

    /**
     * Resolve the requesting user from the authenticated username.
     * Returns null for anonymous callers (test mode with security disabled);
     * in production all calendar endpoints require a valid JWT.
     */
    private User resolveUser(String username) {
        if (username == null || username.isBlank()
                || "anonymous".equals(username) || "anonymousUser".equals(username)) {
            return null;
        }
        return userRepository.findByUsername(username).orElse(null);
    }

    /**
     * Resolve the requesting user or reject the request (403) when no
     * authenticated user can be resolved. Used by write operations that
     * need a concrete creator/actor.
     */
    private User requireUser(String username) {
        User user = resolveUser(username);
        if (user == null) {
            throw new AccessDeniedException("Authentication required");
        }
        return user;
    }

    // ── Events ────────────────────────────────────────────────────────────────

    /**
     * List calendar events within a date range, applying visibility rules:
     * <ul>
     *   <li>ADMIN (without mine=true) → all events in the range</li>
     *   <li>regular user → public events + their own private events</li>
     *   <li>mine=true → only the requesting user's events (both visibilities)</li>
     * </ul>
     * When from/to are not provided, the current month is used as the default
     * range. The optional type filter matches the event type name
     * case-insensitively (unknown types yield an empty result). Results are
     * ordered by date, then time (all-day events first on the same day).
     */
    public List<CalendarEventDTO> listEvents(LocalDate from, LocalDate to, String typeFilter,
                                             boolean mine, String username) {
        User user = resolveUser(username);
        boolean admin = user != null && user.getRole() == Role.ADMIN;

        if (from == null || to == null) {
            YearMonth current = YearMonth.now();
            if (from == null) {
                from = current.atDay(1);
            }
            if (to == null) {
                to = current.atEndOfMonth();
            }
        }
        if (from.isAfter(to)) {
            throw new BadRequestException("Parameter 'from' must not be after parameter 'to'");
        }

        List<CalendarEvent> events;
        if (mine && user != null) {
            events = calendarEventRepository.findByCreatorIdAndEventDateBetween(user.getId(), from, to);
        } else if (admin) {
            events = calendarEventRepository.findByEventDateBetween(from, to);
        } else {
            events = calendarEventRepository.findVisibleEvents(from, to, user != null ? user.getId() : null);
        }

        if (typeFilter != null && !typeFilter.isBlank()) {
            String filter = typeFilter.trim().toLowerCase(Locale.ROOT);
            events = events.stream()
                    .filter(e -> e.getEventType() != null
                            && e.getEventType().getName() != null
                            && e.getEventType().getName().toLowerCase(Locale.ROOT).equals(filter))
                    .collect(Collectors.toList());
        }

        return events.stream()
                .sorted(Comparator.comparing(CalendarEvent::getEventDate)
                        .thenComparing(e -> e.getStartTime() == null ? 0 : 1)
                        .thenComparing(CalendarEvent::getStartTime, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    /**
     * Get a single event by id. Other users' private events are reported as
     * 404 (existence is not revealed).
     */
    public CalendarEventDTO getEvent(Long id, String username) {
        User user = resolveUser(username);
        CalendarEvent event = calendarEventRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Calendar event not found with id: " + id));
        checkVisibility(event, user);
        return convertToDTO(event);
    }

    /**
     * Create a new calendar event. The creator is the authenticated user.
     * Validation: title and date are required (400), the event type must exist
     * (400 with the list of available types), visibility must be public/private.
     */
    @Transactional
    public CalendarEventDTO createEvent(CalendarEventCreateDTO dto, String username) {
        User user = requireUser(username);

        if (dto.getTitle() == null || dto.getTitle().isBlank()) {
            throw new BadRequestException("Event title is required");
        }
        if (dto.getEventDate() == null) {
            throw new BadRequestException("Event date is required");
        }
        EventType type = requireEventType(dto.getEventType());
        validateVisibility(dto.getVisibility());

        CalendarEvent event = new CalendarEvent();
        event.setTitle(dto.getTitle().trim());
        event.setDescription(dto.getDescription());
        event.setEventType(type);
        event.setEventDate(dto.getEventDate());
        event.setStartTime(dto.getStartTime());
        event.setEndTime(dto.getEndTime());
        event.setVisibility(normalizeVisibility(dto.getVisibility()));
        event.setCreator(user);

        return convertToDTO(calendarEventRepository.save(event));
    }

    /**
     * Update an existing calendar event (PATCH-like: null fields are not
     * changed). Only the owner or an ADMIN may update; other users get 404 for
     * a foreign private event (existence not revealed) and 403 for a foreign
     * public event.
     */
    @Transactional
    public CalendarEventDTO updateEvent(Long id, CalendarEventUpdateDTO dto, String username) {
        User user = requireUser(username);
        CalendarEvent event = calendarEventRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Calendar event not found with id: " + id));
        checkOwnership(event, user, "update");

        if (dto.getTitle() != null) {
            if (dto.getTitle().isBlank()) {
                throw new BadRequestException("Event title must not be blank");
            }
            event.setTitle(dto.getTitle().trim());
        }
        if (dto.getDescription() != null) {
            event.setDescription(dto.getDescription());
        }
        if (dto.getEventType() != null && !dto.getEventType().isBlank()) {
            event.setEventType(requireEventType(dto.getEventType()));
        }
        if (dto.getEventDate() != null) {
            event.setEventDate(dto.getEventDate());
        }
        if (dto.getStartTime() != null) {
            event.setStartTime(dto.getStartTime());
        }
        if (dto.getEndTime() != null) {
            event.setEndTime(dto.getEndTime());
        }
        // WIKI4AI-97: explicit clear signal — PATCH-like semantics treat null as
        // "no change", so a timed event cannot be reverted to all-day without it.
        if (Boolean.TRUE.equals(dto.getClearTime())) {
            event.setStartTime(null);
            event.setEndTime(null);
        }
        if (dto.getVisibility() != null && !dto.getVisibility().isBlank()) {
            validateVisibility(dto.getVisibility());
            event.setVisibility(normalizeVisibility(dto.getVisibility()));
        }

        return convertToDTO(calendarEventRepository.save(event));
    }

    /**
     * Delete a calendar event. Only the owner or an ADMIN may delete; other
     * users get 404 for a foreign private event (existence not revealed) and
     * 403 for a foreign public event.
     */
    @Transactional
    public void deleteEvent(Long id, String username) {
        User user = requireUser(username);
        CalendarEvent event = calendarEventRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Calendar event not found with id: " + id));
        checkOwnership(event, user, "delete");
        calendarEventRepository.delete(event);
    }

    /**
     * Enforce the visibility rule for a single event. Public events pass for
     * everyone; private events only for their creator or an ADMIN. Other
     * callers receive EntityNotFoundException (404) so that the existence of
     * someone else's private event is not revealed.
     */
    private void checkVisibility(CalendarEvent event, User user) {
        if (!CalendarEvent.VISIBILITY_PRIVATE.equals(event.getVisibility())) {
            return;
        }
        boolean isOwner = user != null && user.getId() != null
                && user.getId().equals(event.getCreator().getId());
        boolean isAdmin = user != null && user.getRole() == Role.ADMIN;
        if (!isOwner && !isAdmin) {
            throw new EntityNotFoundException("Calendar event not found with id: " + event.getId());
        }
    }

    /**
     * Enforce the ownership rule for update/delete. Only the event's creator
     * or an ADMIN may modify the event. For anyone else: a foreign PRIVATE
     * event is reported as 404 (existence not revealed), a foreign PUBLIC
     * event as 403 Forbidden (the event exists and is visible, but this user
     * has no right to modify it).
     */
    private void checkOwnership(CalendarEvent event, User user, String action) {
        boolean isOwner = user.getId() != null && user.getId().equals(event.getCreator().getId());
        boolean isAdmin = user.getRole() == Role.ADMIN;
        if (isOwner || isAdmin) {
            return;
        }
        if (CalendarEvent.VISIBILITY_PRIVATE.equals(event.getVisibility())) {
            throw new EntityNotFoundException("Calendar event not found with id: " + event.getId());
        }
        throw new AccessDeniedException(
                "You can only " + action + " your own calendar events");
    }

    // ── Event types ───────────────────────────────────────────────────────────

    /**
     * List all event types ordered by id (seeded types first).
     */
    public List<EventTypeDTO> listEventTypes() {
        return eventTypeRepository.findAllByOrderByIdAsc().stream()
                .map(this::convertToTypeDTO)
                .collect(Collectors.toList());
    }

    /**
     * Create a new event type. Duplicate names (case-insensitive) are rejected
     * with 409 Conflict.
     *
     * <p><b>Decision (WIKI4AI-197 commit 15):</b> creating stays open to any authenticated
     * user while deleting becomes ADMIN-only. Event types are a single global vocabulary —
     * there is no per-project or per-user type — so the two operations are not symmetric:
     * a create is additive and can be cleaned up by an administrator, while a delete
     * removes a value every other user sees and, once types are in use, cannot be undone
     * at all (events referencing it block the delete with 409). Keeping create open also
     * keeps {@code calendar_create_event_type} working for agents, which run as ordinary
     * users; restricting it would turn that documented MCP tool into a 403 for everyone
     * but an administrator.</p>
     */
    @Transactional
    public EventTypeDTO createEventType(EventTypeCreateDTO dto, String username) {
        requireUser(username);

        if (dto.getName() == null || dto.getName().isBlank()) {
            throw new BadRequestException("Event type name is required");
        }
        String name = dto.getName().trim();
        if (eventTypeRepository.existsByNameIgnoreCase(name)) {
            // GlobalExceptionHandler maps IllegalArgumentException → 409 Conflict.
            throw new IllegalArgumentException("An event type with this name already exists");
        }

        EventType type = new EventType();
        type.setName(name);
        type.setColor(dto.getColor());
        return convertToTypeDTO(eventTypeRepository.save(type));
    }

    /**
     * Delete an event type. Types that are still referenced by calendar events
     * cannot be deleted (409 Conflict with the event count).
     *
     * <p>WIKI4AI-197 commit 15: this needs an administrator. Event types are global —
     * one shared list for every user — and until a type is in use any account could delete
     * it out from under everyone else, including the seeded types the UI and the MCP tool
     * descriptions name by string. Ownership is not a usable rule here either: {@code
     * EventType} records no creator, so "your own type" has no meaning and the only
     * defensible rule left is the one for a shared resource.</p>
     *
     * <p>The check goes through {@link EffectiveAdmin}, not {@code user.getRole()}, so an
     * administrator's default {@code read}+{@code write} API token cannot delete global
     * types on his behalf — the same rule the other administrative endpoints use.</p>
     */
    @Transactional
    public void deleteEventType(Long id, String username) {
        requireUser(username);
        EffectiveAdmin.Verdict admin = effectiveAdmin.check(username);
        if (!admin.allowed()) {
            throw new AccessDeniedException(admin.message());
        }
        EventType type = eventTypeRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Event type not found with id: " + id));

        long eventCount = calendarEventRepository.countByEventTypeId(id);
        if (eventCount > 0) {
            // GlobalExceptionHandler maps IllegalArgumentException → 409 Conflict.
            throw new IllegalArgumentException(
                    "Cannot delete event type '" + type.getName() + "': it is used by "
                            + eventCount + " calendar event(s)");
        }

        eventTypeRepository.delete(type);
    }

    // ── Validation helpers ────────────────────────────────────────────────────

    /**
     * Resolve an event type reference given as either the numeric id or the
     * type name (case-insensitive). Unknown references are rejected with 400
     * including the list of available types.
     */
    private EventType requireEventType(String reference) {
        if (reference == null || reference.isBlank()) {
            throw new BadRequestException("Event type is required");
        }
        String ref = reference.trim();

        EventType type = null;
        if (ref.matches("\\d+")) {
            try {
                Long id = Long.parseLong(ref);
                type = eventTypeRepository.findById(id).orElse(null);
            } catch (NumberFormatException ignored) {
                // Larger than Long.MAX_VALUE — fall through to the name lookup.
            }
        }
        if (type == null) {
            type = eventTypeRepository.findByNameIgnoreCase(ref).orElse(null);
        }

        if (type == null) {
            String available = eventTypeRepository.findAllByOrderByIdAsc().stream()
                    .map(EventType::getName)
                    .collect(Collectors.joining(", "));
            throw new BadRequestException(
                    "Unknown event type '" + ref + "'. Available types: " + available);
        }
        return type;
    }

    /**
     * Validate the visibility value (null/blank = keep default, otherwise it
     * must be 'public' or 'private', case-insensitive).
     */
    private void validateVisibility(String visibility) {
        if (visibility == null || visibility.isBlank()) {
            return;
        }
        if (!CalendarEvent.VISIBILITY_PUBLIC.equalsIgnoreCase(visibility.trim())
                && !CalendarEvent.VISIBILITY_PRIVATE.equalsIgnoreCase(visibility.trim())) {
            throw new BadRequestException(
                    "Invalid visibility '" + visibility + "'. Allowed values: 'public', 'private'");
        }
    }

    /**
     * Normalize the visibility value to lowercase; null/blank defaults to 'public'.
     */
    private String normalizeVisibility(String visibility) {
        return (visibility == null || visibility.isBlank())
                ? CalendarEvent.VISIBILITY_PUBLIC
                : visibility.trim().toLowerCase(Locale.ROOT);
    }

    // ── DTO conversion ────────────────────────────────────────────────────────

    /**
     * Convert a CalendarEvent entity to its DTO (denormalizes the type name/
     * color and the creator username).
     */
    private CalendarEventDTO convertToDTO(CalendarEvent event) {
        EventType type = event.getEventType();
        User creator = event.getCreator();
        return CalendarEventDTO.builder()
                .id(event.getId())
                .title(event.getTitle())
                .description(event.getDescription())
                .eventTypeId(type != null ? type.getId() : null)
                .eventType(type != null ? type.getName() : null)
                .eventColor(type != null ? type.getColor() : null)
                .eventDate(event.getEventDate())
                .startTime(event.getStartTime())
                .endTime(event.getEndTime())
                .visibility(event.getVisibility())
                .createdBy(creator != null ? creator.getUsername() : null)
                .createdAt(event.getCreatedAt())
                .updatedAt(event.getUpdatedAt())
                .build();
    }

    /**
     * Convert an EventType entity to its DTO.
     */
    private EventTypeDTO convertToTypeDTO(EventType type) {
        return EventTypeDTO.builder()
                .id(type.getId())
                .name(type.getName())
                .color(type.getColor())
                .createdAt(type.getCreatedAt())
                .build();
    }
}
