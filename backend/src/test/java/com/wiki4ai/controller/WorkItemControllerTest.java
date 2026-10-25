package com.wiki4ai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wiki4ai.dto.WorkItemClaimDTO;
import com.wiki4ai.dto.WorkItemDTO;
import com.wiki4ai.dto.WorkItemRequestDTO;
import com.wiki4ai.exception.BadRequestException;
import com.wiki4ai.model.WorkItem;
import com.wiki4ai.service.WorkItemService;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web MVC tests for {@link WorkItemController} (WIKI4AI-140 / WIKI4AI-141).
 * <p>
 * The service is mocked, so this slice tests only what the controller is
 * responsible for: the routes, the query parameters, the status codes the service
 * exceptions arrive as, and the shape of the claim response. Runs with
 * {@code SecurityAutoConfiguration} excluded like the other controller tests
 * here: the request reaches the controller unauthenticated, the service answers
 * with AccessDeniedException and {@code GlobalExceptionHandler} turns it into 403.
 * <p>
 * The real claim race, the state machine and the attribution are covered by
 * {@code WorkItemServiceTest} and {@code WorkItemClaimIntegrationTest}.
 */
@WebMvcTest(WorkItemController.class)
@AutoConfigureMockMvc
@ImportAutoConfiguration(exclude = {SecurityAutoConfiguration.class})
@ActiveProfiles("test")
class WorkItemControllerTest {

    private static final String CLAIM_TOKEN = "0".repeat(63) + "a";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private WorkItemService workItemService;

    private final LocalDateTime createdAt = LocalDateTime.of(2026, 10, 9, 10, 0);
    private final LocalDateTime leaseExpiresAt = LocalDateTime.of(2026, 10, 9, 10, 15);

    private WorkItemDTO item(Long id, String status) {
        return WorkItemDTO.builder()
                .id(id)
                .projectId(1L)
                .title("Implement atomic claim")
                .body("UPDATE ... WHERE status = 'ready'")
                .status(status)
                .priority("medium")
                .labels(List.of("backend"))
                .externalRef("WIKI4AI-141")
                .createdByUserId(10L)
                .createdByActorId(7L)
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .version(0)
                .build();
    }

    private WorkItemClaimDTO claim() {
        return WorkItemClaimDTO.builder()
                .id(1L)
                .status(WorkItem.STATUS_CLAIMED)
                .assigneeActorId(7L)
                .claimToken(CLAIM_TOKEN)
                .leaseExpiresAt(leaseExpiresAt)
                .version(1)
                .updatedAt(leaseExpiresAt)
                .build();
    }

    // ── GET /api/v1/work-items ────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/v1/work-items")
    class ListItems {

