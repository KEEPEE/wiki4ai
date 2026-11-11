package com.wiki4ai.controller;

import com.wiki4ai.config.JwtUtil;
import com.wiki4ai.dto.TokenCreateRequestDTO;
import com.wiki4ai.model.Role;
import com.wiki4ai.model.User;
import com.wiki4ai.repository.ApiTokenRepository;
import com.wiki4ai.repository.UserRepository;
import com.wiki4ai.service.ApiTokenService;
import com.wiki4ai.service.AuthService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A token's scopes are enforced, and an administrator's role no longer outranks them
 * (WIKI4AI-197, REV-2 P0 #5).
 *
 * <p><b>Two separate holes, one credential.</b>
 *
 * <p><i>The scopes were decoration.</i> A {@code w4a_} token row has a {@code scopes}
 * column, the filter turns each scope into a {@code SCOPE_*} authority, and a caller
 * chooses the scopes when creating the token — so a token could be created with
 * {@code ["read"]} and the operator had every reason to believe it was read-only. But
 * exactly two methods in the whole application read those authorities, both in
 * {@code ResourceService}. Everything else asked only "is the caller authenticated?",
 * which every scope answers identically. A read-only token could delete a document.
 * {@code ApiTokenScopeAuthorizationManager} now answers verb against scope for every
 * request a token authenticates, and leaves web sessions alone — a browser session
 * declares no scopes, and the frontend would break if saving a page suddenly required a
 * {@code write} scope.
 *
 * <p><i>The role was a skeleton key.</i> Every administrative check asked whether the
 * <b>account</b> behind the caller had the ADMIN role, and stopped there. A token defaults
 * to {@code read}+{@code write}, so an administrator who issued a token for a read-only
 * integration handed out a credential that could list every user, change a role, delete an
 * account, run the embedding backfill and import or export the whole instance — because
 * the account was an admin and the token's own scopes were never consulted.
 * {@code EffectiveAdmin} is now the single definition: ADMIN role <b>and</b> (not a token,
 * or the token carries the {@code admin} scope).
 *
 * <p><b>Why both halves are load-bearing.</b> Drop the role and a token could be granted
 * administrative power for an account that never had it. Drop the scope requirement and
 * revoking a leaked administrator token would not stop it — the credential would keep
 * acting with its owner's full authority no matter what its scopes say, which is the
 * complaint being closed.
 *
 * <p><b>The deployment note.</b> Existing tokens default to {@code read}+{@code write}, so
 * an administrator's ordinary token now gets 403 on administrative endpoints where it
 * previously succeeded. That is the fix, and the body says so and names the scope to ask
 * for. The MCP server calls no admin, token or embedding endpoint, so it is unaffected.
 *
 * <p>Runs on the shared {@code security.enabled=true} context, so it adds no Spring
 * context — one past the test context cache and LRU eviction closes a {@code create-drop}
 * context, dropping the H2 schema other classes are reading.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "security.enabled=true")
class EffectiveAdminScopeEnforcementIntegrationTest {

    private static final String OPERATOR = "scope_operator";   // ADMIN
    private static final String MEMBER = "scope_member";       // USER

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ApiTokenRepository apiTokenRepository;

    @Autowired
    private ApiTokenService apiTokenService;

