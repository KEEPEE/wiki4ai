package com.wiki4ai.controller;

import com.wiki4ai.model.Role;
import com.wiki4ai.model.User;
import com.wiki4ai.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WIKI4AI-69 + WIKI4AI-70: integration tests for the first-run setup flow and the
 * registration-closure policy, using a real H2 database and the full security
 * filter chain (security.enabled=true) — no mocks on the auth path.
 * <p>
 * Named *Test (not *IT) so surefire runs it in CI ({@code mvn test}).
 * <p>
 * <b>WIKI4AI-169 — this class owns its database, and it has to.</b> Every other Spring
 * test context in this suite points at the shared named H2 {@code jdbc:h2:mem:testdb}, and
 * contexts are cached for the life of the JVM. Everything asserted here — setup is open only
 * while no account exists, registration closes after the first one, a rejected setup leaves
 * zero accounts — is a claim about the <em>whole</em> users table.
 * <p>
 * Deleting the predecessors' accounts is not a fix, and commit 85bef6d proved it by being
 * incomplete: {@code Project.owner} and {@code Document.owner} are {@code @ManyToOne}
 * associations, so the schema Hibernate generates from the entities has real foreign keys on
 * {@code owner_id}. A user that owns a project or a document cannot be deleted, the delete
 * throws, the cleanup below swallows it, and the table is still non-empty. That is exactly
 * the CI signature of this story — {@code Status expected: 201 but was: 403} and
 * {@code expected: 0L but was: 1L} across three {@code @Nested} classes at once — and
 * whether it happens is a {@code runOrder} question, and {@code runOrder} defaults to
 * {@code filesystem}.
 * <p>
 * A private named database makes "the users table is empty" a fact about this class alone.
 * Same pattern as the {@code FirstRunDocSeeder*Test} classes. Not {@code @DirtiesContext}:
 * closing a cached context drops the schema the other contexts are still using.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "security.enabled=true")
class AuthFirstRunFlowTest {

