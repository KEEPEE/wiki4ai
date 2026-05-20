package com.wiki4ai.controller;

import com.wiki4ai.dto.AuthResponseDTO;
import com.wiki4ai.dto.UserDTO;
import com.wiki4ai.model.Role;
import com.wiki4ai.service.AuthService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for AuthController POST /token endpoint.
 * Tests token generation for authenticated users, and 401 responses for unauthenticated requests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.test.context.ActiveProfiles("test")
@org.springframework.test.context.TestPropertySource(properties = "security.enabled=true")
class AuthControllerTokenTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuthService authService;

    private final LocalDateTime now = LocalDateTime.of(2024, 5, 16, 10, 0);

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private AuthResponseDTO createSampleAuthResponse(String username) {
        UserDTO user = UserDTO.builder()
                .id(1L)
                .username(username)
                .email(username + "@example.com")
                .role(Role.USER)
                .createdAt(now)
                .build();

        return AuthResponseDTO.builder()
                .accessToken("test-access-token-for-" + username)
                .refreshToken("test-refresh-token-for-" + username)
                .user(user)
                .build();
    }

    @Nested
    @DisplayName("POST /api/v1/auth/token - Generate new token")
    class GenerateNewTokenTests {

        @Test
        @DisplayName("Should return 200 with new accessToken and refreshToken when authenticated")
        void shouldReturnNewTokensWhenAuthenticated() throws Exception {
            // given
            String username = "testuser";
            AuthResponseDTO expectedResponse = createSampleAuthResponse(username);

            List<SimpleGrantedAuthority> authorities = Collections.singletonList(
                    new SimpleGrantedAuthority("ROLE_USER"));
            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(username, null, authorities);

            given(authService.generateNewTokenForUser(username)).willReturn(expectedResponse);

            // when & then
            mockMvc.perform(post("/api/v1/auth/token").with(authentication(auth)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessToken").value("test-access-token-for-testuser"))
                    .andExpect(jsonPath("$.refreshToken").value("test-refresh-token-for-testuser"))
                    .andExpect(jsonPath("$.user.id").value(1))
                    .andExpect(jsonPath("$.user.username").value("testuser"))
                    .andExpect(jsonPath("$.user.email").value("testuser@example.com"))
                    .andExpect(jsonPath("$.user.role").value("USER"));
        }

        @Test
        @DisplayName("Should return 200 with new tokens for admin user")
        void shouldReturnNewTokensForAdminUser() throws Exception {
            // given
            String username = "admin";
            UserDTO adminUser = UserDTO.builder()
                    .id(2L)
                    .username(username)
                    .email("admin@example.com")
                    .role(Role.ADMIN)
                    .createdAt(now)
                    .build();

            AuthResponseDTO expectedResponse = AuthResponseDTO.builder()
                    .accessToken("admin-access-token")
                    .refreshToken("admin-refresh-token")
                    .user(adminUser)
                    .build();

            List<SimpleGrantedAuthority> authorities = Collections.singletonList(
                    new SimpleGrantedAuthority("ROLE_USER"));
            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(username, null, authorities);

            given(authService.generateNewTokenForUser(username)).willReturn(expectedResponse);

            // when & then
            mockMvc.perform(post("/api/v1/auth/token").with(authentication(auth)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessToken").value("admin-access-token"))
                    .andExpect(jsonPath("$.refreshToken").value("admin-refresh-token"))
                    .andExpect(jsonPath("$.user.role").value("ADMIN"));
        }

        @Test
        @DisplayName("Should return 401 when no authentication (no token)")
        void shouldReturn401WhenNoAuthentication() throws Exception {
            // given - empty SecurityContext

            // when & then
            mockMvc.perform(post("/api/v1/auth/token"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").exists());
        }

        @Test
        @DisplayName("Should return 401 for anonymous user")
        void shouldReturn401ForAnonymousUser() throws Exception {
            // given - simulate anonymous authentication (no valid JWT)
            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken("anonymousUser", null, Collections.emptyList());

            // when & then
            mockMvc.perform(post("/api/v1/auth/token").with(authentication(auth)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").exists());
        }

        @Test
        @DisplayName("Should return 401 when user not found in database")
        void shouldReturn401WhenUserNotFoundInDatabase() throws Exception {
            // given
            String username = "ghostuser";

            List<SimpleGrantedAuthority> authorities = Collections.singletonList(
                    new SimpleGrantedAuthority("ROLE_USER"));
            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(username, null, authorities);

            given(authService.generateNewTokenForUser(username)).willReturn(null);

            // when & then
            mockMvc.perform(post("/api/v1/auth/token").with(authentication(auth)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("User not found in database"));
        }

        @Test
        @DisplayName("Should return tokens without password field")
        void shouldNotReturnPasswordInResponse() throws Exception {
            // given
            String username = "secureuser";
            AuthResponseDTO expectedResponse = createSampleAuthResponse(username);

            List<SimpleGrantedAuthority> authorities = Collections.singletonList(
                    new SimpleGrantedAuthority("ROLE_USER"));
            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(username, null, authorities);

            given(authService.generateNewTokenForUser(username)).willReturn(expectedResponse);

            // when & then
            mockMvc.perform(post("/api/v1/auth/token").with(authentication(auth)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.user.password").doesNotExist());
        }
    }
}
