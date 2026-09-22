package com.wiki4ai.controller;

import com.wiki4ai.model.Role;
import com.wiki4ai.model.User;
import com.wiki4ai.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WIKI4AI-70: integration tests for the explicit opt-in open registration policy
 * ({@code auth.registration.open=true}). Separate Spring context (different
 * property set) from {@link AuthFirstRunFlowTest}, which covers the default policy.
 * <p>
 * Named *Test (not *IT) so surefire runs it in CI ({@code mvn test}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {"security.enabled=true", "auth.registration.open=true"})
class AuthRegistrationOptInTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void cleanUsers() {
        List<User> all = userRepository.findAll();
        for (User u : all) {
            try {
                userRepository.delete(u);
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    @DisplayName("opt-in: register stays open after the first account exists → 201")
    void registrationStaysOpenAfterFirstAccount() throws Exception {
        // First account
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"userone\",\"email\":\"one@example.com\",\"password\":\"secret123\"}"))
                .andExpect(status().isCreated());

        // Second account — would be 403 under the default policy, 201 with opt-in
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"usertwo\",\"email\":\"two@example.com\",\"password\":\"secret123\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("USER"));

        assertThat(userRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("opt-in: duplicate username still → 409")
    void duplicateUsernameStillConflict() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"dupuser\",\"email\":\"dup@example.com\",\"password\":\"secret123\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"dupuser\",\"email\":\"other@example.com\",\"password\":\"secret123\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Username is already taken"));
    }

    @Test
    @DisplayName("opt-in: status reports registrationOpen=true even when initialized")
    void statusReportsRegistrationOpen() throws Exception {
        userRepository.save(User.builder()
                .username("seedadmin").email("seed@example.com")
                .password(new BCryptPasswordEncoder().encode("secret123"))
                .role(Role.ADMIN).build());

        mockMvc.perform(get("/api/v1/auth/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.initialized").value(true))
                .andExpect(jsonPath("$.registrationOpen").value(true));
    }
}