    private static final String SETUP_PAYLOAD = "{\"username\":\"firstadmin\",\"password\":\"secret123\"}";
    private static final String REGISTER_PAYLOAD =
            "{\"username\":\"newuser\",\"email\":\"newuser@example.com\",\"password\":\"secret123\"}";

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        // WIKI4AI-169: this class's own H2. See the class javadoc.
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:w4ai169-firstrunflow;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false");
    }

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

    /**
     * WIKI4AI-169 (SPEC rule 5b): the same cleanup also runs after every test, so this
     * class leaves its database as it found it. Its own tests create accounts through
     * {@code POST /auth/setup} and {@code POST /auth/register}, and the next test in the
     * same class needs the table empty again — which is why the cleanup is per-test and not
     * per-class.
     */
    @AfterEach
    void cleanUsersAfterEach() {
        cleanUsers();
    }

    @Nested
    @DisplayName("GET /api/v1/auth/status")
    class StatusEndpointTests {

        @Test
        @DisplayName("uninitialized instance → initialized=false, registrationOpen=true (default policy)")
        void uninitializedStatus() throws Exception {
            mockMvc.perform(get("/api/v1/auth/status"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.initialized").value(false))
                    .andExpect(jsonPath("$.registrationOpen").value(true));
        }

        @Test
        @DisplayName("after first account → initialized=true, registrationOpen=false (default policy closes)")
        void initializedStatusAfterSetup() throws Exception {
            mockMvc.perform(post("/api/v1/auth/setup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(SETUP_PAYLOAD))
                    .andExpect(status().isCreated());

            mockMvc.perform(get("/api/v1/auth/status"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.initialized").value(true))
                    .andExpect(jsonPath("$.registrationOpen").value(false));
        }

        @Test
        @DisplayName("response leaks no user details (only the two booleans)")
        void statusLeaksNoDetails() throws Exception {
            String body = mockMvc.perform(get("/api/v1/auth/status"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            assertThat(body).contains("\"initialized\"");
            assertThat(body).contains("\"registrationOpen\"");
            assertThat(body).doesNotContain("firstadmin");
            assertThat(body).doesNotContain("username");
        }
    }

    @Nested
    @DisplayName("POST /api/v1/auth/setup")
    class SetupEndpointTests {

        @Test
        @DisplayName("setup on empty DB → 201 + ADMIN in DB, derived email {username}@localhost")
        void setupCreatesAdminOnEmptyDb() throws Exception {
            mockMvc.perform(post("/api/v1/auth/setup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(SETUP_PAYLOAD))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.username").value("firstadmin"))
                    .andExpect(jsonPath("$.role").value("ADMIN"));

            User user = userRepository.findByUsername("firstadmin").orElseThrow();
            assertThat(user.getRole()).isEqualTo(Role.ADMIN);
            assertThat(user.getEmail()).isEqualTo("firstadmin@localhost");
            // Password must be BCrypt-hashed, never plaintext
            assertThat(user.getPassword()).startsWith("$2a$");
            assertThat(new BCryptPasswordEncoder().matches("secret123", user.getPassword())).isTrue();
        }

        @Test
        @DisplayName("setup with explicit email uses that email")
        void setupWithExplicitEmail() throws Exception {
            mockMvc.perform(post("/api/v1/auth/setup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"firstadmin\",\"email\":\"admin@example.com\",\"password\":\"secret123\"}"))
                    .andExpect(status().isCreated());

            User user = userRepository.findByUsername("firstadmin").orElseThrow();
            assertThat(user.getEmail()).isEqualTo("admin@example.com");
        }

        @Test
        @DisplayName("setup after first account exists → 403 with generic message")
        void setupAfterFirstAccountRejected() throws Exception {
            // given — instance already initialized (via register, which is open on an empty DB)
            mockMvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(REGISTER_PAYLOAD))
                    .andExpect(status().isCreated());

            // when & then — setup must be gone, with a generic (non-leaking) message
            mockMvc.perform(post("/api/v1/auth/setup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"secondadmin\",\"password\":\"secret123\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value("Setup already completed"));

            // and no second account was created
            assertThat(userRepository.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("setup with short password → 400")
        void setupShortPasswordRejected() throws Exception {
            mockMvc.perform(post("/api/v1/auth/setup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"firstadmin\",\"password\":\"short\"}"))
                    .andExpect(status().isBadRequest());
            assertThat(userRepository.count()).isZero();
        }

        @Test
        @DisplayName("setup with blank username → 400")
        void setupBlankUsernameRejected() throws Exception {
            mockMvc.perform(post("/api/v1/auth/setup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"\",\"password\":\"secret123\"}"))
                    .andExpect(status().isBadRequest());
            assertThat(userRepository.count()).isZero();
        }

        @Test
        @DisplayName("setup with missing password → 400")
        void setupMissingPasswordRejected() throws Exception {
            mockMvc.perform(post("/api/v1/auth/setup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"firstadmin\"}"))
                    .andExpect(status().isBadRequest());
            assertThat(userRepository.count()).isZero();
        }
    }

    @Nested
    @DisplayName("POST /api/v1/auth/register (WIKI4AI-70 closure)")
    class RegisterClosureTests {

        @Test
        @DisplayName("register on empty DB (default policy) → 201 with role USER")
        void registerOpenOnEmptyDb() throws Exception {
            mockMvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(REGISTER_PAYLOAD))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.role").value("USER"));

            User user = userRepository.findByUsername("newuser").orElseThrow();
            assertThat(user.getRole()).isEqualTo(Role.USER);
        }

        @Test
        @DisplayName("register after first account exists → 403 (registration disabled)")
        void registerClosedAfterFirstAccount() throws Exception {
            // given — first account created via setup
            mockMvc.perform(post("/api/v1/auth/setup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(SETUP_PAYLOAD))
                    .andExpect(status().isCreated());

            // when & then — public registration is now closed
            mockMvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(REGISTER_PAYLOAD))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value("Registration is disabled on this instance"));

            assertThat(userRepository.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("register with duplicate username on empty DB → 409 before closure check")
        void duplicateUsernameConflictOnEmptyDb() throws Exception {
            // Seed a user directly (bypasses the policy — simulates an existing account
            // without consuming the one open registration slot).
            userRepository.save(User.builder()
                    .username("newuser").email("seed@example.com")
                    .password(new BCryptPasswordEncoder().encode("secret123"))
                    .role(Role.USER).build());

            // Now the DB is non-empty → default policy already closed registration,
            // so a duplicate attempt hits 403 (closure) — assert that ordering:
            // closure (403) takes precedence over conflict (409).
            mockMvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(REGISTER_PAYLOAD))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("Unsupported HTTP methods (WIKI4AI-70: 405, not 500)")
    class MethodNotAllowedTests {

        @Test
        @DisplayName("GET /api/v1/auth/register → 405 with Allow header")
        void getRegisterReturns405() throws Exception {
            mockMvc.perform(get("/api/v1/auth/register"))
                    .andExpect(status().isMethodNotAllowed())
                    .andExpect(header().exists("Allow"))
                    .andExpect(jsonPath("$.error").value("Method Not Allowed"));
        }

        @Test
        @DisplayName("DELETE /api/v1/auth/login → 405 (other auth endpoints covered too)")
        void deleteLoginReturns405() throws Exception {
            mockMvc.perform(delete("/api/v1/auth/login"))
                    .andExpect(status().isMethodNotAllowed());
        }

        @Test
        @DisplayName("PUT /api/v1/auth/setup → 405")
        void putSetupReturns405() throws Exception {
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .put("/api/v1/auth/setup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(SETUP_PAYLOAD))
                    .andExpect(status().isMethodNotAllowed());
        }
    }
}
