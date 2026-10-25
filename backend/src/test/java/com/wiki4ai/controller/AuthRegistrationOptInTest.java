package com.wiki4ai.controller;

import com.wiki4ai.model.Role;
import com.wiki4ai.model.User;
import com.wiki4ai.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
 * <p>
 * <b>WIKI4AI-169 — this class owns its database.</b> It asserts
 * {@code userRepository.count() == 2} after registering two accounts, and it depends on
 * {@code POST /auth/register} being open, which the service decides from the users table.
 * Both are claims about the whole table. On the shared {@code jdbc:h2:mem:testdb} the
 * cleanup below cannot always succeed — {@code Project.owner} and {@code Document.owner}
 * are {@code @ManyToOne}, so a predecessor's account that owns a project or a document
 * cannot be deleted and the swallowed exception leaves it in place. A private named H2
 * makes the count a fact about this class alone. Not {@code @DirtiesContext}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {"security.enabled=true", "auth.registration.open=true"})
class AuthRegistrationOptInTest {

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        // WIKI4AI-169: this class's own H2. See the class javadoc.
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:w4ai169-regoptin;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false");
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
     * WIKI4AI-169 (SPEC rule 5b): cleanup runs after every test too, so the class leaves
     * its own database as it found it instead of handing the next test a table that already
     * looks "initialized".
     */
    @AfterEach
    void cleanUsersAfterEach() {
        cleanUsers();
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