        @Test
        @DisplayName("returns 200 with the items")
        void returnsItems() throws Exception {
            given(workItemService.listWorkItems(eq(1L), isNull(), anyString()))
                    .willReturn(List.of(item(1L, WorkItem.STATUS_BACKLOG)));

            mockMvc.perform(get("/api/v1/work-items").param("projectId", "1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)))
                    .andExpect(jsonPath("$[0].id").value(1))
                    .andExpect(jsonPath("$[0].status").value("backlog"))
                    .andExpect(jsonPath("$[0].labels[0]").value("backend"));
        }

        @Test
        @DisplayName("passes the status filter through to the service")
        void passesStatusFilter() throws Exception {
            given(workItemService.listWorkItems(eq(1L), eq("ready"), anyString()))
                    .willReturn(List.of(item(1L, WorkItem.STATUS_READY)));

            mockMvc.perform(get("/api/v1/work-items")
                            .param("projectId", "1")
                            .param("status", "ready"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].status").value("ready"));

            verify(workItemService).listWorkItems(1L, "ready", "anonymous");
        }

        @Test
        @DisplayName("an unknown status filter is 400, not an empty 200")
        void unknownStatusFilterIsBadRequest() throws Exception {
            willThrow(new BadRequestException("Unknown status 'nope'. Allowed values: backlog, ready"))
                    .given(workItemService).listWorkItems(eq(1L), eq("nope"), anyString());

            mockMvc.perform(get("/api/v1/work-items").param("projectId", "1").param("status", "nope"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("nope")));
        }

        @Test
        @DisplayName("a foreign private project is 404")
        void privateProjectIsNotFound() throws Exception {
            willThrow(new EntityNotFoundException("Project not found with id: 1"))
                    .given(workItemService).listWorkItems(eq(1L), isNull(), anyString());

            mockMvc.perform(get("/api/v1/work-items").param("projectId", "1"))
                    .andExpect(status().isNotFound());
        }
    }

    // ── GET /api/v1/work-items/{id} ───────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/v1/work-items/{id}")
    class GetItem {

        @Test
        @DisplayName("returns 200 with the item")
        void returnsItem() throws Exception {
            given(workItemService.getWorkItem(eq(1L), anyString())).willReturn(item(1L, WorkItem.STATUS_READY));

            mockMvc.perform(get("/api/v1/work-items/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(1))
                    .andExpect(jsonPath("$.status").value("ready"))
                    .andExpect(jsonPath("$.createdByActorId").value(7))
                    .andExpect(jsonPath("$.version").value(0));
        }

        @Test
        @DisplayName("an unknown id is 404")
        void unknownIdIsNotFound() throws Exception {
            willThrow(new EntityNotFoundException("Work item not found with id: 999"))
                    .given(workItemService).getWorkItem(eq(999L), anyString());

            mockMvc.perform(get("/api/v1/work-items/999"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("999")));
        }

        @Test
        @DisplayName("an item of a foreign private project is 404, not 403")
        void foreignPrivateItemIsNotFound() throws Exception {
            willThrow(new EntityNotFoundException("Project not found with id: 1"))
                    .given(workItemService).getWorkItem(eq(1L), anyString());

            mockMvc.perform(get("/api/v1/work-items/1"))
                    .andExpect(status().isNotFound());
        }
    }

    // ── POST /api/v1/work-items ───────────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/v1/work-items")
    class CreateItem {

        @Test
        @DisplayName("returns 201 with the created item")
        void createsItem() throws Exception {
            given(workItemService.createWorkItem(any(WorkItemRequestDTO.class), anyString()))
                    .willReturn(item(1L, WorkItem.STATUS_BACKLOG));

            mockMvc.perform(post("/api/v1/work-items")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"projectId\":1,\"title\":\"Implement atomic claim\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").value(1))
                    .andExpect(jsonPath("$.status").value("backlog"));
        }

        @Test
        @DisplayName("a missing projectId is 400")
        void missingProjectIdIsBadRequest() throws Exception {
            willThrow(new BadRequestException("Parameter 'projectId' is required"))
                    .given(workItemService).createWorkItem(any(WorkItemRequestDTO.class), anyString());

            mockMvc.perform(post("/api/v1/work-items")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"No project\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("projectId")));
        }

        @Test
        @DisplayName("status 'claimed' on create is 400")
        void claimedOnCreateIsBadRequest() throws Exception {
            willThrow(new BadRequestException(
                    "Status 'claimed' cannot be set on create; claim the item with POST /api/v1/work-items/{id}/claim"))
                    .given(workItemService).createWorkItem(any(WorkItemRequestDTO.class), anyString());

            mockMvc.perform(post("/api/v1/work-items")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"projectId\":1,\"title\":\"X\",\"status\":\"claimed\"}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("a duplicate externalRef is 409")
        void duplicateExternalRefIsConflict() throws Exception {
            willThrow(new IllegalArgumentException("A work item with external reference 'WIKI4AI-141' already exists"))
                    .given(workItemService).createWorkItem(any(WorkItemRequestDTO.class), anyString());

            mockMvc.perform(post("/api/v1/work-items")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"projectId\":1,\"title\":\"X\",\"externalRef\":\"WIKI4AI-141\"}"))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("no write permission on the project is 403")
        void withoutPermissionIsForbidden() throws Exception {
            willThrow(new AccessDeniedException("You can only create work items in projects you own"))
                    .given(workItemService).createWorkItem(any(WorkItemRequestDTO.class), anyString());

            mockMvc.perform(post("/api/v1/work-items")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"projectId\":1,\"title\":\"X\"}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("AC: attribution and claim fields sent in the body never reach the service")
        void bodyCannotNameTheActor() throws Exception {
            given(workItemService.createWorkItem(any(WorkItemRequestDTO.class), anyString()))
                    .willReturn(item(1L, WorkItem.STATUS_BACKLOG));

            mockMvc.perform(post("/api/v1/work-items")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "projectId", 1,
                                    "title", "Injected",
                                    "createdByActorId", 999,
                                    "updatedByActorId", 999,
                                    "assigneeActorId", 999,
                                    "claimToken", "forged",
                                    "leaseExpiresAt", "2026-10-09T10:15:00",
                                    "version", 42))))
                    .andExpect(status().isCreated());

            ArgumentCaptor<WorkItemRequestDTO> sent = ArgumentCaptor.forClass(WorkItemRequestDTO.class);
            verify(workItemService).createWorkItem(sent.capture(), anyString());
            WorkItemRequestDTO payload = sent.getValue();

            assertThat(payload.getProjectId()).isEqualTo(1L);
            assertThat(payload.getTitle()).isEqualTo("Injected");

            // Nothing the client injected survived binding: the request DTO has no
            // field an actor id, a claim token or a version could land in.
            for (java.lang.reflect.Field field : WorkItemRequestDTO.class.getDeclaredFields()) {
                field.setAccessible(true);
                Object value = field.get(payload);
                assertThat(value)
                        .as("field '%s' must not carry a client-supplied attribution or claim value", field.getName())
                        .isNotEqualTo(999L)
                        .isNotEqualTo("forged")
                        .isNotEqualTo(42);
            }
        }
    }

    // ── PUT /api/v1/work-items/{id} ───────────────────────────────────────────

    @Nested
    @DisplayName("PUT /api/v1/work-items/{id}")
    class UpdateItem {

        @Test
        @DisplayName("returns 200 with the updated item")
        void updatesItem() throws Exception {
            given(workItemService.updateWorkItem(eq(1L), any(WorkItemRequestDTO.class), anyString()))
                    .willReturn(item(1L, WorkItem.STATUS_READY));

            mockMvc.perform(put("/api/v1/work-items/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"ready\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("ready"));
        }

        @Test
        @DisplayName("AC: an illegal status transition is 409")
        void illegalTransitionIsConflict() throws Exception {
            willThrow(new IllegalArgumentException(
                    "Invalid status transition 'backlog' -> 'done'. Allowed from 'backlog': blocked, cancelled, ready"))
                    .given(workItemService).updateWorkItem(eq(1L), any(WorkItemRequestDTO.class), anyString());

            mockMvc.perform(put("/api/v1/work-items/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"done\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("Conflict"));
        }

        @Test
        @DisplayName("AC: 'claimed' is not reachable through a plain PUT (409)")
        void claimedIsNotReachableByPut() throws Exception {
            willThrow(new IllegalArgumentException(
                    "Status 'claimed' can only be set by POST /api/v1/work-items/{id}/claim"))
                    .given(workItemService).updateWorkItem(eq(1L), any(WorkItemRequestDTO.class), anyString());

            mockMvc.perform(put("/api/v1/work-items/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"claimed\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("/claim")));
        }

        @Test
        @DisplayName("an update of a foreign public project is 403")
        void foreignPublicProjectIsForbidden() throws Exception {
            willThrow(new AccessDeniedException("You can only update projects you own"))
                    .given(workItemService).updateWorkItem(eq(1L), any(WorkItemRequestDTO.class), anyString());

            mockMvc.perform(put("/api/v1/work-items/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"Other's item\"}"))
                    .andExpect(status().isForbidden());
        }
    }

    // ── DELETE /api/v1/work-items/{id} ────────────────────────────────────────

    @Nested
    @DisplayName("DELETE /api/v1/work-items/{id}")
    class DeleteItem {

        @Test
        @DisplayName("returns 204")
        void deletesItem() throws Exception {
            mockMvc.perform(delete("/api/v1/work-items/1"))
                    .andExpect(status().isNoContent());

            verify(workItemService).deleteWorkItem(eq(1L), anyString());
        }

        @Test
        @DisplayName("an unknown id is 404")
        void unknownIdIsNotFound() throws Exception {
            willThrow(new EntityNotFoundException("Work item not found with id: 999"))
                    .given(workItemService).deleteWorkItem(eq(999L), anyString());

            mockMvc.perform(delete("/api/v1/work-items/999"))
                    .andExpect(status().isNotFound());
        }
    }

    // ── POST /api/v1/work-items/{id}/claim ────────────────────────────────────

    @Nested
    @DisplayName("POST /api/v1/work-items/{id}/claim")
    class ClaimItem {

        @Test
        @DisplayName("returns 200 with claimToken, leaseExpiresAt and assigneeActorId")
        void claimSucceeds() throws Exception {
            given(workItemService.claimWorkItem(eq(1L), anyString())).willReturn(claim());

            mockMvc.perform(post("/api/v1/work-items/1/claim"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(1))
                    .andExpect(jsonPath("$.status").value("claimed"))
                    .andExpect(jsonPath("$.assigneeActorId").value(7))
                    .andExpect(jsonPath("$.claimToken").value(CLAIM_TOKEN))
                    .andExpect(jsonPath("$.leaseExpiresAt").exists())
                    .andExpect(jsonPath("$.version").value(1));
        }

        @Test
        @DisplayName("AC (WIKI4AI-141): a second claim of the same item is 409")
        void secondClaimIsConflict() throws Exception {
            willThrow(new IllegalArgumentException(
                    "Work item 1 could not be claimed: it is not in status 'ready' "
                            + "(it was already claimed, is being worked on, or is finished/cancelled)."))
                    .given(workItemService).claimWorkItem(eq(1L), anyString());

            mockMvc.perform(post("/api/v1/work-items/1/claim"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("Conflict"));
        }

        @Test
        @DisplayName("AC: a claim by a caller with no actor is 403, never 500")
        void claimWithoutActorIsForbidden() throws Exception {
            willThrow(new AccessDeniedException(
                    "Claiming a work item requires an actor identity; this credential names no actor"))
                    .given(workItemService).claimWorkItem(eq(1L), anyString());

            mockMvc.perform(post("/api/v1/work-items/1/claim"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("Forbidden"));
        }

        @Test
        @DisplayName("a claim of an unknown item is 404")
        void claimOfUnknownItemIsNotFound() throws Exception {
            willThrow(new EntityNotFoundException("Work item not found with id: 999"))
                    .given(workItemService).claimWorkItem(eq(999L), anyString());

            mockMvc.perform(post("/api/v1/work-items/999/claim"))
                    .andExpect(status().isNotFound());
        }
    }
}