    @Autowired
    private AuthService authService;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        cleanState();
        saveUser(OPERATOR, Role.ADMIN);
        saveUser(MEMBER, Role.USER);
    }

    @AfterEach
    void cleanUp() {
        cleanState();
    }

    /** Row-level cleanup: the H2 database is shared by name across the whole JVM. */
    private void cleanState() {
        apiTokenRepository.deleteAll();
        jdbcTemplate.execute("DELETE FROM refresh_tokens");
        for (String name : List.of(OPERATOR, MEMBER)) {
            userRepository.findByUsername(name).ifPresent(userRepository::delete);
        }
        apiTokenService.clearValidationCache();
    }

    private void saveUser(String username, Role role) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@test.local");
        user.setPassword(authService.encodePassword("a-real-password-123"));
        user.setRole(role);
        userRepository.save(user);
    }

    /** Issue a real hashed token with explicit scopes and return its plaintext. */
    private String tokenFor(String username, List<String> scopes) {
        return apiTokenService.createToken(
                TokenCreateRequestDTO.builder().name("scope test").scopes(scopes).build(), username)
                .getToken();
    }

    /** The id of the token row for a plaintext, so a token can rotate itself. */
    private long idOf(String plaintext) {
        Long id = jdbcTemplate.queryForObject("SELECT id FROM api_tokens WHERE token_hash = ?",
                Long.class, sha256Hex(plaintext));
        assertNotNull(id, "the token this test issued must have a row");
        return id;
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the JDK", impossible);
        }
    }

    // ── the scopes are enforced ───────────────────────────────────────────────

    @Nested
    @DisplayName("WIKI4AI-197: a token's scopes decide what it may do")
    class ScopeEnforcement {

        @Test
        @DisplayName("read reaches GET, and a mutating method is refused naming the scope")
        void readOnlyTokenCannotMutate() throws Exception {
            String readOnly = tokenFor(MEMBER, List.of("read"));

            mockMvc.perform(get("/api/v1/tokens")
                            .header("Authorization", "Bearer " + readOnly))
                    .andExpect(status().isOk());

            mockMvc.perform(post("/api/v1/tokens/" + idOf(readOnly) + "/rotate")
                            .header("Authorization", "Bearer " + readOnly))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("Forbidden"))
                    // The body has to name the missing scope: an agent that gets a bare
                    // 403 cannot tell a missing scope from a missing project grant, and
                    // the remedies differ (be issued another token versus be added to the
                    // project).
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("'write' scope")));
        }

        @Test
        @DisplayName("read+write may mutate — the default token keeps working")
        void defaultScopesMayMutate() throws Exception {
            // DEFAULT_SCOPES is read+write, so every token minted by the frontend or by an
            // agent that asked for nothing must be unaffected by this commit.
            String ordinary = tokenFor(MEMBER, List.of("read", "write"));

            mockMvc.perform(post("/api/v1/tokens/" + idOf(ordinary) + "/rotate")
                            .header("Authorization", "Bearer " + ordinary))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("write does not imply read: a write-only token cannot GET")
        void writeDoesNotImplyRead() throws Exception {
            // The rule is "read answers GET/HEAD, write is needed for mutating methods",
            // and it is asserted in both directions so nobody quietly widens it later. A
            // write-only token is unusual; what matters is that the scopes mean exactly
            // what they say, in neither direction more than stated.
            String writeOnly = tokenFor(MEMBER, List.of("write"));

            mockMvc.perform(get("/api/v1/tokens")
                            .header("Authorization", "Bearer " + writeOnly))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("'read' scope")));
        }

        @Test
        @DisplayName("admin scope satisfies both, and a session needs neither")
        void adminScopeAndSessionsAreNotGated() throws Exception {
            String adminScoped = tokenFor(MEMBER, List.of("admin"));
            mockMvc.perform(get("/api/v1/tokens")
                            .header("Authorization", "Bearer " + adminScoped))
                    .andExpect(status().isOk());
            mockMvc.perform(post("/api/v1/tokens/" + idOf(adminScoped) + "/rotate")
                            .header("Authorization", "Bearer " + adminScoped))
                    .andExpect(status().isCreated());

            // A signed-in session declares no scopes at all. If the gate applied to it,
            // the frontend could not save a page.
            mockMvc.perform(get("/api/v1/tokens")
                            .header("Authorization", "Bearer " + jwtUtil.generateToken(MEMBER)))
                    .andExpect(status().isOk());
        }
    }

    // ── the role no longer outranks the scopes ────────────────────────────────

    @Nested
    @DisplayName("WIKI4AI-197: an ADMIN account does not make every token administrative")
    class EffectiveAdminRole {

        @Test
        @DisplayName("An ADMIN's ordinary read/write token is refused on an admin endpoint")
        void adminAccountWithScopedTokenIsRefused() throws Exception {
            String ordinary = tokenFor(OPERATOR, List.of("read", "write"));

            mockMvc.perform(get("/api/v1/admin/users")
                            .header("Authorization", "Bearer " + ordinary))
                    .andExpect(status().isForbidden())
                    // AdminController answers a SecurityException with {"error": <reason>},
                    // so the reason lives under "error" here — unlike the filter-chain
                    // refusals, which GlobalExceptionHandler shapes as
                    // {"error":"Forbidden","message":<reason>}. Two shapes for two paths;
                    // asserted as they are rather than quietly harmonised in a security
                    // commit.
                    .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("admin' scope")))
                    // The reason must also say the account IS an admin, otherwise the
                    // operator reads "admin access required", sees an admin account, and
                    // concludes the check is broken.
                    .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("ADMIN role")));
        }

        @Test
        @DisplayName("The same account with an admin-scoped token is served")
        void adminScopedTokenIsServed() throws Exception {
            String adminScoped = tokenFor(OPERATOR, List.of("read", "write", "admin"));

            mockMvc.perform(get("/api/v1/admin/users")
                            .header("Authorization", "Bearer " + adminScoped))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("A signed-in ADMIN is unaffected — the session is the identity")
        void sessionOfAdminStillWorks() throws Exception {
            mockMvc.perform(get("/api/v1/admin/users")
                            .header("Authorization", "Bearer " + jwtUtil.generateToken(OPERATOR)))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("A non-admin session is still refused with the wording clients read")
        void nonAdminIsStillRefusedWithTheSameMessage() throws Exception {
            // The role half of the rule and its message are unchanged; only the credential
            // half is new. Clients and tests read "Current role: USER" to decide what to
            // do, so that wording is load-bearing.
            mockMvc.perform(get("/api/v1/admin/users")
                            .header("Authorization", "Bearer " + jwtUtil.generateToken(MEMBER)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Current role: USER")));
        }

        @Test
        @DisplayName("A non-admin's admin-scoped token is still refused — the role still gates")
        void scopeWithoutRoleIsNotEnough() throws Exception {
            // Dropping the role half would let a token be granted administrative power for
            // an account that never had it.
            String memberAdminScoped = tokenFor(MEMBER, List.of("admin"));

            mockMvc.perform(get("/api/v1/admin/users")
                            .header("Authorization", "Bearer " + memberAdminScoped))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Current role: USER")));
        }
    }
}
