package com.wiki4ai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wiki4ai.config.JwtAuthenticationFilter.ApiTokenAuthenticationDetails;
import com.wiki4ai.dto.ActorCreateDTO;
import com.wiki4ai.dto.ActorDTO;
import com.wiki4ai.dto.ActorHeartbeatDTO;
import com.wiki4ai.dto.ActorUpdateDTO;
import com.wiki4ai.dto.ActorWhoamiDTO;
import com.wiki4ai.repository.ActorRepository;
import com.wiki4ai.repository.UserRepository;
import com.wiki4ai.service.ActorService;
import com.wiki4ai.service.ApiTokenService;
import com.wiki4ai.service.RequestActorResolver;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
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
 * Web MVC tests for ActorController using MockMvc (WIKI4AI-131).
 * Tests the controller layer in isolation without starting the full
 * application context — the service is mocked, so no database is involved.
 */
@WebMvcTest(ActorController.class)
@AutoConfigureMockMvc
@ImportAutoConfiguration(exclude = {SecurityAutoConfiguration.class})
@ActiveProfiles("test")
// WIKI4AI-134: ActorController gained a collaborator (RequestActorResolver). It is
// imported as the REAL component — with its two repositories mocked — so the whoami
// tests below exercise the actual SecurityContext → identity resolution instead of
// stubbing the resolver away. Existing tests do not touch these beans.
@Import(RequestActorResolver.class)
class ActorControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ActorService actorService;

    /**
     * WIKI4AI-137: the actor kill switch (DELETE /api/v1/actors/{id}/tokens) is token
     * business and lives in ApiTokenService, so the slice needs it mocked too.
     */
    @MockBean
    private ApiTokenService apiTokenService;

    /**
     * WIKI4AI-134: dependencies of the imported RequestActorResolver. Mocked so the
     * slice stays a slice — no database is involved, as before.
     */
    @MockBean
    private ActorRepository actorRepository;

    @MockBean
    private UserRepository userRepository;

    private final LocalDateTime now = LocalDateTime.of(2026, 9, 15, 10, 0);

    private ActorDTO sampleActor() {
        return ActorDTO.builder()
                .id(1L)
                .handle("agent-coder")
                .displayName("Coder agent")
                .kind("agent")
                .ownerUserId(7L)
                .systemLabel(null)
                .capabilities("java,spring")
                .webhookUrl("https://example.local/hooks/coder")
                .lastSeenAt(now)
                .status("active")
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    // ── GET /api/v1/actors ────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/v1/actors")
    class ListActors {

        @Test
        @DisplayName("Returns 200 with the actor list")
        void shouldListActors() throws Exception {
            given(actorService.listActors(isNull(), anyString())).willReturn(List.of(sampleActor()));

            mockMvc.perform(get("/api/v1/actors"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].id").value(1))
                    .andExpect(jsonPath("$[0].handle").value("agent-coder"))
                    .andExpect(jsonPath("$[0].kind").value("agent"))
                    .andExpect(jsonPath("$[0].status").value("active"));
        }

        @Test
        @DisplayName("Passes the status filter to the service")
        void shouldPassStatusFilter() throws Exception {
            given(actorService.listActors(eq("paused"), anyString())).willReturn(List.of());

            mockMvc.perform(get("/api/v1/actors").param("status", "paused"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isEmpty());
        }

        @Test
        @DisplayName("Returns 400 for an unknown status filter")
        void shouldRejectUnknownStatus() throws Exception {
            willThrow(new com.wiki4ai.exception.BadRequestException(
                    "Invalid status 'bogus'. Allowed values: 'active', 'paused', 'archived'"))
                    .given(actorService).listActors(eq("bogus"), anyString());

            mockMvc.perform(get("/api/v1/actors").param("status", "bogus"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("Bad Request"));
        }
    }

    // ── POST /api/v1/actors ───────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/v1/actors")
    class CreateActor {

        @Test
        @DisplayName("Returns 201 with the created actor")
        void shouldCreateActor() throws Exception {
            given(actorService.createActor(any(), anyString())).willReturn(sampleActor());

            String body = objectMapper.writeValueAsString(ActorCreateDTO.builder()
                    .handle("agent-coder")
                    .displayName("Coder agent")
                    .kind("agent")
                    .capabilities("java,spring")
                    .webhookUrl("https://example.local/hooks/coder")
                    .build());

            mockMvc.perform(post("/api/v1/actors")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").value(1))
                    .andExpect(jsonPath("$.handle").value("agent-coder"))
                    .andExpect(jsonPath("$.ownerUserId").value(7));
        }

        @Test
        @DisplayName("Returns 400 when the handle is missing")
        void shouldRejectMissingHandle() throws Exception {
            String body = objectMapper.writeValueAsString(ActorCreateDTO.builder()
                    .displayName("No handle")
                    .build());

            mockMvc.perform(post("/api/v1/actors")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.handle").exists());
        }

        @Test
        @DisplayName("Returns 400 when the handle contains an illegal character")
        void shouldRejectIllegalHandleCharacters() throws Exception {
            String body = objectMapper.writeValueAsString(ActorCreateDTO.builder()
                    .handle("agent coder!")
                    .build());

            mockMvc.perform(post("/api/v1/actors")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.handle").exists());
        }

        @Test
        @DisplayName("Returns 409 when the handle is already taken")
        void shouldReturn409ForDuplicateHandle() throws Exception {
            willThrow(new IllegalArgumentException("An actor with handle 'agent-coder' already exists"))
                    .given(actorService).createActor(any(), anyString());

            String body = objectMapper.writeValueAsString(ActorCreateDTO.builder()
                    .handle("agent-coder")
                    .build());

            mockMvc.perform(post("/api/v1/actors")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("Conflict"))
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("agent-coder")));
        }
    }

    // ── GET /api/v1/actors/{id} ───────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/v1/actors/{id}")
    class GetActor {

        @Test
        @DisplayName("Returns 200 with the actor detail")
        void shouldGetActor() throws Exception {
            given(actorService.getActor(eq(1L), anyString())).willReturn(sampleActor());

            mockMvc.perform(get("/api/v1/actors/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(1))
                    .andExpect(jsonPath("$.handle").value("agent-coder"))
                    .andExpect(jsonPath("$.capabilities").value("java,spring"));
        }

        @Test
        @DisplayName("Returns 404 when the actor is missing")
        void shouldReturn404ForMissingActor() throws Exception {
            willThrow(new EntityNotFoundException("Actor not found with id: 99"))
                    .given(actorService).getActor(eq(99L), anyString());

            mockMvc.perform(get("/api/v1/actors/99"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("Not Found"));
        }
    }

    // ── PUT /api/v1/actors/{id} ───────────────────────────────────────────────

    @Nested
    @DisplayName("PUT /api/v1/actors/{id}")
    class UpdateActor {

        @Test
        @DisplayName("Returns 200 with the updated actor")
        void shouldUpdateActor() throws Exception {
            ActorDTO paused = sampleActor();
            paused.setStatus("paused");
            given(actorService.updateActor(eq(1L), any(), anyString())).willReturn(paused);

            String body = objectMapper.writeValueAsString(ActorUpdateDTO.builder()
                    .status("paused")
                    .build());

            mockMvc.perform(put("/api/v1/actors/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(1))
                    .andExpect(jsonPath("$.status").value("paused"));
        }

        @Test
        @DisplayName("Returns 404 when the actor is missing")
        void shouldReturn404ForMissingActor() throws Exception {
            willThrow(new EntityNotFoundException("Actor not found with id: 99"))
                    .given(actorService).updateActor(eq(99L), any(), anyString());

            String body = objectMapper.writeValueAsString(ActorUpdateDTO.builder()
                    .status("archived")
                    .build());

            mockMvc.perform(put("/api/v1/actors/99")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("Not Found"));
        }

        @Test
        @DisplayName("Returns 403 when a non-owner updates a foreign actor")
        void shouldReturn403ForForeignActor() throws Exception {
            willThrow(new AccessDeniedException("You can only update actors that you own"))
                    .given(actorService).updateActor(eq(1L), any(), anyString());

            String body = objectMapper.writeValueAsString(ActorUpdateDTO.builder()
                    .status("archived")
                    .build());

            mockMvc.perform(put("/api/v1/actors/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("Forbidden"));
        }

        @Test
        @DisplayName("Returns 409 when the new handle belongs to another actor")
        void shouldReturn409ForTakenHandle() throws Exception {
            willThrow(new IllegalArgumentException("An actor with handle 'other-agent' already exists"))
                    .given(actorService).updateActor(eq(1L), any(), anyString());

            String body = objectMapper.writeValueAsString(ActorUpdateDTO.builder()
                    .handle("other-agent")
                    .build());

            mockMvc.perform(put("/api/v1/actors/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("Conflict"));
        }
    }

    // ── DELETE /api/v1/actors/{id} ────────────────────────────────────────────

    @Nested
    @DisplayName("DELETE /api/v1/actors/{id}")
    class DeleteActor {

        @Test
        @DisplayName("Returns 204 on success")
        void shouldDeleteActor() throws Exception {
            mockMvc.perform(delete("/api/v1/actors/1"))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("Returns 404 when the actor is missing")
        void shouldReturn404ForMissingActor() throws Exception {
            willThrow(new EntityNotFoundException("Actor not found with id: 99"))
                    .given(actorService).deleteActor(eq(99L), anyString());

            mockMvc.perform(delete("/api/v1/actors/99"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("Not Found"));
        }

        @Test
        @DisplayName("Returns 403 when a non-owner deletes a foreign actor")
        void shouldReturn403ForForeignActor() throws Exception {
            willThrow(new AccessDeniedException("You can only delete actors that you own"))
                    .given(actorService).deleteActor(eq(1L), anyString());

            mockMvc.perform(delete("/api/v1/actors/1"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("Forbidden"));
        }
    }

    // ── POST /api/v1/actors/{id}/heartbeat (WIKI4AI-133) ──────────────────────

    @Nested
    @DisplayName("POST /api/v1/actors/{id}/heartbeat")
    class HeartbeatActor {

        @Test
        @DisplayName("Returns 204 with a presence payload")
        void shouldAcceptHeartbeatWithBody() throws Exception {
            String body = objectMapper.writeValueAsString(ActorHeartbeatDTO.builder()
                    .status("working")
                    .note("working on WIKI4AI-133")
                    .build());

            mockMvc.perform(post("/api/v1/actors/1/heartbeat")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("Returns 204 with an empty JSON body (both fields optional)")
        void shouldAcceptHeartbeatWithEmptyBody() throws Exception {
            mockMvc.perform(post("/api/v1/actors/1/heartbeat")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("Returns 204 with no body at all")
        void shouldAcceptHeartbeatWithoutBody() throws Exception {
            mockMvc.perform(post("/api/v1/actors/1/heartbeat"))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("Returns 400 for an unknown presence status")
        void shouldReturn400ForUnknownPresenceStatus() throws Exception {
            willThrow(new com.wiki4ai.exception.BadRequestException(
                    "Invalid presence status 'alien'. Allowed values: 'idle', 'working', 'busy'"))
                    .given(actorService).heartbeat(eq(1L), any(), anyString());

            String body = objectMapper.writeValueAsString(ActorHeartbeatDTO.builder()
                    .status("alien")
                    .build());

            mockMvc.perform(post("/api/v1/actors/1/heartbeat")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("Bad Request"));
        }

        @Test
        @DisplayName("Returns 400 when the note is longer than 256 characters")
        void shouldReturn400ForOverlongNote() throws Exception {
            String body = objectMapper.writeValueAsString(ActorHeartbeatDTO.builder()
                    .note("x".repeat(257))
                    .build());

            mockMvc.perform(post("/api/v1/actors/1/heartbeat")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.note").exists());
        }

        @Test
        @DisplayName("Returns 400 when the actor is archived")
        void shouldReturn400ForArchivedActor() throws Exception {
            willThrow(new com.wiki4ai.exception.BadRequestException(
                    "Actor 'agent-coder' is archived and cannot send heartbeats"))
                    .given(actorService).heartbeat(eq(1L), any(), anyString());

            mockMvc.perform(post("/api/v1/actors/1/heartbeat")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("archived")));
        }

        @Test
        @DisplayName("Returns 404 when the actor is missing")
        void shouldReturn404ForMissingActor() throws Exception {
            willThrow(new EntityNotFoundException("Actor not found with id: 99"))
                    .given(actorService).heartbeat(eq(99L), any(), anyString());

            mockMvc.perform(post("/api/v1/actors/99/heartbeat")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("Not Found"));
        }

        @Test
        @DisplayName("Returns 403 when a non-owner sends a heartbeat for a foreign actor")
        void shouldReturn403ForForeignActor() throws Exception {
            willThrow(new AccessDeniedException("You can only update actors that you own"))
                    .given(actorService).heartbeat(eq(1L), any(), anyString());

            mockMvc.perform(post("/api/v1/actors/1/heartbeat")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("Forbidden"));
        }

        @Test
        @DisplayName("Returns 403 for an unauthenticated caller")
        void shouldReturn403WithoutAuthentication() throws Exception {
            willThrow(new AccessDeniedException("Authentication required"))
                    .given(actorService).heartbeat(eq(1L), any(), anyString());

            mockMvc.perform(post("/api/v1/actors/1/heartbeat"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value("Authentication required"));
        }
    }

    // ── DELETE /api/v1/actors/{id}/tokens (WIKI4AI-137) ───────────────────────

    /**
     * HTTP contract of the actor kill switch. The revoking itself — stamping
     * {@code revoked_at} on every token of the actor and evicting each cache entry —
     * is proven in {@code ApiTokenServiceTest}; here the question is only what the
     * endpoint answers: 204, 404 for an actor you may not touch, and 204 again on a
     * repeat call.
     */
    @Nested
    @DisplayName("DELETE /api/v1/actors/{id}/tokens — kill switch")
    class RevokeActorTokens {

        @Test
        @DisplayName("204 and the service revokes every token of the actor")
        void shouldReturn204() throws Exception {
            given(apiTokenService.revokeActorTokens(eq(1L), anyString())).willReturn(3);

            mockMvc.perform(delete("/api/v1/actors/1/tokens"))
                    .andExpect(status().isNoContent());

            org.mockito.Mockito.verify(apiTokenService).revokeActorTokens(eq(1L), anyString());
        }

        @Test
        @DisplayName("204 on a repeat call — the switch is idempotent")
        void shouldBeIdempotent() throws Exception {
            given(apiTokenService.revokeActorTokens(eq(1L), anyString())).willReturn(0);

            mockMvc.perform(delete("/api/v1/actors/1/tokens"))
                    .andExpect(status().isNoContent());

            mockMvc.perform(delete("/api/v1/actors/1/tokens"))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("204 even when the actor has no tokens")
        void shouldReturn204ForActorWithoutTokens() throws Exception {
            given(apiTokenService.revokeActorTokens(eq(2L), anyString())).willReturn(0);

            mockMvc.perform(delete("/api/v1/actors/2/tokens"))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("404 for an actor that belongs to someone else")
        void shouldReturn404ForForeignActor() throws Exception {
            willThrow(new EntityNotFoundException("Actor not found: 42"))
                    .given(apiTokenService).revokeActorTokens(eq(42L), anyString());

            mockMvc.perform(delete("/api/v1/actors/42/tokens"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.error").value("Not Found"));
        }

        @Test
        @DisplayName("404 for an unknown actor — the same answer as 'not yours'")
        void shouldReturn404ForUnknownActor() throws Exception {
            willThrow(new EntityNotFoundException("Actor not found: 999"))
                    .given(apiTokenService).revokeActorTokens(eq(999L), anyString());

            mockMvc.perform(delete("/api/v1/actors/999/tokens"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("The kill switch does not disturb the existing actor endpoints")
        void shouldNotShadowDeleteActor() throws Exception {
            // DELETE /api/v1/actors/1 still deletes the actor itself, and
            // DELETE /api/v1/actors/1/tokens still only revokes tokens.
            mockMvc.perform(delete("/api/v1/actors/1"))
                    .andExpect(status().isNoContent());

            org.mockito.Mockito.verify(actorService).deleteActor(eq(1L), anyString());
            org.mockito.Mockito.verify(apiTokenService, org.mockito.Mockito.never())
                    .revokeActorTokens(eq(1L), anyString());
        }
    }

    // ── GET /api/v1/actors/whoami (WIKI4AI-134) ───────────────────────────────

    /**
     * HTTP contract of whoami. The identity the controller hands to the service is
     * captured and asserted, because that is exactly where the security rule lives:
     * the actor id travels out of the authenticated credential and from nowhere
     * else. The resolver is the real one (imported above), so these tests drive
     * SecurityContext → identity → service for real.
     * <p>
     * The 401 of an unauthenticated caller is enforced by SecurityConfig, not by
     * this controller, and is proven end-to-end in {@code ActorWhoamiIntegrationTest}
     * — this slice runs with the security filter chain excluded.
     */
    @Nested
    @DisplayName("GET /api/v1/actors/whoami")
    class Whoami {

        @AfterEach
        void clearAuthentication() {
            SecurityContextHolder.clearContext();
        }

        /** Put an authentication into the SecurityContext the way the JWT filter does. */
        private void authenticateAs(String username, ApiTokenAuthenticationDetails details) {
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    username, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
            authentication.setDetails(details);
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        /**
         * Stub the service with the answer the real {@code ActorService.whoami} gives
         * for the identity it is handed, so the JSON assertions below describe the
         * endpoint and not a fixture.
         */
        private void stubWhoamiForIdentity(String handle, String kind) {
            given(actorService.whoami(anyString(), any())).willAnswer(invocation -> {
                String username = invocation.getArgument(0);
                RequestActorResolver.CallerIdentity identity = invocation.getArgument(1);
                return ActorWhoamiDTO.builder()
                        .userId(identity.userId())
                        .username(username)
                        .actorId(identity.actorId())
                        .handle(identity.actorId() != null ? handle : null)
                        .kind(identity.actorId() != null ? kind : null)
                        .scopes(identity.scopes())
                        .build();
            });
        }

        /** The identity the controller actually resolved from the SecurityContext. */
        private RequestActorResolver.CallerIdentity capturedIdentity() {
            ArgumentCaptor<RequestActorResolver.CallerIdentity> captor =
                    ArgumentCaptor.forClass(RequestActorResolver.CallerIdentity.class);
            org.mockito.Mockito.verify(actorService).whoami(anyString(), captor.capture());
            return captor.getValue();
        }

        @Test
        @DisplayName("200 with the actor the token acts as")
        void agentTokenReportsItsActor() throws Exception {
            stubWhoamiForIdentity("agent-coder", "agent");
            authenticateAs("agent-user",
                    new ApiTokenAuthenticationDetails(11L, 7L, 42L, List.of("read", "write")));

            mockMvc.perform(get("/api/v1/actors/whoami"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.userId").value(7))
                    .andExpect(jsonPath("$.username").value("agent-user"))
                    .andExpect(jsonPath("$.actorId").value(42))
                    .andExpect(jsonPath("$.handle").value("agent-coder"))
                    .andExpect(jsonPath("$.kind").value("agent"))
                    .andExpect(jsonPath("$.scopes[0]").value("read"))
                    .andExpect(jsonPath("$.scopes[1]").value("write"));

            RequestActorResolver.CallerIdentity identity = capturedIdentity();
            org.assertj.core.api.Assertions.assertThat(identity.actorId()).isEqualTo(42L);
            org.assertj.core.api.Assertions.assertThat(identity.userId()).isEqualTo(7L);
            org.assertj.core.api.Assertions.assertThat(identity.scopes())
                    .containsExactly("read", "write");
        }

        @Test
        @DisplayName("200 with no actor when the token has no actorId")
        void agentTokenWithoutActorIdReportsNoActor() throws Exception {
            stubWhoamiForIdentity("agent-coder", "agent");
            authenticateAs("human-user",
                    new ApiTokenAuthenticationDetails(12L, 7L, null, List.of("read")));

            mockMvc.perform(get("/api/v1/actors/whoami"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.userId").value(7))
                    .andExpect(jsonPath("$.username").value("human-user"))
                    .andExpect(jsonPath("$.actorId", org.hamcrest.Matchers.nullValue()))
                    .andExpect(jsonPath("$.handle", org.hamcrest.Matchers.nullValue()));

            org.assertj.core.api.Assertions.assertThat(capturedIdentity().actorId()).isNull();
        }

        @Test
        @DisplayName("Legacy JWT (details == null) is a 200 with no actor — not 400, not 401")
        void legacyJwtIsNotAnError() throws Exception {
            stubWhoamiForIdentity("agent-coder", "agent");
            // Exactly what the legacy branch of the filter produces: an
            // authentication whose details are null.
            authenticateAs("legacy-user", null);

            mockMvc.perform(get("/api/v1/actors/whoami"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.username").value("legacy-user"))
                    .andExpect(jsonPath("$.actorId", org.hamcrest.Matchers.nullValue()))
                    .andExpect(jsonPath("$.handle", org.hamcrest.Matchers.nullValue()))
                    .andExpect(jsonPath("$.kind", org.hamcrest.Matchers.nullValue()));

            RequestActorResolver.CallerIdentity identity = capturedIdentity();
            org.assertj.core.api.Assertions.assertThat(identity.actorId()).isNull();
            org.assertj.core.api.Assertions.assertThat(identity.scopes()).isEmpty();
        }

        @Test
        @DisplayName("whoami is matched literally — never by GET /api/v1/actors/{id}")
        void whoamiIsNotConfusedWithActorById() throws Exception {
            stubWhoamiForIdentity("agent-coder", "agent");
            authenticateAs("agent-user",
                    new ApiTokenAuthenticationDetails(11L, 7L, 42L, List.of("read")));

            // 'whoami' must not be parsed as an id: no Long conversion, no 400,
            // and GET /{id} is never reached.
            mockMvc.perform(get("/api/v1/actors/whoami"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.username").value("agent-user"))
                    .andExpect(jsonPath("$.actorId").value(42));

            org.mockito.Mockito.verify(actorService, org.mockito.Mockito.never())
                    .getActor(org.mockito.ArgumentMatchers.anyLong(), anyString());

            // The {id} route still resolves a numeric id…
            given(actorService.getActor(eq(1L), anyString())).willReturn(sampleActor());
            mockMvc.perform(get("/api/v1/actors/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(1))
                    .andExpect(jsonPath("$.handle").value("agent-coder"));

            // …and still rejects a non-numeric one, which is what would have
            // happened to 'whoami' had the literal path lost.
            mockMvc.perform(get("/api/v1/actors/whoami-not-an-id"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("The caller's own actor is reported whatever user owns it")
        void foreignOwnedActorIsStillTheCallersIdentity() throws Exception {
            stubWhoamiForIdentity("someone-elses-agent", "agent");
            // The actor belongs to another user; whoami describes the CALLER, it
            // does not police registry ownership (the token was already checked
            // when it was created).
            authenticateAs("other-user",
                    new ApiTokenAuthenticationDetails(13L, 99L, 42L, List.of("read")));

            mockMvc.perform(get("/api/v1/actors/whoami"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.username").value("other-user"))
                    .andExpect(jsonPath("$.userId").value(99))
                    .andExpect(jsonPath("$.actorId").value(42))
                    .andExpect(jsonPath("$.handle").value("someone-elses-agent"));
        }

        @Test
        @DisplayName("With no authentication at all the controller reports an anonymous, actor-less caller")
        void anonymousCallerCarriesNoIdentity() throws Exception {
            stubWhoamiForIdentity("agent-coder", "agent");
            SecurityContextHolder.clearContext();

            mockMvc.perform(get("/api/v1/actors/whoami"))
                    .andExpect(status().isOk());

            RequestActorResolver.CallerIdentity identity = capturedIdentity();
            org.assertj.core.api.Assertions.assertThat(identity.actorId()).isNull();
            org.assertj.core.api.Assertions.assertThat(identity.userId()).isNull();
            org.assertj.core.api.Assertions.assertThat(identity.scopes()).isEmpty();
        }
    }
}
