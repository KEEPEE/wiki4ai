package com.wiki4ai.service;

import com.wiki4ai.dto.CalendarEventCreateDTO;
import com.wiki4ai.dto.CalendarEventDTO;
import com.wiki4ai.dto.CalendarEventUpdateDTO;
import com.wiki4ai.dto.EventTypeCreateDTO;
import com.wiki4ai.exception.BadRequestException;
import com.wiki4ai.model.CalendarEvent;
import com.wiki4ai.model.EventType;
import com.wiki4ai.model.Role;
import com.wiki4ai.model.User;
import com.wiki4ai.repository.CalendarEventRepository;
import com.wiki4ai.repository.EventTypeRepository;
import com.wiki4ai.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for CalendarEventService using a real database (H2, WIKI4AI-96).
 * Uses @SpringBootTest with the test profile (Flyway disabled — schema from entities,
 * so the V13 seed types are created here explicitly in setUp).
 */
@SpringBootTest
@ActiveProfiles("test")
class CalendarEventServiceIntegrationTests {

    private static final String ADMIN = "cal_admin";
    private static final String ALICE = "cal_alice";
    private static final String BOB = "cal_bob";

    @Autowired
    private CalendarEventService calendarEventService;

    @Autowired
    private CalendarEventRepository calendarEventRepository;

    @Autowired
    private EventTypeRepository eventTypeRepository;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        // Clean up state from previous tests (H2 "testdb" is shared across contexts).
        calendarEventRepository.deleteAll();
        eventTypeRepository.deleteAll();
        for (String name : List.of(ADMIN, ALICE, BOB)) {
            userRepository.findByUsername(name).ifPresent(userRepository::delete);
        }

        saveUser(ADMIN, Role.ADMIN);
        saveUser(ALICE, Role.USER);
        saveUser(BOB, Role.USER);

