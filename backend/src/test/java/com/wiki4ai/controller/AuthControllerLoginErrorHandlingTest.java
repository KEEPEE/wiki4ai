package com.wiki4ai.controller;

import com.wiki4ai.dto.AuthResponseDTO;
import com.wiki4ai.dto.UserDTO;
import com.wiki4ai.model.Role;
import com.wiki4ai.service.AuthService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WIKI4AI-68: login endpoint error-handling contract.
 *
 * <p>When the database fails during login (pool exhaustion, constraint violation, ...),
 * the client must receive the SAME generic 401 "Invalid username or password" as for bad
 * credentials — no DB details may leak to unauthenticated callers. The real cause is
 * logged server-side (verified by code review of AuthController.login; log capture is not
 * asserted here to keep the test independent of the logging backend).</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "security.enabled=true")
class AuthControllerLoginErrorHandlingTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuthService authService;

    @Nested
    @DisplayName("POST /api/v1/auth/login — database failure handling")
    class DatabaseFailureTests {

        @Test
        @DisplayName("DB exception during login → generic 401, no DB details in body")
        void dbExceptionReturnsGeneric401() throws Exception {
            // DuplicateKeyException = concrete DataAccessException subclass — the exact
            // type observed in production (refresh_tokens.user_id unique constraint)
            given(authService.loginUser(any()))
                    .willThrow(new DuplicateKeyException(
                            "could not execute statement [ERROR: duplicate key value violates unique constraint]"));

            String response = mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                        "username": "victim",
                                        "password": "correct-horse"
                                    }
                                    """))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("Invalid username or password"))
                    .andReturn().getResponse().getContentAsString();

            // Security: no infrastructure detail may reach the client.
            assertThat(response).doesNotContain("duplicate key");
            assertThat(response).doesNotContain("constraint");
            assertThat(response).doesNotContain("SQL");
        }

        @Test
        @DisplayName("null result (invalid credentials) → generic 401 (unchanged behavior)")
        void nullResultReturnsGeneric401() throws Exception {
            given(authService.loginUser(any())).willReturn(null);

            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                        "username": "nobody",
                                        "password": "wrong"
                                    }
                                    """))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("Invalid username or password"));
        }

        @Test
        @DisplayName("successful login → 200 with tokens (unchanged behavior)")
        void successReturns200() throws Exception {
            AuthResponseDTO ok = AuthResponseDTO.builder()
                    .accessToken("access")
                    .refreshToken("refresh")
                    .user(UserDTO.builder()
                            .id(1L)
                            .username("alice")
                            .email("alice@example.com")
                            .role(Role.USER)
                            .createdAt(LocalDateTime.now())
                            .build())
                    .build();
            given(authService.loginUser(any())).willReturn(ok);

            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                        "username": "alice",
                                        "password": "secret"
                                    }
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessToken").value("access"))
                    .andExpect(jsonPath("$.refreshToken").value("refresh"))
                    .andExpect(jsonPath("$.user.username").value("alice"));
        }
    }
}
