package com.wiki4ai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wiki4ai.dto.ProjectActorAssignDTO;
import com.wiki4ai.dto.ProjectActorDTO;
import com.wiki4ai.dto.ProjectActorUpdateRoleDTO;
import com.wiki4ai.exception.BadRequestException;
import com.wiki4ai.service.ProjectActorService;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
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
 * Web MVC tests for ProjectActorController using MockMvc (WIKI4AI-132).
 * Tests the controller layer in isolation without starting the full
 * application context — the service is mocked, so no database is involved.
 * <p>
 * Runs with SecurityAutoConfiguration excluded, exactly like the other
 * controller tests here: the request reaches the controller unauthenticated,
 * the service answers with AccessDeniedException and the global advice turns it
 * into 403.
 */
@WebMvcTest(ProjectActorController.class)
@AutoConfigureMockMvc
@ImportAutoConfiguration(exclude = {SecurityAutoConfiguration.class})
@ActiveProfiles("test")
class ProjectActorControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ProjectActorService projectActorService;

    private final LocalDateTime now = LocalDateTime.of(2026, 10, 9, 10, 0);

    private ProjectActorDTO sampleAssignment() {
        return ProjectActorDTO.builder()
                .id(11L)
                .projectId(1L)
                .actorId(7L)
                .role("viewer")
                .createdAt(now)
                .build();
    }

    // ── GET /api/v1/projects/{projectId}/actors ───────────────────────────────

    @Nested
    @DisplayName("GET /api/v1/projects/{projectId}/actors")
    class ListProjectActors {

        @Test
        @DisplayName("Returns 200 with the assignment list")
        void shouldListProjectActors() throws Exception {
            given(projectActorService.listProjectActors(eq(1L), isNull(), anyString()))
                    .willReturn(List.of(sampleAssignment()));

            mockMvc.perform(get("/api/v1/projects/1/actors"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].id").value(11))
                    .andExpect(jsonPath("$[0].projectId").value(1))
                    .andExpect(jsonPath("$[0].actorId").value(7))
                    .andExpect(jsonPath("$[0].role").value("viewer"));
        }

        @Test
        @DisplayName("Passes the role filter to the service")
        void shouldPassRoleFilter() throws Exception {
            given(projectActorService.listProjectActors(eq(1L), eq("worker"), anyString()))
                    .willReturn(List.of());

            mockMvc.perform(get("/api/v1/projects/1/actors").param("role", "worker"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isEmpty());
        }

        @Test
        @DisplayName("Returns 400 for an unknown role filter")
        void shouldRejectUnknownRoleFilter() throws Exception {
            willThrow(new BadRequestException(
                    "Invalid role 'alien'. Allowed values: 'viewer', 'worker', 'planner', 'admin'"))
                    .given(projectActorService).listProjectActors(eq(1L), eq("alien"), anyString());

            mockMvc.perform(get("/api/v1/projects/1/actors").param("role", "alien"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("Bad Request"));
        }

        @Test
        @DisplayName("Returns 404 when the project is missing")
        void shouldReturn404ForMissingProject() throws Exception {
            willThrow(new EntityNotFoundException("Project not found with id: 99"))
                    .given(projectActorService).listProjectActors(eq(99L), isNull(), anyString());

            mockMvc.perform(get("/api/v1/projects/99/actors"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("Not Found"));
        }
    }

    // ── POST /api/v1/projects/{projectId}/actors ──────────────────────────────

    @Nested
    @DisplayName("POST /api/v1/projects/{projectId}/actors")
    class AssignActor {

        @Test
        @DisplayName("Returns 201 with the created assignment")
        void shouldAssignActor() throws Exception {
            ProjectActorDTO created = sampleAssignment();
            created.setRole("worker");
            given(projectActorService.assignActor(eq(1L), any(), anyString()))
                    .willReturn(created);

            String body = objectMapper.writeValueAsString(
                    ProjectActorAssignDTO.builder().actorId(7L).role("worker").build());

            mockMvc.perform(post("/api/v1/projects/1/actors")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").value(11))
                    .andExpect(jsonPath("$.projectId").value(1))
                    .andExpect(jsonPath("$.actorId").value(7))
                    .andExpect(jsonPath("$.role").value("worker"));
        }

        @Test
        @DisplayName("Returns 400 when the actor id is missing")
        void shouldRejectMissingActorId() throws Exception {
            String body = objectMapper.writeValueAsString(
                    ProjectActorAssignDTO.builder().role("worker").build());

            mockMvc.perform(post("/api/v1/projects/1/actors")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.actorId").exists());
        }

        @Test
        @DisplayName("Returns 409 when the actor is already assigned")
        void shouldReturn409ForDuplicateAssignment() throws Exception {
            willThrow(new IllegalArgumentException("Actor 7 is already assigned to project 1"))
                    .given(projectActorService).assignActor(eq(1L), any(), anyString());

            String body = objectMapper.writeValueAsString(
                    ProjectActorAssignDTO.builder().actorId(7L).build());

            mockMvc.perform(post("/api/v1/projects/1/actors")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("Conflict"))
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("already assigned")));
        }

        @Test
        @DisplayName("Returns 400 for an unknown role")
        void shouldReturn400ForUnknownRole() throws Exception {
            willThrow(new BadRequestException(
                    "Invalid role 'alien'. Allowed values: 'viewer', 'worker', 'planner', 'admin'"))
                    .given(projectActorService).assignActor(eq(1L), any(), anyString());

            String body = objectMapper.writeValueAsString(
                    ProjectActorAssignDTO.builder().actorId(7L).role("alien").build());

            mockMvc.perform(post("/api/v1/projects/1/actors")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("Bad Request"));
        }

        @Test
        @DisplayName("Returns 400 when the actor is archived")
        void shouldReturn400ForArchivedActor() throws Exception {
            willThrow(new BadRequestException(
                    "Actor 'agent-coder' is archived and cannot be assigned to a project"))
                    .given(projectActorService).assignActor(eq(1L), any(), anyString());

            String body = objectMapper.writeValueAsString(
                    ProjectActorAssignDTO.builder().actorId(7L).build());

            mockMvc.perform(post("/api/v1/projects/1/actors")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("archived")));
        }

        @Test
        @DisplayName("Returns 404 when the actor is missing")
        void shouldReturn404ForMissingActor() throws Exception {
            willThrow(new EntityNotFoundException("Actor not found with id: 99"))
                    .given(projectActorService).assignActor(eq(1L), any(), anyString());

            String body = objectMapper.writeValueAsString(
                    ProjectActorAssignDTO.builder().actorId(99L).build());

            mockMvc.perform(post("/api/v1/projects/1/actors")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("Not Found"));
        }

        @Test
        @DisplayName("Returns 403 when the caller has no admin role for the project")
        void shouldReturn403WithoutAdminRole() throws Exception {
            willThrow(new AccessDeniedException(
                    "You can only assign actors of projects when you hold the 'admin' actor role"))
                    .given(projectActorService).assignActor(eq(1L), any(), anyString());

            String body = objectMapper.writeValueAsString(
                    ProjectActorAssignDTO.builder().actorId(7L).build());

            mockMvc.perform(post("/api/v1/projects/1/actors")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("Forbidden"));
        }

        @Test
        @DisplayName("Returns 403 for an unauthenticated caller")
        void shouldReturn403ForAnonymousCaller() throws Exception {
            willThrow(new AccessDeniedException("Authentication required"))
                    .given(projectActorService).assignActor(eq(1L), any(), anyString());

            String body = objectMapper.writeValueAsString(
                    ProjectActorAssignDTO.builder().actorId(7L).build());

            mockMvc.perform(post("/api/v1/projects/1/actors")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value("Authentication required"));
        }
    }

    // ── PUT /api/v1/projects/{projectId}/actors/{actorId} ─────────────────────

    @Nested
    @DisplayName("PUT /api/v1/projects/{projectId}/actors/{actorId}")
    class UpdateRole {

        @Test
        @DisplayName("Returns 200 with the updated role")
        void shouldUpdateRole() throws Exception {
            ProjectActorDTO planner = sampleAssignment();
            planner.setRole("planner");
            given(projectActorService.updateRole(eq(1L), eq(7L), any(), anyString()))
                    .willReturn(planner);

            String body = objectMapper.writeValueAsString(
                    ProjectActorUpdateRoleDTO.builder().role("planner").build());

            mockMvc.perform(put("/api/v1/projects/1/actors/7")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.actorId").value(7))
                    .andExpect(jsonPath("$.role").value("planner"));
        }

        @Test
        @DisplayName("Returns 400 for an unknown role")
        void shouldReturn400ForUnknownRole() throws Exception {
            willThrow(new BadRequestException(
                    "Invalid role 'alien'. Allowed values: 'viewer', 'worker', 'planner', 'admin'"))
                    .given(projectActorService).updateRole(eq(1L), eq(7L), any(), anyString());

            String body = objectMapper.writeValueAsString(
                    ProjectActorUpdateRoleDTO.builder().role("alien").build());

            mockMvc.perform(put("/api/v1/projects/1/actors/7")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("Bad Request"));
        }

        @Test
        @DisplayName("Returns 400 when the payload carries no role")
        void shouldRejectMissingRole() throws Exception {
            String body = objectMapper.writeValueAsString(ProjectActorUpdateRoleDTO.builder().build());

            mockMvc.perform(put("/api/v1/projects/1/actors/7")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.role").exists());
        }

        @Test
        @DisplayName("Returns 404 when the actor is not assigned to the project")
        void shouldReturn404ForUnassignedActor() throws Exception {
            willThrow(new EntityNotFoundException("Actor 7 is not assigned to project 1"))
                    .given(projectActorService).updateRole(eq(1L), eq(7L), any(), anyString());

            String body = objectMapper.writeValueAsString(
                    ProjectActorUpdateRoleDTO.builder().role("worker").build());

            mockMvc.perform(put("/api/v1/projects/1/actors/7")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("Not Found"));
        }

        @Test
        @DisplayName("Returns 403 when the caller has no admin role for the project")
        void shouldReturn403WithoutAdminRole() throws Exception {
            willThrow(new AccessDeniedException(
                    "You can only change actors of projects when you hold the 'admin' actor role"))
                    .given(projectActorService).updateRole(eq(1L), eq(7L), any(), anyString());

            String body = objectMapper.writeValueAsString(
                    ProjectActorUpdateRoleDTO.builder().role("admin").build());

            mockMvc.perform(put("/api/v1/projects/1/actors/7")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("Forbidden"));
        }
    }

    // ── DELETE /api/v1/projects/{projectId}/actors/{actorId} ──────────────────

    @Nested
    @DisplayName("DELETE /api/v1/projects/{projectId}/actors/{actorId}")
    class RemoveActor {

        @Test
        @DisplayName("Returns 204 on success")
        void shouldRemoveActor() throws Exception {
            mockMvc.perform(delete("/api/v1/projects/1/actors/7"))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("Returns 404 when the actor is not assigned to the project")
        void shouldReturn404ForUnassignedActor() throws Exception {
            willThrow(new EntityNotFoundException("Actor 7 is not assigned to project 1"))
                    .given(projectActorService).removeActor(eq(1L), eq(7L), anyString());

            mockMvc.perform(delete("/api/v1/projects/1/actors/7"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("Not Found"));
        }

        @Test
        @DisplayName("Returns 403 when the caller has no admin role for the project")
        void shouldReturn403WithoutAdminRole() throws Exception {
            willThrow(new AccessDeniedException(
                    "You can only remove actors of projects when you hold the 'admin' actor role"))
                    .given(projectActorService).removeActor(eq(1L), eq(7L), anyString());

            mockMvc.perform(delete("/api/v1/projects/1/actors/7"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("Forbidden"));
        }
    }
}
