package com.wiki4ai.controller;

import com.wiki4ai.model.User;
import com.wiki4ai.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WIKI4AI-73: integration tests for the per-user UI language preference.
 * <p>
 * Covers the full contract the WebUI relies on:
 * <ul>
 *   <li>new users default to "en" (V12 column default / entity default);</li>
 *   <li>{@code GET /api/v1/auth/me} exposes the preference;</li>
 *   <li>{@code PUT /api/v1/auth/me} updates it (only supported locales accepted);</li>
 *   <li>{@code POST /api/v1/auth/login} returns it in the user object so the
 *       frontend can initialize i18n immediately after login.</li>
 * </ul>
 * Named *Test (not *IT) so surefire runs it in CI ({@code mvn test}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "security.enabled=true")
class AuthProfileLanguageTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

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

    private org.springframework.security.core.Authentication createAuth(String username) {
        return new UsernamePasswordAuthenticationToken(
                username, null, Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));
    }

    @Nested
    @DisplayName("Profile language preference (WIKI4AI-73)")
    class ProfileLanguageTests {

        @Test
        @DisplayName("new user defaults to 'en' — GET /me and DB both report it")
        void newUserDefaultsToEnglish() throws Exception {
            userRepository.save(User.builder().username("testuser").email("test@example.com")
                    .password(encoder.encode("password123")).build());

            mockMvc.perform(get("/api/v1/auth/me").with(authentication(createAuth("testuser"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.language").value("en"));

            User stored = userRepository.findByUsername("testuser").orElseThrow();
            assertThat(stored.getLanguage()).isEqualTo("en");
        }

        @Test
        @DisplayName("PUT /me with language=sk → 200 and persisted on the account")
        void updateLanguageToSlovak() throws Exception {
            userRepository.save(User.builder().username("testuser").email("test@example.com")
                    .password(encoder.encode("password123")).build());

            mockMvc.perform(put("/api/v1/auth/me")
                            .with(authentication(createAuth("testuser")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"language\":\"sk\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.language").value("sk"));

            User updated = userRepository.findByUsername("testuser").orElseThrow();
            assertThat(updated.getLanguage()).isEqualTo("sk");
        }

        @Test
        @DisplayName("PUT /me switches language back to 'en'")
        void switchLanguageBackToEnglish() throws Exception {
            userRepository.save(User.builder().username("testuser").email("test@example.com")
                    .password(encoder.encode("password123")).language("sk").build());

            mockMvc.perform(put("/api/v1/auth/me")
                            .with(authentication(createAuth("testuser")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"language\":\"en\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.language").value("en"));

            User updated = userRepository.findByUsername("testuser").orElseThrow();
            assertThat(updated.getLanguage()).isEqualTo("en");
        }

        @Test
        @DisplayName("PUT /me rejects unsupported language — 400, preference unchanged")
        void rejectUnsupportedLanguage() throws Exception {
            userRepository.save(User.builder().username("testuser").email("test@example.com")
                    .password(encoder.encode("password123")).build());

            mockMvc.perform(put("/api/v1/auth/me")
                            .with(authentication(createAuth("testuser")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"language\":\"fr\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").exists());

            User stored = userRepository.findByUsername("testuser").orElseThrow();
            assertThat(stored.getLanguage()).isEqualTo("en");
        }

        @Test
        @DisplayName("PUT /me without language leaves the existing preference untouched")
        void omittingLanguageIsNoOp() throws Exception {
            userRepository.save(User.builder().username("testuser").email("old@example.com")
                    .password(encoder.encode("password123")).language("sk").build());

            mockMvc.perform(put("/api/v1/auth/me")
                            .with(authentication(createAuth("testuser")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"new@example.com\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.email").value("new@example.com"))
                    .andExpect(jsonPath("$.language").value("sk"));

            User updated = userRepository.findByUsername("testuser").orElseThrow();
            assertThat(updated.getLanguage()).isEqualTo("sk");
        }
    }

    @Nested
    @DisplayName("Login response carries the language preference (WIKI4AI-73)")
    class LoginResponseLanguageTests {

        @Test
        @DisplayName("login returns user.language so i18n initializes without an extra roundtrip")
        void loginReturnsLanguage() throws Exception {
            userRepository.save(User.builder().username("loginuser").email("login@example.com")
                    .password(encoder.encode("secret123")).language("sk").build());

            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"loginuser\",\"password\":\"secret123\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessToken").exists())
                    .andExpect(jsonPath("$.refreshToken").exists())
                    .andExpect(jsonPath("$.user.username").value("loginuser"))
                    .andExpect(jsonPath("$.user.language").value("sk"));
        }

        @Test
        @DisplayName("login for a user without an explicit preference returns the 'en' default")
        void loginReturnsDefaultLanguage() throws Exception {
            userRepository.save(User.builder().username("loginuser").email("login@example.com")
                    .password(encoder.encode("secret123")).build());

            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"loginuser\",\"password\":\"secret123\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.user.language").value("en"));
        }
    }
}
