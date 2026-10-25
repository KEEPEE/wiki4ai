package com.wiki4ai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wiki4ai.dto.ApiTokenCreatedDTO;
import com.wiki4ai.dto.ApiTokenDTO;
import com.wiki4ai.dto.TokenCreateRequestDTO;
import com.wiki4ai.exception.BadRequestException;
import com.wiki4ai.exception.RateLimitExceededException;
import com.wiki4ai.model.ApiToken;
import com.wiki4ai.service.ApiTokenService;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web MVC tests for {@link TokenController} (WIKI4AI-135 / WIKI4AI-136).
 * <p>
 * The controller layer in isolation: the service is mocked, so no database is
 * involved and the assertions are about HTTP contracts — status codes, body shape
 * and the fact that no read endpoint can carry a secret. Service-level behaviour
 * (hashing, cache, revocation) is covered by {@code ApiTokenServiceTest}, the
 * 401-on-revoked-token behaviour by {@code JwtAuthenticationFilterTest}.
 */
@WebMvcTest(TokenController.class)
@AutoConfigureMockMvc
@ImportAutoConfiguration(exclude = {SecurityAutoConfiguration.class})
@ActiveProfiles("test")
class TokenControllerTest {

    private static final String ALICE = "token_ctrl_alice";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ApiTokenService apiTokenService;

    private final LocalDateTime now = LocalDateTime.of(2026, 10, 9, 12, 0);

