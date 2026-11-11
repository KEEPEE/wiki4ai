package com.wiki4ai.controller;

import com.wiki4ai.config.JwtUtil;
import com.wiki4ai.dto.TokenCreateRequestDTO;
import com.wiki4ai.model.Role;
import com.wiki4ai.model.User;
import com.wiki4ai.repository.ApiTokenRepository;
import com.wiki4ai.repository.UserRepository;
import com.wiki4ai.service.ApiTokenService;
import com.wiki4ai.service.AuthService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A hashed API token may not mint or clone credentials (WIKI4AI-197, REV-2 P0 #6).
 *
 * <p><b>The hole.</b> The application has two kinds of credential and they are not
 * interchangeable. A JWT is a session: it exists because a human signed in, it carries no
 * row, and the endpoints that hand out long-lived credentials are how that session
 * delegates. A {@code w4a_} token is already such a credential — a row in
 * {@code api_tokens} with scopes, an expiry, a last-used timestamp and a revoke button.
 * Before this commit a request carrying a {@code w4a_} token could call every minting
 * endpoint as if it were a session, so the credential that is <i>supposed</i> to be
 * revocable decided what survived its own revocation.
 *
 * <p>Three concrete escalations, each asserted here:
 * <ul>
 *   <li>{@code POST /auth/token} and {@code POST /auth/token/named} mint a JWT with no
 *       row behind it. Revoking the API token left that JWT working — and the named
 *       variant, called without {@code expiresAt}, minted one with no {@code exp} at all,
 *       i.e. a permanent clone.</li>
 *   <li>{@code POST /api/v1/tokens} checked the {@code admin} scope only when an
 *       {@code actorId} was supplied, so an agent holding an ordinary read/write token
 *       could create a token with {@code scopes=["admin"]} and no actorId, receive its
 *       plaintext in the response body, and act with administrative rights it was never
 *       granted.</li>
 *   <li>{@code POST /api/v1/tokens/{id}/rotate} enforced ownership, which is not the same
 *       as authority: rotation returns the <b>new</b> plaintext and the replacement
 *       inherits the target's scopes, so a read-only token could rotate its owner's
 *       admin-scoped token and walk away holding a fresh admin secret. Rotating one's own
 *       id is the case rotation exists for, and it stays allowed.</li>
 * </ul>
 *
 * <p><b>403, not 401.</b> The caller is authenticated — the credential is valid, it is
 * simply not the kind that may do this. Confusing the two would tell an operator that a
 * token had been revoked when it had not.
 *
 * <p><b>Why this costs nothing to deploy.</b> The MCP server is configured with one token
 * and calls no token or minting endpoint, and the frontend creates tokens from a signed-in
 * session (JWT), so every existing caller keeps working.
 *
 * <p>Runs on the shared {@code security.enabled=true} context so it does not add a ninth
 * Spring context — one past the test context cache, LRU eviction closes a
 * {@code create-drop} context and drops the H2 schema other classes are reading.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "security.enabled=true")
class ApiTokenMintingRestrictionIntegrationTest {

