package com.wiki4ai.controller;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for AuthController GET /me endpoint.
 * Uses @SpringBootTest to load the full application context with security disabled,
 * allowing us to test controller logic directly via SecurityContext manipulation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.test.context.ActiveProfiles("test")
@org.springframework.test.context.TestPropertySource(properties = "security.enabled=true")
class AuthControllerMeTest {

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

    private UserDTO createSampleUserDto(Long id, String username, String email, Role role) {
        return UserDTO.builder()
                .id(id)
                .username(username)
                .email(email)
                .role(role)
                // WIKI4AI-73: profile always exposes the UI language preference
                .language("en")
                .createdAt(now)
                .build();
    }

    @Nested
    @DisplayName("GET /api/v1/auth/me - Get current user profile")
    class GetCurrentProfileTests {

        @Test
        @DisplayName("Should return 200 with user profile when authenticated")
        void shouldReturnUserProfileWhenAuthenticated() throws Exception {
            // given
            String username = "testuser";
            UserDTO expectedProfile = createSampleUserDto(1L, username, "test@example.com", Role.USER);

            List<SimpleGrantedAuthority> authorities = Collections.singletonList(
                    new SimpleGrantedAuthority("ROLE_USER"));
            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(username, null, authorities);

            given(authService.getProfileByUsername(username)).willReturn(expectedProfile);

            // when & then
            mockMvc.perform(get("/api/v1/auth/me").with(authentication(auth)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(1))
                    .andExpect(jsonPath("$.username").value("testuser"))
                    .andExpect(jsonPath("$.email").value("test@example.com"))
                    .andExpect(jsonPath("$.role").value("USER"))
                    // WIKI4AI-73: language preference is part of the profile contract
                    .andExpect(jsonPath("$.language").value("en"))
                    .andExpect(jsonPath("$.createdAt").exists());
        }

        @Test
        @DisplayName("Should return 200 with admin role when user is admin")
        void shouldReturnAdminProfileWhenAuthenticated() throws Exception {
            // given
            String username = "admin";
            UserDTO expectedProfile = createSampleUserDto(2L, username, "admin@example.com", Role.ADMIN);

            List<SimpleGrantedAuthority> authorities = Collections.singletonList(
                    new SimpleGrantedAuthority("ROLE_USER"));
            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(username, null, authorities);

            given(authService.getProfileByUsername(username)).willReturn(expectedProfile);

            // when & then
            mockMvc.perform(get("/api/v1/auth/me").with(authentication(auth)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(2))
                    .andExpect(jsonPath("$.username").value("admin"))
                    .andExpect(jsonPath("$.role").value("ADMIN"));
        }

        @Test
        @DisplayName("Should return 401 when no authentication (no token)")
        void shouldReturn401WhenNoAuthentication() throws Exception {
            // given - empty SecurityContext

            // when & then
            mockMvc.perform(get("/api/v1/auth/me"))
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

            given(authService.getProfileByUsername(username)).willReturn(null);

            // when & then
            mockMvc.perform(get("/api/v1/auth/me").with(authentication(auth)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("User not found in database"));
        }

        @Test
        @DisplayName("Should return 401 for anonymous user")
        void shouldReturn401ForAnonymousUser() throws Exception {
            // given - simulate anonymous authentication (no valid JWT)
            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken("anonymousUser", null, Collections.emptyList());

            // when & then
            mockMvc.perform(get("/api/v1/auth/me").with(authentication(auth)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").exists());
        }
    }
}
