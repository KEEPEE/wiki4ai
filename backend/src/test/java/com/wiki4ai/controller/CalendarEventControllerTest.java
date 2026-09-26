package com.wiki4ai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wiki4ai.dto.CalendarEventCreateDTO;
import com.wiki4ai.dto.CalendarEventDTO;
import com.wiki4ai.dto.CalendarEventUpdateDTO;
import com.wiki4ai.dto.EventTypeCreateDTO;
import com.wiki4ai.dto.EventTypeDTO;
import com.wiki4ai.service.CalendarEventService;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web MVC tests for CalendarEventController using MockMvc (WIKI4AI-96).
 * Tests the controller layer in isolation without starting the full application context.
 */
@WebMvcTest(CalendarEventController.class)
@AutoConfigureMockMvc
@ImportAutoConfiguration(exclude = {SecurityAutoConfiguration.class})
@ActiveProfiles("test")
class CalendarEventControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private CalendarEventService calendarEventService;

    private final LocalDateTime now = LocalDateTime.of(2026, 9, 15, 10, 0);

    private CalendarEventDTO sampleEvent() {
        return CalendarEventDTO.builder()
                .id(1L)
                .title("Sprint review")
                .description("Weekly sync")
                .eventTypeId(2L)
                .eventType("Agent task")
                .eventColor("#4f8cff")
                .eventDate(LocalDate.of(2026, 9, 15))
                .startTime(LocalTime.of(10, 0))
                .endTime(LocalTime.of(11, 0))
                .visibility("public")
                .createdBy("alice")
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private EventTypeDTO sampleType() {
        return EventTypeDTO.builder()
                .id(2L)
                .name("Agent task")
                .color("#4f8cff")
                .createdAt(now)
                .build();
    }

    // ── GET /events ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/v1/calendar/events")
    class ListEvents {

        @Test
        @DisplayName("Passes parsed params to the service and returns 200 with the event list")
        void shouldListEvents() throws Exception {
            given(calendarEventService.listEvents(
                    eq(LocalDate.of(2026, 9, 1)), eq(LocalDate.of(2026, 9, 30)),
                    eq("Agent task"), eq(true), anyString()))
                    .willReturn(List.of(sampleEvent()));

            mockMvc.perform(get("/api/v1/calendar/events")
                            .param("from", "2026-09-01")
                            .param("to", "2026-09-30")
                            .param("type", "Agent task")
                            .param("mine", "true"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].id").value(1))
                    .andExpect(jsonPath("$[0].title").value("Sprint review"))
                    .andExpect(jsonPath("$[0].eventType").value("Agent task"))
                    .andExpect(jsonPath("$[0].eventDate").value("2026-09-15"));
        }

        @Test
        @DisplayName("Returns 200 with an empty list when no params are given (defaults in the service)")
        void shouldListEventsWithoutParams() throws Exception {
            given(calendarEventService.listEvents(isNull(), isNull(), isNull(), eq(false), anyString()))
                    .willReturn(List.of());

            mockMvc.perform(get("/api/v1/calendar/events"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isEmpty());
        }

        @Test
        @DisplayName("Returns 400 for a malformed date parameter")
        void shouldRejectMalformedDate() throws Exception {
            mockMvc.perform(get("/api/v1/calendar/events").param("from", "not-a-date"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("Bad Request"));
        }
    }

    // ── GET /events/{id} ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/v1/calendar/events/{id}")
    class GetEvent {

        @Test
        @DisplayName("Returns 200 with the event detail")
        void shouldGetEvent() throws Exception {
            given(calendarEventService.getEvent(eq(7L), anyString())).willReturn(sampleEvent());

            mockMvc.perform(get("/api/v1/calendar/events/7"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(1))
                    .andExpect(jsonPath("$.visibility").value("public"));
        }

        @Test
        @DisplayName("Returns 404 when the event is missing or a foreign private event")
        void shouldReturn404ForMissingOrForeignPrivate() throws Exception {
            willThrow(new EntityNotFoundException("Calendar event not found with id: 7"))
                    .given(calendarEventService).getEvent(eq(7L), anyString());

            mockMvc.perform(get("/api/v1/calendar/events/7"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("Not Found"));
        }
    }

    // ── POST /events ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/v1/calendar/events")
    class CreateEvent {

        @Test
        @DisplayName("Returns 201 with the created event")
        void shouldCreateEvent() throws Exception {
            given(calendarEventService.createEvent(any(), anyString())).willReturn(sampleEvent());

            String body = objectMapper.writeValueAsString(CalendarEventCreateDTO.builder()
                    .title("Sprint review")
                    .eventType("Agent task")
                    .eventDate(LocalDate.of(2026, 9, 15))
                    .startTime(LocalTime.of(10, 0))
                    .visibility("public")
                    .build());

            mockMvc.perform(post("/api/v1/calendar/events")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").value(1))
                    .andExpect(jsonPath("$.title").value("Sprint review"));
        }

        @Test
        @DisplayName("Returns 400 when the title is missing")
        void shouldRejectMissingTitle() throws Exception {
            String body = objectMapper.writeValueAsString(CalendarEventCreateDTO.builder()
                    .eventType("Agent task")
                    .eventDate(LocalDate.of(2026, 9, 15))
                    .build());

            mockMvc.perform(post("/api/v1/calendar/events")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.title").exists());
        }

        @Test
        @DisplayName("Returns 400 when the event date is missing")
        void shouldRejectMissingDate() throws Exception {
            String body = objectMapper.writeValueAsString(CalendarEventCreateDTO.builder()
                    .title("No date")
                    .eventType("Agent task")
                    .build());

            mockMvc.perform(post("/api/v1/calendar/events")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.eventDate").exists());
        }
    }

    // ── PUT /events/{id} ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("PUT /api/v1/calendar/events/{id}")
    class UpdateEvent {

        @Test
        @DisplayName("Returns 200 with the updated event")
        void shouldUpdateEvent() throws Exception {
            given(calendarEventService.updateEvent(eq(1L), any(), anyString())).willReturn(sampleEvent());

            String body = objectMapper.writeValueAsString(CalendarEventUpdateDTO.builder()
                    .title("Renamed").build());

            mockMvc.perform(put("/api/v1/calendar/events/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(1));
        }

        @Test
        @DisplayName("Returns 404 when the event is missing or a foreign private event")
        void shouldReturn404ForMissingOrForeignPrivate() throws Exception {
            willThrow(new EntityNotFoundException("Calendar event not found with id: 9"))
                    .given(calendarEventService).updateEvent(eq(9L), any(), anyString());

            String body = objectMapper.writeValueAsString(CalendarEventUpdateDTO.builder()
                    .title("Hacked").build());

            mockMvc.perform(put("/api/v1/calendar/events/9")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("Not Found"));
        }

        @Test
        @DisplayName("Returns 403 when a non-owner tries to update a foreign public event")
        void shouldReturn403ForForeignPublicUpdate() throws Exception {
            willThrow(new org.springframework.security.access.AccessDeniedException(
                    "You can only update your own calendar events"))
                    .given(calendarEventService).updateEvent(eq(1L), any(), anyString());

            String body = objectMapper.writeValueAsString(CalendarEventUpdateDTO.builder()
                    .title("Hacked").build());

            mockMvc.perform(put("/api/v1/calendar/events/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("Forbidden"));
        }
    }

    // ── DELETE /events/{id} ───────────────────────────────────────────────────

    @Nested
    @DisplayName("DELETE /api/v1/calendar/events/{id}")
    class DeleteEvent {

        @Test
        @DisplayName("Returns 204 on success")
        void shouldDeleteEvent() throws Exception {
            mockMvc.perform(delete("/api/v1/calendar/events/1"))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("Returns 404 when the event is missing or a foreign private event")
        void shouldReturn404ForMissingOrForeignPrivate() throws Exception {
            willThrow(new EntityNotFoundException("Calendar event not found with id: 9"))
                    .given(calendarEventService).deleteEvent(eq(9L), anyString());

            mockMvc.perform(delete("/api/v1/calendar/events/9"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("Not Found"));
        }
    }

    // ── Event types ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Event types endpoints")
    class EventTypes {

        @Test
        @DisplayName("GET /event-types returns 200 with all types")
        void shouldListEventTypes() throws Exception {
            given(calendarEventService.listEventTypes()).willReturn(List.of(sampleType()));

            mockMvc.perform(get("/api/v1/calendar/event-types"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].name").value("Agent task"))
                    .andExpect(jsonPath("$[0].color").value("#4f8cff"));
        }

        @Test
        @DisplayName("POST /event-types returns 201 with the created type")
        void shouldCreateEventType() throws Exception {
            given(calendarEventService.createEventType(any(), anyString()))
                    .willReturn(EventTypeDTO.builder().id(3L).name("Deploy").color("#123456").createdAt(now).build());

            String body = objectMapper.writeValueAsString(EventTypeCreateDTO.builder()
                    .name("Deploy").color("#123456").build());

            mockMvc.perform(post("/api/v1/calendar/event-types")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.name").value("Deploy"));
        }

        @Test
        @DisplayName("POST /event-types returns 409 for a duplicate name")
        void shouldReturn409ForDuplicateType() throws Exception {
            willThrow(new IllegalArgumentException("An event type with this name already exists"))
                    .given(calendarEventService).createEventType(any(), anyString());

            String body = objectMapper.writeValueAsString(EventTypeCreateDTO.builder()
                    .name("Agent task").build());

            mockMvc.perform(post("/api/v1/calendar/event-types")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("Conflict"));
        }

        @Test
        @DisplayName("DELETE /event-types/{id} returns 204 when the type is unused")
        void shouldDeleteEventType() throws Exception {
            mockMvc.perform(delete("/api/v1/calendar/event-types/3"))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("DELETE /event-types/{id} returns 409 when the type is still in use")
        void shouldReturn409ForInUseType() throws Exception {
            willThrow(new IllegalArgumentException(
                    "Cannot delete event type 'Deploy': it is used by 2 calendar event(s)"))
                    .given(calendarEventService).deleteEventType(eq(3L), anyString());

            mockMvc.perform(delete("/api/v1/calendar/event-types/3"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("used by 2")));
        }
    }
}