    private static final String ALICE = "minting_alice";

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

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        cleanState();
        saveUser(ALICE, Role.USER);
    }

    /**
     * Row-level cleanup, not {@code @DirtiesContext}: the H2 database is shared by name
     * across the whole JVM, so closing this context would drop the schema every other
     * class is still using. Children before parents — {@code api_tokens.user_id} is an
     * FK, so a referenced user cannot be deleted while its tokens exist.
     */
    @AfterEach
    void cleanUp() {
        cleanState();
    }

    private void cleanState() {
        apiTokenRepository.deleteAll();
        jdbcTemplate.execute("DELETE FROM refresh_tokens");
        for (String name : List.of(ALICE)) {
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

    /** Mint a real hashed token for ALICE and return its plaintext. */
    private String issueApiToken(String name, List<String> scopes) {
        TokenCreateRequestDTO dto = TokenCreateRequestDTO.builder()
                .name(name)
                .scopes(scopes)
                .build();
        return apiTokenService.createToken(dto, ALICE).getToken();
    }

    private long tokenIdOf(String plaintextToken) {
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM api_tokens WHERE token_hash = ?",
                Long.class,
                sha256Hex(plaintextToken));
        assertNotNull(id, "the token this test just minted must have a row");
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

    // ── the two JWT-minting endpoints ─────────────────────────────────────────

    @Test
    @DisplayName("POST /auth/token: a session may mint, an API token may not")
    void sessionTokenEndpointRejectsApiTokenCallers() throws Exception {
        // Control first: the endpoint still works for the caller it exists for.
        mockMvc.perform(post("/api/v1/auth/token")
                        .header("Authorization", "Bearer " + jwtUtil.generateToken(ALICE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty());

        String apiToken = issueApiToken("mint attempt", List.of("read", "write"));

        mockMvc.perform(post("/api/v1/auth/token")
                        .header("Authorization", "Bearer " + apiToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("outlives")));
    }

    @Test
    @DisplayName("POST /auth/token/named: the permanent-clone case is refused")
    void namedTokenEndpointRejectsApiTokenCallers() throws Exception {
        mockMvc.perform(post("/api/v1/auth/token/named")
                        .header("Authorization", "Bearer " + jwtUtil.generateToken(ALICE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"from a session\"}"))
                .andExpect(status().isCreated());

        String apiToken = issueApiToken("named mint attempt", List.of("read", "write"));

        // Without expiresAt the named token carries no exp claim at all, so this was the
        // cheapest way for one token to reproduce itself forever.
        mockMvc.perform(post("/api/v1/auth/token/named")
                        .header("Authorization", "Bearer " + apiToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"clone\"}"))
                .andExpect(status().isForbidden());
    }

    // ── POST /api/v1/tokens: the self-granted admin scope ─────────────────────

    @Test
    @DisplayName("POST /api/v1/tokens: a token cannot grant itself scopes")
    void apiTokenCannotCreateTokens() throws Exception {
        mockMvc.perform(post("/api/v1/tokens")
                        .header("Authorization", "Bearer " + jwtUtil.generateToken(ALICE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"session-made\",\"scopes\":[\"read\",\"write\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").isNotEmpty());

        // read+write, not read-only: WIKI4AI-197 commit 5 added a scope gate in front of
        // every request, so a read-only token is now refused before it reaches this rule.
        // The escalation this test is about was performed by an ordinary token — 'admin'
        // was only refused when an actorId was present, so omitting the actor was enough
        // to ask for administrative scope.
        String ordinaryToken = issueApiToken("ordinary agent", List.of("read", "write"));
        long before = apiTokenRepository.count();
        mockMvc.perform(post("/api/v1/tokens")
                        .header("Authorization", "Bearer " + ordinaryToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"self-granted\",\"scopes\":[\"admin\"]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("scopes it was never issued")));

        assertTrue(apiTokenRepository.count() == before,
                "a refused create must not leave a row behind");
    }

    // ── rotate: allowed in place, refused sideways ────────────────────────────

    @Test
    @DisplayName("A token may rotate itself, and the new secret really replaces the old")
    void apiTokenMayRotateItself() throws Exception {
        String apiToken = issueApiToken("self rotate", List.of("read", "write"));
        long ownId = tokenIdOf(apiToken);

        MvcResult result = mockMvc.perform(post("/api/v1/tokens/" + ownId + "/rotate")
                        .header("Authorization", "Bearer " + apiToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        String fresh = body.get("token").asText();
        assertNotEquals(apiToken, fresh, "rotation must produce a different secret");

        // The old secret is dead immediately — that is the point of rotation.
        mockMvc.perform(post("/api/v1/auth/token")
                        .header("Authorization", "Bearer " + apiToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("A token may not rotate a sibling: rotation hands over the sibling's secret")
    void apiTokenCannotRotateAnotherToken() throws Exception {
        // 'low' carries read+write so the scope gate (commit 5) lets the POST through and
        // this rule is the thing that refuses it. It still holds strictly less authority
        // than the token it is trying to rotate.
        String lowPrivilege = issueApiToken("low", List.of("read", "write"));
        String adminScoped = issueApiToken("high", List.of("admin"));
        long adminId = tokenIdOf(adminScoped);

        // Same owner, so the ownership check passes — which is exactly why ownership was
        // never the right check here. The replacement inherits 'admin' and the response
        // body would carry its plaintext to a caller that does not hold that scope.
        mockMvc.perform(post("/api/v1/tokens/" + adminId + "/rotate")
                        .header("Authorization", "Bearer " + lowPrivilege))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("only its own id")));

        // And the admin token was not touched: it still authenticates.
        mockMvc.perform(post("/api/v1/auth/token")
                        .header("Authorization", "Bearer " + adminScoped))
                .andExpect(status().isForbidden()); // refused as a token caller, not 401
    }

    @Test
    @DisplayName("Control: a session may still rotate any token it owns")
    void sessionMayRotateAnyOwnedToken() throws Exception {
        String target = issueApiToken("session rotate target", List.of("read", "write"));
        long targetId = tokenIdOf(target);

        mockMvc.perform(post("/api/v1/tokens/" + targetId + "/rotate")
                        .header("Authorization", "Bearer " + jwtUtil.generateToken(ALICE)))
                .andExpect(status().isCreated());
    }
}
