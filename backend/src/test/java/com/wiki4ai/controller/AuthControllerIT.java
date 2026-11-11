package com.wiki4ai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wiki4ai.dto.LoginRequestDTO;
import com.wiki4ai.dto.RegisterRequestDTO;
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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for AuthController using real database (H2).
 * Tests register endpoint with valid/invalid data, and login with correct/incorrect credentials.
 * <p>
 * <b>WIKI4AI-202 — this class had never run.</b> Surefire's default includes cover
 * {@code Test*}, {@code *Test}, {@code *Tests} and {@code *TestCase}; {@code *IT} is not
 * one of them, so all 15 tests here were compiled and silently dropped on every build.
 * Two annotations were missing underneath that, and both only became visible once the
 * class actually executed:
 * <ul>
 *   <li><b>{@code @AutoConfigureMockMvc}</b> — the class autowires {@link MockMvc}, but
 *   {@code @SpringBootTest} alone does not register that bean (it creates a mock
 *   {@code WebApplicationContext}, not a {@code MockMvc}). Every test errored with
 *   {@code No qualifying bean of type 'org.springframework.test.web.servlet.MockMvc'}
 *   before a single assertion ran. {@code @WebMvcTest} was the other option and is the
 *   wrong one here: this class asserts on rows written to the database, which needs the
 *   real service and repository beans, not a sliced web context.</li>
 *   <li><b>{@code security.enabled=true}</b> — {@link AuthController} is
 *   {@code @ConditionalOnProperty(security.enabled=true)} and the test profile turns
 *   security off, so without this the controller bean does not exist and every request
 *   is a 404. Same pairing as {@code AuthControllerMeTest} and
 *   {@code AuthRefreshEndpointIntegrationTest}.</li>
 *   <li><b>{@code auth.registration.open=true}</b> — WIKI4AI-70 made self-registration
 *   close as soon as the first account exists. The two duplicate-key tests seed an
 *   account through the repository first, so under the default policy the request is
 *   refused 403 by the registration gate before the uniqueness check is ever reached,
 *   and the 409 they assert is unreachable. Those tests are about the uniqueness rule,
 *   so the class opts into open registration rather than weakening the assertion.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {"security.enabled=true", "auth.registration.open=true"})
class AuthControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

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

    @Nested
    @DisplayName("Register Endpoint Tests")
    class RegisterEndpointTests {

        @Test
        @DisplayName("Should register a new user successfully")
        void shouldRegisterNewUser() throws Exception {
            // when & then
            mockMvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                        "username": "newuser",
                                        "email": "newuser@example.com",
                                        "password": "password123"
                                    }
                                    """))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").exists())
                    .andExpect(jsonPath("$.username").value("newuser"))
                    .andExpect(jsonPath("$.email").value("newuser@example.com"))
                    .andExpect(jsonPath("$.createdAt").exists());
        }

        @Test
        @DisplayName("Should reject registration with duplicate username")
        void shouldRejectDuplicateUsername() throws Exception {
            // given - register first user
            userRepository.save(User.builder().username("existing").email("existing@example.com").password("$2a$10.hashed").build());

            // when & then
            mockMvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                        "username": "existing",
                                        "email": "other@example.com",
                                        "password": "password123"
                                    }
                                    """))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").exists());
        }

        @Test
        @DisplayName("Should reject registration with duplicate email")
        void shouldRejectDuplicateEmail() throws Exception {
            // given - register first user
            userRepository.save(User.builder().username("existing").email("existing@example.com").password("$2a$10.hashed").build());

            // when & then
            mockMvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                        "username": "otheruser",
                                        "email": "existing@example.com",
                                        "password": "password123"
                                    }
                                    """))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").exists());
        }

        @Test
        @DisplayName("Should reject registration with short password")
        void shouldRejectShortPassword() throws Exception {
            // when & then
            mockMvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                        "username": "newuser",
                                        "email": "new@example.com",
                                        "password": "short"
                                    }
                                    """))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Should reject registration with blank username")
        void shouldRejectBlankUsername() throws Exception {
            // when & then
            mockMvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                        "username": "",
                                        "email": "new@example.com",
                                        "password": "password123"
                                    }
                                    """))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Should reject registration with blank email")
        void shouldRejectBlankEmail() throws Exception {
            // when & then
            mockMvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                        "username": "newuser",
                                        "email": "",
                                        "password": "password123"
                                    }
                                    """))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Should reject registration with blank password")
        void shouldRejectBlankPassword() throws Exception {
            // when & then
            mockMvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                        "username": "newuser",
                                        "email": "new@example.com",
                                        "password": ""
                                    }
                                    """))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Should store password as BCrypt hash")
        void shouldStorePasswordAsHash() throws Exception {
            // given - register a user
            mockMvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                        "username": "hashuser",
                                        "email": "hash@example.com",
                                        "password": "secret123"
                                    }
                                    """))
                    .andExpect(status().isCreated());

            // then - verify password is hashed in DB
            User user = userRepository.findByUsername("hashuser").orElseThrow();
            assertThat(user.getPassword()).startsWith("$2a$");
            assertThat(user.getPassword()).isNotEqualTo("secret123");
        }
    }

    @Nested
    @DisplayName("Login Endpoint Tests")
    class LoginEndpointTests {

        @Test
        @DisplayName("Should login with correct credentials")
        void shouldLoginWithCorrectCredentials() throws Exception {
            // given - register a user
            String rawPassword = "secret123";
            BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
            String hashed = encoder.encode(rawPassword);
            userRepository.save(User.builder().username("loginuser").email("login@example.com").password(hashed).build());

            // when & then
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                        "username": "loginuser",
                                        "password": "secret123"
                                    }
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessToken").exists())
                    .andExpect(jsonPath("$.refreshToken").exists())
                    .andExpect(jsonPath("$.user.username").value("loginuser"))
                    .andExpect(jsonPath("$.user.email").value("login@example.com"))
                    .andExpect(jsonPath("$.user.id").exists());
        }

        @Test
        @DisplayName("Should reject login with wrong password")
        void shouldRejectWrongPassword() throws Exception {
            // given - register a user
            String rawPassword = "secret123";
            BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
            String hashed = encoder.encode(rawPassword);
            userRepository.save(User.builder().username("wrongpw").email("wrong@example.com").password(hashed).build());

            // when & then
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                        "username": "wrongpw",
                                        "password": "wrongpassword"
                                    }
                                    """))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").exists());
        }

        @Test
        @DisplayName("Should reject login with non-existent username")
        void shouldRejectNonExistentUsername() throws Exception {
            // when & then
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                        "username": "nobody",
                                        "password": "secret123"
                                    }
                                    """))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").exists());
        }

        @Test
        @DisplayName("Should reject login with blank username")
        void shouldRejectBlankUsername() throws Exception {
            // when & then
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                        "username": "",
                                        "password": "secret123"
                                    }
                                    """))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Should reject login with blank password")
        void shouldRejectBlankPassword() throws Exception {
            // when & then
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                        "username": "loginuser",
                                        "password": ""
                                    }
                                    """))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Should return user DTO without password in login response")
        void shouldReturnUserWithoutPassword() throws Exception {
            // given - register a user
            String rawPassword = "secret123";
            BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
            String hashed = encoder.encode(rawPassword);
            userRepository.save(User.builder().username("nopw").email("nopw@example.com").password(hashed).build());

            // when & then - verify password is NOT in response
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                        "username": "nopw",
                                        "password": "secret123"
                                    }
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.user").exists())
                    .andExpect(jsonPath("$.user.password").doesNotExist());
        }
    }

    @Nested
    @DisplayName("Auth Health Check")
    class HealthCheckTests {

        @Test
        @DisplayName("Should return health status for auth service")
        void shouldReturnHealthStatus() throws Exception {
            mockMvc.perform(get("/api/v1/auth/health"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"))
                    .andExpect(jsonPath("$.service").value("auth"));
        }
    }
}