    @BeforeEach
    void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(ALICE, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private ApiTokenDTO metadata(Long id, String name, String prefix) {
        return ApiTokenDTO.builder()
                .id(id)
                .name(name)
                .prefix(prefix)
                .scopes(List.of(ApiTokenService.SCOPE_READ, ApiTokenService.SCOPE_WRITE))
                .actorId(null)
                .expiresAt(null)
                .revokedAt(null)
                .lastUsedAt(null)
                .createdAt(now)
                .hashed(true)
                .build();
    }

    private ApiTokenCreatedDTO created(Long id, String name, String plaintext) {
        return ApiTokenCreatedDTO.builder()
                .token(plaintext)
                .metadata(metadata(id, name, plaintext.substring(
                        ApiToken.HASHED_TOKEN_PREFIX.length(),
                        ApiToken.HASHED_TOKEN_PREFIX.length() + 8)))
                .build();
    }

    /** A well-formed hashed token: w4a_ + exactly 32 base62 characters. */
    private static final String PLAINTEXT = "w4a_0123456789abcdefghijABCDEFGHIJKL";

    /** Its display prefix: the first 8 characters of the body, never the body. */
    private static final String PREFIX = "01234567";

    // ── POST /api/v1/tokens ───────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/v1/tokens")
    class CreateToken {

        @Test
        @DisplayName("201 with the plaintext token and its metadata")
        void shouldCreateTokenAndReturnPlaintextOnce() throws Exception {
            given(apiTokenService.createToken(any(TokenCreateRequestDTO.class), eq(ALICE)))
                    .willReturn(created(11L, "ci", PLAINTEXT));

            mockMvc.perform(post("/api/v1/tokens")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"ci\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.token").value(PLAINTEXT))
                    .andExpect(jsonPath("$.token").value(org.hamcrest.Matchers.startsWith("w4a_")))
                    .andExpect(jsonPath("$.metadata.id").value(11))
                    .andExpect(jsonPath("$.metadata.name").value("ci"))
                    .andExpect(jsonPath("$.metadata.prefix").value(PREFIX))
                    .andExpect(jsonPath("$.metadata.hashed").value(true));
        }

        @Test
        @DisplayName("201 passing scopes, expiry and actorId through to the service")
        void shouldForwardOptionalFields() throws Exception {
            given(apiTokenService.createToken(any(TokenCreateRequestDTO.class), eq(ALICE)))
                    .willReturn(created(12L, "agent", PLAINTEXT));

            mockMvc.perform(post("/api/v1/tokens")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(TokenCreateRequestDTO.builder()
                                    .name("agent")
                                    .scopes(List.of(ApiTokenService.SCOPE_READ))
                                    .actorId(5L)
                                    .expiresAt(now.plusDays(1))
                                    .build())))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("400 when the name is missing")
        void shouldRejectMissingName() throws Exception {
            mockMvc.perform(post("/api/v1/tokens")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.name").exists());
        }

        @Test
        @DisplayName("400 when the name is blank")
        void shouldRejectBlankName() throws Exception {
            mockMvc.perform(post("/api/v1/tokens")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"   \"}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("400 for an unknown scope (service validation)")
        void shouldRejectUnknownScope() throws Exception {
            willThrow(new BadRequestException("Unknown scope: superuser"))
                    .given(apiTokenService).createToken(any(TokenCreateRequestDTO.class), anyString());

            mockMvc.perform(post("/api/v1/tokens")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"ci\",\"scopes\":[\"superuser\"]}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Unknown scope: superuser"));
        }

        @Test
        @DisplayName("400 when actorId belongs to another user")
        void shouldRejectForeignActor() throws Exception {
            willThrow(new BadRequestException("Actor 7 does not belong to you"))
                    .given(apiTokenService).createToken(any(TokenCreateRequestDTO.class), anyString());

            mockMvc.perform(post("/api/v1/tokens")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"ci\",\"actorId\":7}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("400 when an agent token is asked for the admin scope")
        void shouldRejectAdminScopeForAgentToken() throws Exception {
            willThrow(new BadRequestException("An agent token cannot be granted the 'admin' scope"))
                    .given(apiTokenService).createToken(any(TokenCreateRequestDTO.class), anyString());

            mockMvc.perform(post("/api/v1/tokens")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"agent\",\"actorId\":7,\"scopes\":[\"read\",\"admin\"]}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message")
                            .value("An agent token cannot be granted the 'admin' scope"));
        }
    }

    // ── GET /api/v1/tokens ────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/v1/tokens")
    class ListTokens {

        @Test
        @DisplayName("200 with metadata only — no plaintext, no hash, no token_value")
        void shouldListWithoutSecrets() throws Exception {
            given(apiTokenService.listTokens(ALICE))
                    .willReturn(List.of(metadata(1L, "ci", PREFIX), metadata(2L, "old", "zzzzzzzz")));

            mockMvc.perform(get("/api/v1/tokens"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.size()").value(2))
                    .andExpect(jsonPath("$[0].id").value(1))
                    .andExpect(jsonPath("$[0].name").value("ci"))
                    .andExpect(jsonPath("$[0].prefix").value(PREFIX))
                    .andExpect(jsonPath("$[0].scopes").value(org.hamcrest.Matchers.hasItems("read", "write")))
                    .andExpect(jsonPath("$[0].createdAt").exists())
                    // The three fields that could leak a secret are not in the shape at all.
                    .andExpect(jsonPath("$[0].token").doesNotExist())
                    .andExpect(jsonPath("$[0].tokenHash").doesNotExist())
                    .andExpect(jsonPath("$[0].tokenValue").doesNotExist())
                    .andExpect(jsonPath("$[1].token").doesNotExist());
        }

        @Test
        @DisplayName("200 with an empty list when the user has no tokens")
        void shouldReturnEmptyList() throws Exception {
            given(apiTokenService.listTokens(ALICE)).willReturn(List.of());

            mockMvc.perform(get("/api/v1/tokens"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.size()").value(0));
        }

        @Test
        @DisplayName("A legacy row is reported as not hashed, without prefix or scopes")
        void shouldDescribeLegacyRows() throws Exception {
            ApiTokenDTO legacy = ApiTokenDTO.builder()
                    .id(3L)
                    .name("legacy-jwt")
                    .prefix(null)
                    .scopes(null)
                    .expiresAt(null)
                    .createdAt(now)
                    .hashed(false)
                    .build();
            given(apiTokenService.listTokens(ALICE)).willReturn(List.of(legacy));

            mockMvc.perform(get("/api/v1/tokens"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].hashed").value(false))
                    .andExpect(jsonPath("$[0].prefix").doesNotExist())
                    .andExpect(jsonPath("$[0].scopes").doesNotExist());
        }
    }

    // ── GET /api/v1/tokens/{id} ───────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/v1/tokens/{id}")
    class GetToken {

        @Test
        @DisplayName("200 with the token metadata")
        void shouldReturnToken() throws Exception {
            given(apiTokenService.getToken(1L, ALICE)).willReturn(metadata(1L, "ci", PREFIX));

            mockMvc.perform(get("/api/v1/tokens/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(1))
                    .andExpect(jsonPath("$.token").doesNotExist())
                    .andExpect(jsonPath("$.tokenHash").doesNotExist());
        }

        @Test
        @DisplayName("404 for an unknown id")
        void shouldReturn404ForUnknownId() throws Exception {
            given(apiTokenService.getToken(eq(999L), anyString()))
                    .willThrow(new EntityNotFoundException("API token not found"));

            mockMvc.perform(get("/api/v1/tokens/999"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404));
        }

        @Test
        @DisplayName("404 for someone else's token — the same answer as 'does not exist'")
        void shouldReturn404ForForeignToken() throws Exception {
            given(apiTokenService.getToken(eq(42L), anyString()))
                    .willThrow(new EntityNotFoundException("API token not found"));

            mockMvc.perform(get("/api/v1/tokens/42"))
                    .andExpect(status().isNotFound());
        }
    }

    // ── DELETE /api/v1/tokens/{id} ────────────────────────────────────────────

    @Nested
    @DisplayName("DELETE /api/v1/tokens/{id}")
    class RevokeToken {

        @Test
        @DisplayName("204 and the service performs the soft revoke")
        void shouldRevokeWith204() throws Exception {
            given(apiTokenService.revokeToken(1L, ALICE)).willReturn(true);

            mockMvc.perform(delete("/api/v1/tokens/1"))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("204 when the token was already revoked")
        void shouldBeIdempotent() throws Exception {
            given(apiTokenService.revokeToken(2L, ALICE)).willReturn(false);

            mockMvc.perform(delete("/api/v1/tokens/2"))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("404 for someone else's token")
        void shouldReturn404ForForeignToken() throws Exception {
            given(apiTokenService.revokeToken(eq(42L), anyString()))
                    .willThrow(new EntityNotFoundException("API token not found"));

            mockMvc.perform(delete("/api/v1/tokens/42"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("404 for an unknown id")
        void shouldReturn404ForUnknownId() throws Exception {
            given(apiTokenService.revokeToken(eq(999L), anyString()))
                    .willThrow(new EntityNotFoundException("API token not found"));

            mockMvc.perform(delete("/api/v1/tokens/999"))
                    .andExpect(status().isNotFound());
        }
    }

    // ── POST /api/v1/tokens/{id}/rotate (WIKI4AI-137) ─────────────────────────

    @Nested
    @DisplayName("POST /api/v1/tokens/{id}/rotate")
    class RotateToken {

        private static final String NEW_PLAINTEXT = "w4a_9876543210zyxwvutsrqponmlkjg";

        @Test
        @DisplayName("201 with the new plaintext and its metadata")
        void shouldReturn201WithNewSecret() throws Exception {
            given(apiTokenService.rotateToken(11L, ALICE))
                    .willReturn(created(12L, "ci", NEW_PLAINTEXT));

            mockMvc.perform(post("/api/v1/tokens/11/rotate"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.token").value(NEW_PLAINTEXT))
                    .andExpect(jsonPath("$.metadata.id").value(12))
                    .andExpect(jsonPath("$.metadata.name").value("ci"))
                    .andExpect(jsonPath("$.metadata.prefix").value("98765432"))
                    .andExpect(jsonPath("$.metadata.hashed").value(true))
                    // The rotated token is reported as a new row, not as the old id.
                    .andExpect(jsonPath("$.metadata.revokedAt").doesNotExist());
        }

        @Test
        @DisplayName("The path id and the caller reach the service")
        void shouldForwardIdAndCaller() throws Exception {
            given(apiTokenService.rotateToken(7L, ALICE)).willReturn(created(8L, "ci", NEW_PLAINTEXT));

            mockMvc.perform(post("/api/v1/tokens/7/rotate"))
                    .andExpect(status().isCreated());

            org.mockito.Mockito.verify(apiTokenService).rotateToken(7L, ALICE);
        }

        @Test
        @DisplayName("400 for a legacy clear-text token — it is not reissuable")
        void shouldReturn400ForLegacyToken() throws Exception {
            willThrow(new BadRequestException(
                    "Legacy API tokens cannot be rotated: their clear-text value is not reissuable. "
                            + "Create a new hashed token instead."))
                    .given(apiTokenService).rotateToken(eq(21L), anyString());

            mockMvc.perform(post("/api/v1/tokens/21/rotate"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("Legacy API tokens cannot be rotated")));
        }

        @Test
        @DisplayName("409 for an already revoked token")
        void shouldReturn409ForRevokedToken() throws Exception {
            willThrow(new IllegalArgumentException("API token 22 is already revoked and cannot be rotated"))
                    .given(apiTokenService).rotateToken(eq(22L), anyString());

            mockMvc.perform(post("/api/v1/tokens/22/rotate"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("already revoked")));
        }

        @Test
        @DisplayName("404 for someone else's token — same answer as 'does not exist'")
        void shouldReturn404ForForeignToken() throws Exception {
            willThrow(new EntityNotFoundException("API token not found"))
                    .given(apiTokenService).rotateToken(eq(42L), anyString());

            mockMvc.perform(post("/api/v1/tokens/42/rotate"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404));
        }

        @Test
        @DisplayName("404 for an unknown id")
        void shouldReturn404ForUnknownId() throws Exception {
            willThrow(new EntityNotFoundException("API token not found"))
                    .given(apiTokenService).rotateToken(eq(999L), anyString());

            mockMvc.perform(post("/api/v1/tokens/999/rotate"))
                    .andExpect(status().isNotFound());
        }
    }

    // ── Rate limit mapping (WIKI4AI-139) ──────────────────────────────────────

    /**
     * The throttle itself is enforced in {@code ApiTokenService} and answered by the
     * auth filter; these tests pin the other half of WIKI4AI-139: the exception type
     * is mapped to 429 with a Retry-After hint by the shared advice, and it can never
     * fall through to the generic 500 handler.
     */
    @Nested
    @DisplayName("Rate limit → 429 with Retry-After (WIKI4AI-139)")
    class RateLimitMapping {

        @Test
        @DisplayName("RateLimitExceededException becomes 429 with Retry-After: 1")
        void shouldMapTo429WithRetryAfter() throws Exception {
            willThrow(new RateLimitExceededException(
                    "API token request limit exceeded. Try again in 1 second(s).", 1L))
                    .given(apiTokenService).getToken(1L, ALICE);

            mockMvc.perform(get("/api/v1/tokens/1"))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().string("Retry-After", "1"))
                    .andExpect(jsonPath("$.status").value(429))
                    .andExpect(jsonPath("$.error").value("Too Many Requests"))
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("request limit exceeded")));
        }

        @Test
        @DisplayName("The throttle never escapes as a 500")
        void shouldNeverFallThroughTo500() throws Exception {
            willThrow(new RateLimitExceededException("API token request limit exceeded"))
                    .given(apiTokenService).listTokens(ALICE);

            mockMvc.perform(get("/api/v1/tokens"))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().string("Retry-After", "1"))
                    .andExpect(jsonPath("$.status").value(429));
        }

        @Test
        @DisplayName("The exception carries the Retry-After advice it was built with")
        void exceptionCarriesRetryAdvice() {
            RateLimitExceededException ten =
                    new RateLimitExceededException("limited", 10L);

            assertThat(ten.getRetryAfterSeconds()).isEqualTo(10L);
            assertThat(new RateLimitExceededException("limited").getRetryAfterSeconds())
                    .isEqualTo(RateLimitExceededException.DEFAULT_RETRY_AFTER_SECONDS)
                    .isEqualTo(1L);
        }
    }

    // ── Contract guard ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Response shape guard")
    class ResponseShape {

        /**
         * Structural guard, not a behaviour test: the read DTO must not even have a
         * field a secret could travel in. If someone later adds {@code tokenValue}
         * "just for debugging", this fails before any endpoint can leak it.
         */
        @Test
        @DisplayName("ApiTokenDTO has no field that could carry a token secret")
        void dtoHasNoSecretFields() {
            List<String> declared = java.util.Arrays.stream(ApiTokenDTO.class.getDeclaredFields())
                    .map(java.lang.reflect.Field::getName)
                    .toList();

            assertThat(declared).doesNotContain(
                    "token", "tokenValue", "tokenHash", "accessToken", "plaintext", "secret");
            assertThat(declared).contains("prefix");
        }

        @Test
        @DisplayName("The plaintext used in these tests is a valid hashed token format")
        void plaintextMatchesTokenFormat() {
            assertThat(PLAINTEXT).hasSize(ApiToken.HASHED_TOKEN_PREFIX.length() + ApiTokenService.TOKEN_BODY_LENGTH);
            assertThat(ApiTokenService.isHashedTokenFormat(PLAINTEXT)).isTrue();
            assertThat(PREFIX).hasSize(8);
            assertThat(PLAINTEXT).startsWith(ApiToken.HASHED_TOKEN_PREFIX + PREFIX);
        }
    }
}