        // Seed the same types as the V13 migration (Flyway is disabled in tests).
        eventTypeRepository.save(newType("Agent task", "#4f8cff"));
        eventTypeRepository.save(newType("Pripomienka", "#f59e0b"));
    }

    private User saveUser(String username, Role role) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@test.local");
        user.setPassword("not-a-real-hash");
        user.setRole(role);
        return userRepository.save(user);
    }

    private EventType newType(String name, String color) {
        EventType type = new EventType();
        type.setName(name);
        type.setColor(color);
        return type;
    }

    private CalendarEventCreateDTO eventDto(String title, LocalDate date, String eventType,
                                            LocalTime startTime, LocalTime endTime, String visibility) {
        return CalendarEventCreateDTO.builder()
                .title(title)
                .eventType(eventType)
                .eventDate(date)
                .startTime(startTime)
                .endTime(endTime)
                .visibility(visibility)
                .build();
    }

    private Long typeId(String name) {
        return eventTypeRepository.findByNameIgnoreCase(name).orElseThrow().getId();
    }

    // ── CRUD ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Event CRUD")
    class EventCrudTests {

        @Test
        @DisplayName("Creates a public all-day event (no startTime)")
        void shouldCreatePublicAllDayEvent() {
            LocalDate today = YearMonth.now().atDay(15);

            CalendarEventDTO created = calendarEventService.createEvent(
                    eventDto("Sprint review", today, "Agent task", null, null, null), ALICE);

            assertThat(created.getId()).isNotNull();
            assertThat(created.getTitle()).isEqualTo("Sprint review");
            assertThat(created.getVisibility()).isEqualTo("public");
            assertThat(created.getStartTime()).isNull();
            assertThat(created.getEndTime()).isNull();
            assertThat(created.getEventDate()).isEqualTo(today);
            assertThat(created.getEventType()).isEqualTo("Agent task");
            assertThat(created.getEventTypeId()).isEqualTo(typeId("Agent task"));
            assertThat(created.getEventColor()).isEqualTo("#4f8cff");
            assertThat(created.getCreatedBy()).isEqualTo(ALICE);
            assertThat(created.getCreatedAt()).isNotNull();
        }

        @Test
        @DisplayName("Creates a private event with precise start and end time")
        void shouldCreatePrivateEventWithPreciseTime() {
            LocalDate today = YearMonth.now().atDay(15);

            CalendarEventDTO created = calendarEventService.createEvent(
                    eventDto("1:1 with boss", today, "Pripomienka",
                            LocalTime.of(9, 30), LocalTime.of(10, 0), "private"), ALICE);

            assertThat(created.getVisibility()).isEqualTo("private");
            assertThat(created.getStartTime()).isEqualTo(LocalTime.of(9, 30));
            assertThat(created.getEndTime()).isEqualTo(LocalTime.of(10, 0));
        }

        @Test
        @DisplayName("Resolves the event type by id and by name (case-insensitive)")
        void shouldResolveTypeByIdAndNameCaseInsensitive() {
            LocalDate today = YearMonth.now().atDay(15);

            CalendarEventDTO byId = calendarEventService.createEvent(
                    eventDto("By id", today, String.valueOf(typeId("Agent task")), null, null, null), ALICE);
            assertThat(byId.getEventType()).isEqualTo("Agent task");

            CalendarEventDTO byName = calendarEventService.createEvent(
                    eventDto("By name", today, "aGeNt TaSk", null, null, null), ALICE);
            assertThat(byName.getEventType()).isEqualTo("Agent task");
        }

        @Test
        @DisplayName("Updates only the provided fields (PATCH-like: null = no change)")
        void shouldUpdateOnlyProvidedFields() {
            LocalDate today = YearMonth.now().atDay(15);
            CalendarEventDTO created = calendarEventService.createEvent(
                    eventDto("Original", today, "Agent task", LocalTime.of(9, 0), null, "public"), ALICE);

            CalendarEventDTO updated = calendarEventService.updateEvent(created.getId(),
                    CalendarEventUpdateDTO.builder().title("Renamed").build(), ALICE);

            assertThat(updated.getTitle()).isEqualTo("Renamed");
            // Untouched fields keep their values.
            assertThat(updated.getEventType()).isEqualTo("Agent task");
            assertThat(updated.getStartTime()).isEqualTo(LocalTime.of(9, 0));
            assertThat(updated.getVisibility()).isEqualTo("public");
        }

        @Test
        @DisplayName("Changes visibility and type on update")
        void shouldChangeVisibilityAndTypeOnUpdate() {
            LocalDate today = YearMonth.now().atDay(15);
            CalendarEventDTO created = calendarEventService.createEvent(
                    eventDto("Flip", today, "Agent task", null, null, "public"), ALICE);

            CalendarEventDTO updated = calendarEventService.updateEvent(created.getId(),
                    CalendarEventUpdateDTO.builder().eventType("Pripomienka").visibility("private").build(), ALICE);

            assertThat(updated.getVisibility()).isEqualTo("private");
            assertThat(updated.getEventType()).isEqualTo("Pripomienka");
        }

        @Test
        @DisplayName("clearTime=true reverts a timed event to all-day (WIKI4AI-97)")
        void shouldClearPreciseTimeWhenClearTimeIsTrue() {
            LocalDate today = YearMonth.now().atDay(15);
            CalendarEventDTO created = calendarEventService.createEvent(
                    eventDto("Timed", today, "Agent task", LocalTime.of(14, 0), LocalTime.of(15, 0), "public"), ALICE);

            CalendarEventDTO updated = calendarEventService.updateEvent(created.getId(),
                    CalendarEventUpdateDTO.builder().clearTime(true).build(), ALICE);

            assertThat(updated.getStartTime()).isNull();
            assertThat(updated.getEndTime()).isNull();
        }

        @Test
        @DisplayName("clearTime=false (default) leaves an existing time untouched")
        void shouldKeepTimeWhenClearTimeIsFalse() {
            LocalDate today = YearMonth.now().atDay(15);
            CalendarEventDTO created = calendarEventService.createEvent(
                    eventDto("Timed", today, "Agent task", LocalTime.of(14, 0), LocalTime.of(15, 0), "public"), ALICE);

            CalendarEventDTO updated = calendarEventService.updateEvent(created.getId(),
                    CalendarEventUpdateDTO.builder().title("Renamed").clearTime(false).build(), ALICE);

            assertThat(updated.getTitle()).isEqualTo("Renamed");
            assertThat(updated.getStartTime()).isEqualTo(LocalTime.of(14, 0));
            assertThat(updated.getEndTime()).isEqualTo(LocalTime.of(15, 0));
        }

        @Test
        @DisplayName("Deletes an event")
        void shouldDeleteEvent() {
            LocalDate today = YearMonth.now().atDay(15);
            CalendarEventDTO created = calendarEventService.createEvent(
                    eventDto("To delete", today, "Agent task", null, null, null), ALICE);

            calendarEventService.deleteEvent(created.getId(), ALICE);

            assertThat(calendarEventRepository.existsById(created.getId())).isFalse();
        }
    }

    // ── Validation ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Validation")
    class ValidationTests {

        @Test
        @DisplayName("Rejects a blank title with 400 (BadRequestException)")
        void shouldRejectBlankTitle() {
            LocalDate today = YearMonth.now().atDay(15);
            assertThatThrownBy(() -> calendarEventService.createEvent(
                    eventDto("   ", today, "Agent task", null, null, null), ALICE))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("title");
        }

        @Test
        @DisplayName("Rejects a missing date with 400 (BadRequestException)")
        void shouldRejectMissingDate() {
            assertThatThrownBy(() -> calendarEventService.createEvent(
                    eventDto("No date", null, "Agent task", null, null, null), ALICE))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("date");
        }

        @Test
        @DisplayName("Rejects an unknown type with 400 including the available types")
        void shouldRejectUnknownTypeWithAvailableList() {
            LocalDate today = YearMonth.now().atDay(15);
            assertThatThrownBy(() -> calendarEventService.createEvent(
                    eventDto("Bad type", today, "Necity", null, null, null), ALICE))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Unknown event type 'Necity'")
                    .hasMessageContaining("Agent task")
                    .hasMessageContaining("Pripomienka");
        }

        @Test
        @DisplayName("Rejects an invalid visibility value with 400")
        void shouldRejectInvalidVisibility() {
            LocalDate today = YearMonth.now().atDay(15);
            assertThatThrownBy(() -> calendarEventService.createEvent(
                    eventDto("Bad visibility", today, "Agent task", null, null, "internal"), ALICE))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("visibility");
        }

        @Test
        @DisplayName("Rejects a from date after the to date with 400")
        void shouldRejectInvertedRange() {
            assertThatThrownBy(() -> calendarEventService.listEvents(
                    LocalDate.of(2026, 5, 10), LocalDate.of(2026, 5, 1), null, false, ALICE))
                    .isInstanceOf(BadRequestException.class);
        }
    }

    // ── Visibility ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Visibility (private = owner + ADMIN only)")
    class VisibilityTests {

        private CalendarEventDTO createAlicePrivate() {
            return calendarEventService.createEvent(
                    eventDto("Alice secret", YearMonth.now().atDay(15), "Pripomienka",
                            LocalTime.of(8, 0), null, "private"), ALICE);
        }

        @Test
        @DisplayName("A foreign user does not see another user's private event in the list")
        void shouldHideForeignPrivateEventsInList() {
            CalendarEventDTO alicePrivate = createAlicePrivate();

            List<CalendarEventDTO> bobView = calendarEventService.listEvents(null, null, null, false, BOB);
            assertThat(bobView).extracting(CalendarEventDTO::getId)
                    .doesNotContain(alicePrivate.getId());

            List<CalendarEventDTO> aliceView = calendarEventService.listEvents(null, null, null, false, ALICE);
            assertThat(aliceView).extracting(CalendarEventDTO::getId)
                    .contains(alicePrivate.getId());
        }

        @Test
        @DisplayName("ADMIN sees private events of all users")
        void shouldShowAllPrivateEventsToAdmin() {
            CalendarEventDTO alicePrivate = createAlicePrivate();
            CalendarEventDTO bobPrivate = calendarEventService.createEvent(
                    eventDto("Bob secret", YearMonth.now().atDay(16), "Pripomienka", null, null, "private"), BOB);

            List<CalendarEventDTO> adminView = calendarEventService.listEvents(null, null, null, false, ADMIN);
            assertThat(adminView).extracting(CalendarEventDTO::getId)
                    .contains(alicePrivate.getId(), bobPrivate.getId());
        }

        @Test
        @DisplayName("Foreign private event detail returns 404 (existence not revealed)")
        void shouldReturn404ForForeignPrivateDetail() {
            CalendarEventDTO alicePrivate = createAlicePrivate();

            assertThatThrownBy(() -> calendarEventService.getEvent(alicePrivate.getId(), BOB))
                    .isInstanceOf(EntityNotFoundException.class);

            // Owner and admin can still read it.
            assertThat(calendarEventService.getEvent(alicePrivate.getId(), ALICE).getId()).isEqualTo(alicePrivate.getId());
            assertThat(calendarEventService.getEvent(alicePrivate.getId(), ADMIN).getId()).isEqualTo(alicePrivate.getId());
        }

        @Test
        @DisplayName("Foreign user gets 404 on update/delete of a private event; event survives")
        void shouldReturn404ForForeignPrivateUpdateAndDelete() {
            CalendarEventDTO alicePrivate = createAlicePrivate();

            assertThatThrownBy(() -> calendarEventService.updateEvent(alicePrivate.getId(),
                    CalendarEventUpdateDTO.builder().title("Hacked").build(), BOB))
                    .isInstanceOf(EntityNotFoundException.class);

            assertThatThrownBy(() -> calendarEventService.deleteEvent(alicePrivate.getId(), BOB))
                    .isInstanceOf(EntityNotFoundException.class);

            assertThat(calendarEventRepository.existsById(alicePrivate.getId())).isTrue();
        }

        @Test
        @DisplayName("Foreign user gets 403 (AccessDeniedException) on update/delete of a PUBLIC event")
        void shouldReturn403ForForeignPublicUpdateAndDelete() {
            CalendarEventDTO alicePublic = calendarEventService.createEvent(
                    eventDto("Alice public", YearMonth.now().atDay(15), "Agent task", null, null, "public"), ALICE);

            assertThatThrownBy(() -> calendarEventService.updateEvent(alicePublic.getId(),
                    CalendarEventUpdateDTO.builder().title("Hacked").build(), BOB))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);

            assertThatThrownBy(() -> calendarEventService.deleteEvent(alicePublic.getId(), BOB))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);

            // The event survives both attempts.
            assertThat(calendarEventRepository.existsById(alicePublic.getId())).isTrue();
        }

        @Test
        @DisplayName("ADMIN can update and delete a foreign private event")
        void shouldAllowAdminToModifyForeignPrivate() {
            CalendarEventDTO alicePrivate = createAlicePrivate();

            CalendarEventDTO updated = calendarEventService.updateEvent(alicePrivate.getId(),
                    CalendarEventUpdateDTO.builder().title("Admin edit").build(), ADMIN);
            assertThat(updated.getTitle()).isEqualTo("Admin edit");

            calendarEventService.deleteEvent(alicePrivate.getId(), ADMIN);
            assertThat(calendarEventRepository.existsById(alicePrivate.getId())).isFalse();
        }

        @Test
        @DisplayName("Owner can update their own private event")
        void shouldAllowOwnerToUpdateOwnPrivate() {
            CalendarEventDTO alicePrivate = createAlicePrivate();

            CalendarEventDTO updated = calendarEventService.updateEvent(alicePrivate.getId(),
                    CalendarEventUpdateDTO.builder().title("Still mine").build(), ALICE);
            assertThat(updated.getTitle()).isEqualTo("Still mine");
        }
    }

    // ── Event types ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Event types")
    class EventTypeTests {

        @Test
        @DisplayName("Lists all types ordered by id (seeds first)")
        void shouldListAllTypes() {
            List<com.wiki4ai.dto.EventTypeDTO> types = calendarEventService.listEventTypes();

            assertThat(types).extracting(com.wiki4ai.dto.EventTypeDTO::getName)
                    .containsExactly("Agent task", "Pripomienka");
        }

        @Test
        @DisplayName("Creates a new type")
        void shouldCreateEventType() {
            com.wiki4ai.dto.EventTypeDTO created = calendarEventService.createEventType(
                    EventTypeCreateDTO.builder().name("Deploy").color("#123456").build(), ALICE);

            assertThat(created.getId()).isNotNull();
            assertThat(created.getName()).isEqualTo("Deploy");
            assertThat(created.getColor()).isEqualTo("#123456");
        }

        @Test
        @DisplayName("Rejects a duplicate type name (case-insensitive) with 409 (IllegalArgumentException)")
        void shouldRejectDuplicateTypeName() {
            calendarEventService.createEventType(EventTypeCreateDTO.builder().name("Deploy").build(), ALICE);

            assertThatThrownBy(() -> calendarEventService.createEventType(
                    EventTypeCreateDTO.builder().name("deploy").build(), BOB))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("already exists");
        }

        @Test
        @DisplayName("Rejects deletion of a type that is in use (409 with the event count)")
        void shouldRejectDeleteOfUsedType() {
            Long deployId = calendarEventService.createEventType(
                    EventTypeCreateDTO.builder().name("Deploy").build(), ALICE).getId();
            calendarEventService.createEvent(
                    eventDto("Deploy run", YearMonth.now().atDay(15), "Deploy", null, null, null), ALICE);

            assertThatThrownBy(() -> calendarEventService.deleteEventType(deployId, ALICE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("used by 1");
        }

        @Test
        @DisplayName("Deletes an unused type")
        void shouldDeleteUnusedType() {
            Long tempId = calendarEventService.createEventType(
                    EventTypeCreateDTO.builder().name("Temp").build(), ALICE).getId();

            calendarEventService.deleteEventType(tempId, ALICE);

            assertThat(eventTypeRepository.existsById(tempId)).isFalse();
        }
    }

    // ── List behavior ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("List range / filter / ordering")
    class ListBehaviorTests {

        @Test
        @DisplayName("Default range is the current month (next-month events excluded)")
        void shouldDefaultToCurrentMonth() {
            LocalDate nextMonth = YearMonth.now().plusMonths(1).atDay(5);
            CalendarEventDTO future = calendarEventService.createEvent(
                    eventDto("Next month", nextMonth, "Agent task", null, null, null), ALICE);

            List<CalendarEventDTO> defaultView = calendarEventService.listEvents(null, null, null, false, ALICE);
            assertThat(defaultView).extracting(CalendarEventDTO::getId).doesNotContain(future.getId());

            LocalDate from = YearMonth.now().plusMonths(1).atDay(1);
            LocalDate to = YearMonth.now().plusMonths(1).atEndOfMonth();
            List<CalendarEventDTO> explicitView = calendarEventService.listEvents(from, to, null, false, ALICE);
            assertThat(explicitView).extracting(CalendarEventDTO::getId).contains(future.getId());
        }

        @Test
        @DisplayName("Type filter matches case-insensitively")
        void shouldFilterByTypeNameCaseInsensitive() {
            LocalDate day = YearMonth.now().atDay(15);
            calendarEventService.createEvent(eventDto("Task A", day, "Agent task", null, null, null), ALICE);
            calendarEventService.createEvent(eventDto("Note B", day, "Pripomienka", null, null, null), ALICE);

            List<CalendarEventDTO> filtered = calendarEventService.listEvents(null, null, "pripomienka", false, ALICE);

            assertThat(filtered).hasSize(1);
            assertThat(filtered.get(0).getTitle()).isEqualTo("Note B");
        }

        @Test
        @DisplayName("Unknown type filter yields an empty result")
        void shouldReturnEmptyForUnknownTypeFilter() {
            LocalDate day = YearMonth.now().atDay(15);
            calendarEventService.createEvent(eventDto("Task A", day, "Agent task", null, null, null), ALICE);

            List<CalendarEventDTO> filtered = calendarEventService.listEvents(null, null, "Necity", false, ALICE);

            assertThat(filtered).isEmpty();
        }

        @Test
        @DisplayName("Results are ordered by date, then time (all-day first)")
        void shouldOrderByDateThenTimeAllDayFirst() {
            LocalDate day = YearMonth.now().atDay(15);
            CalendarEventDTO morning = calendarEventService.createEvent(
                    eventDto("Morning", day, "Agent task", LocalTime.of(10, 0), null, null), ALICE);
            CalendarEventDTO allDay = calendarEventService.createEvent(
                    eventDto("All day", day, "Agent task", null, null, null), ALICE);
            CalendarEventDTO early = calendarEventService.createEvent(
                    eventDto("Early", day, "Agent task", LocalTime.of(9, 0), null, null), ALICE);

            List<CalendarEventDTO> view = calendarEventService.listEvents(null, null, null, false, ALICE);

            assertThat(view).extracting(CalendarEventDTO::getId)
                    .containsSubsequence(allDay.getId(), early.getId(), morning.getId());
        }

        @Test
        @DisplayName("mine=true returns only the requesting user's events")
        void shouldFilterByMine() {
            LocalDate day = YearMonth.now().atDay(15);
            calendarEventService.createEvent(eventDto("Alice public", day, "Agent task", null, null, null), ALICE);
            calendarEventService.createEvent(eventDto("Bob public", day, "Agent task", null, null, null), BOB);

            List<CalendarEventDTO> aliceMine = calendarEventService.listEvents(null, null, null, true, ALICE);

            assertThat(aliceMine).hasSize(1);
            assertThat(aliceMine.get(0).getTitle()).isEqualTo("Alice public");
        }
    }
}
