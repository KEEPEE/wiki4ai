package com.wiki4ai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wiki4ai.dto.ProfileUpdateRequestDTO;
import com.wiki4ai.model.User;
import com.wiki4ai.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for AuthController PUT /me endpoint using real database (H2).
 * Tests profile update scenarios: email, username, password change, uniqueness validation, and auth.
 */
@SpringBootTest
@ActiveProfiles("test")
class AuthControllerUpdateProfileIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @BeforeEach
    void setUp() {
        // Clean up before each test to ensure isolation
        List<User> all = userRepository.findAll();
        for (User u : all) {
            try {
                userRepository.delete(u);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Helper to create an authenticated MockMvc request processor for a given username.
     */
    private org.springframework.security.core.Authentication createAuth(String username) {
        return new UsernamePasswordAuthenticationToken(
                username, null, Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));
    }

    @Nested
    @DisplayName("PUT /api/v1/auth/me - Update profile")
    class UpdateProfileTests {

        @Test
        @DisplayName("Should update email successfully - 200 OK")
        void shouldUpdateEmailSuccessfully() throws Exception {
            // given
            String hashed = encoder.encode("password123");
            userRepository.save(User.builder().username("testuser").email("old@example.com").password(hashed).build());

            ProfileUpdateRequestDTO request = ProfileUpdateRequestDTO.builder()
                    .email("new@example.com")
                    .build();

            // when & then
            mockMvc.perform(put("/api/v1/auth/me")
                            .with(authentication(createAuth("testuser")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.username").value("testuser"))
                    .andExpect(jsonPath("$.email").value("new@example.com"));

            // verify in DB
            User updated = userRepository.findByUsername("testuser").orElseThrow();
            assertThat(updated.getEmail()).isEqualTo("new@example.com");
        }

        @Test
        @DisplayName("Should update username successfully - 200 OK")
        void shouldUpdateUsernameSuccessfully() throws Exception {
            // given
            String hashed = encoder.encode("password123");
            userRepository.save(User.builder().username("oldname").email("user@example.com").password(hashed).build());

            ProfileUpdateRequestDTO request = ProfileUpdateRequestDTO.builder()
                    .username("newname")
                    .build();

            // when & then
            mockMvc.perform(put("/api/v1/auth/me")
                            .with(authentication(createAuth("oldname")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.username").value("newname"))
                    .andExpect(jsonPath("$.email").value("user@example.com"));

            // verify in DB - user is found by new username
            User updated = userRepository.findByUsername("newname").orElseThrow();
            assertThat(updated.getUsername()).isEqualTo("newname");
        }

        @Test
        @DisplayName("Should update email and username together")
        void shouldUpdateEmailAndUsernameTogether() throws Exception {
            // given
            String hashed = encoder.encode("password123");
            userRepository.save(User.builder().username("olduser").email("old@example.com").password(hashed).build());

            ProfileUpdateRequestDTO request = ProfileUpdateRequestDTO.builder()
                    .username("newuser")
                    .email("new@example.com")
                    .build();

            // when & then
            mockMvc.perform(put("/api/v1/auth/me")
                            .with(authentication(createAuth("olduser")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.username").value("newuser"))
                    .andExpect(jsonPath("$.email").value("new@example.com"));
        }

        @Test
        @DisplayName("Should reject update to username that already exists - 409 Conflict")
        void shouldRejectDuplicateUsername() throws Exception {
            // given
            String hashed = encoder.encode("password123");
            userRepository.save(User.builder().username("existing").email("existing@example.com").password(hashed).build());
            userRepository.save(User.builder().username("testuser").email("test@example.com").password(hashed).build());

            ProfileUpdateRequestDTO request = ProfileUpdateRequestDTO.builder()
                    .username("existing") // try to take existing username
                    .build();

            // when & then
            mockMvc.perform(put("/api/v1/auth/me")
                            .with(authentication(createAuth("testuser")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("Username is already taken"));
        }

        @Test
        @DisplayName("Should reject update to email that already exists - 409 Conflict")
        void shouldRejectDuplicateEmail() throws Exception {
            // given
            String hashed = encoder.encode("password123");
            userRepository.save(User.builder().username("existing").email("taken@example.com").password(hashed).build());
            userRepository.save(User.builder().username("testuser").email("test@example.com").password(hashed).build());

            ProfileUpdateRequestDTO request = ProfileUpdateRequestDTO.builder()
                    .email("taken@example.com") // try to take existing email
                    .build();

            // when & then
            mockMvc.perform(put("/api/v1/auth/me")
                            .with(authentication(createAuth("testuser")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("Email is already registered"));
        }

        @Test
        @DisplayName("Should change password with correct currentPassword - 200 OK")
        void shouldChangePasswordWithCorrectCurrentPassword() throws Exception {
            // given
            String rawPassword = "oldpassword123";
            String hashed = encoder.encode(rawPassword);
            userRepository.save(User.builder().username("testuser").email("test@example.com").password(hashed).build());

            ProfileUpdateRequestDTO request = ProfileUpdateRequestDTO.builder()
                    .currentPassword(rawPassword)
                    .newPassword("newSecurePassword456")
                    .build();

            // when & then
            mockMvc.perform(put("/api/v1/auth/me")
                            .with(authentication(createAuth("testuser")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.username").value("testuser"));

            // verify password is changed in DB (new hash != old hash)
            User updated = userRepository.findByUsername("testuser").orElseThrow();
            assertThat(updated.getPassword()).isNotEqualTo(hashed);
            assertThat(encoder.matches("newSecurePassword456", updated.getPassword())).isTrue();
        }

        @Test
        @DisplayName("Should reject password change with wrong currentPassword - 400 Bad Request")
        void shouldRejectPasswordChangeWithWrongCurrentPassword() throws Exception {
            // given
            String hashed = encoder.encode("realpassword123");
            userRepository.save(User.builder().username("testuser").email("test@example.com").password(hashed).build());

            ProfileUpdateRequestDTO request = ProfileUpdateRequestDTO.builder()
                    .currentPassword("wrongpassword")
                    .newPassword("newSecurePassword456")
                    .build();

            // when & then
            mockMvc.perform(put("/api/v1/auth/me")
                            .with(authentication(createAuth("testuser")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("Current password is incorrect"));
        }

        @Test
        @DisplayName("Should reject password change without currentPassword - 400 Bad Request")
        void shouldRejectPasswordChangeWithoutCurrentPassword() throws Exception {
            // given
            String hashed = encoder.encode("password123");
            userRepository.save(User.builder().username("testuser").email("test@example.com").password(hashed).build());

            ProfileUpdateRequestDTO request = ProfileUpdateRequestDTO.builder()
                    .newPassword("newSecurePassword456")
                    // no currentPassword provided
                    .build();

            // when & then
            mockMvc.perform(put("/api/v1/auth/me")
                            .with(authentication(createAuth("testuser")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("Current password is required to change password"));
        }

        @Test
        @DisplayName("Should return 401 when no authentication (no token)")
        void shouldReturn401WhenNoAuthentication() throws Exception {
            ProfileUpdateRequestDTO request = ProfileUpdateRequestDTO.builder()
                    .email("new@example.com")
                    .build();

            mockMvc.perform(put("/api/v1/auth/me")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").exists());
        }

        @Test
        @DisplayName("Should allow empty request body (no-op update)")
        void shouldAllowEmptyRequestBody() throws Exception {
            // given
            String hashed = encoder.encode("password123");
            userRepository.save(User.builder().username("testuser").email("test@example.com").password(hashed).build());

            ProfileUpdateRequestDTO request = new ProfileUpdateRequestDTO(); // all fields null

            // when & then
            mockMvc.perform(put("/api/v1/auth/me")
                            .with(authentication(createAuth("testuser")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.username").value("testuser"))
                    .andExpect(jsonPath("$.email").value("test@example.com"));

            // verify nothing changed in DB
            User updated = userRepository.findByUsername("testuser").orElseThrow();
            assertThat(updated.getEmail()).isEqualTo("test@example.com");
        }

        @Test
        @DisplayName("Should return user DTO without password in response")
        void shouldNotReturnPasswordInResponse() throws Exception {
            // given
            String hashed = encoder.encode("password123");
            userRepository.save(User.builder().username("testuser").email("test@example.com").password(hashed).build());

            ProfileUpdateRequestDTO request = ProfileUpdateRequestDTO.builder()
                    .email("updated@example.com")
                    .build();

            // when & then
            mockMvc.perform(put("/api/v1/auth/me")
                            .with(authentication(createAuth("testuser")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.password").doesNotExist());
        }

        @Test
        @DisplayName("Should reject update with invalid email format - 400 Bad Request")
        void shouldRejectInvalidEmailFormat() throws Exception {
            // given
            String hashed = encoder.encode("password123");
            userRepository.save(User.builder().username("testuser").email("test@example.com").password(hashed).build());

            ProfileUpdateRequestDTO request = ProfileUpdateRequestDTO.builder()
                    .email("not-an-email")
                    .build();

            // when & then
            mockMvc.perform(put("/api/v1/auth/me")
                            .with(authentication(createAuth("testuser")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }
    }
}
