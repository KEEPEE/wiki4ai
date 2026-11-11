package com.wiki4ai.controller;

import com.wiki4ai.config.JwtUtil;
import com.wiki4ai.model.Role;
import com.wiki4ai.model.User;
import com.wiki4ai.repository.RefreshTokenRepository;
import com.wiki4ai.repository.UserRepository;
import com.wiki4ai.service.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end tests for {@code POST /api/v1/auth/refresh} (WIKI4AI-197, plan commit 2b),
 * run through the real security filter chain and the real {@link AuthService} on H2.
 *
 * <p><b>Why this endpoint is a security fix and not a feature.</b> The frontend has
 * called {@code /auth/refresh} since it was written ({@code apiClient.ts:43},
 * {@code AuthContext.tsx:240}) while {@code AuthController} had no such mapping, so the
 * request has been a 404 the entire time. Nothing noticed, because a refresh token also
 * used to pass the access-token check — it is a signed JWT with a subject — so a
 * session quietly lived for 7 days on a credential meant to be exchanged. Once the
 * filter required {@code type == ACCESS} (plan commit 2), that stopped working. Without
 * this endpoint commit 2 would have been a UX regression: the session would die at 24
 * hours with the client calling into a route that does not exist.
 *
 * <p><b>What the tests below pin down.</b> A refresh token is two things — a JWT of the
 * right type, and a live row in {@code refresh_tokens} — and the endpoint must demand
 * both. The single most important case is the one that makes the two token types mean
 * something: an <i>access</i> token presented at this door must be refused, because
 * otherwise a 24-hour credential could be renewed forever and the lifetime would again
 * be decorative.
 *
 * <p>{@code security.enabled=true} is required for the controller and the filter to
 * exist at all; the default test profile turns them off. The same property set is used
 * by {@code ActorWhoamiIntegrationTest}, so the two classes share one cached Spring
 * context rather than paying for a second.
 *
 * <p>No {@code @DirtiesContext}: every context here points at the same named in-memory
 * H2 with {@code ddl-auto=create-drop}, so closing one drops the schema the others are
 * still using. Isolation is by row, in {@link #cleanState()}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "security.enabled=true")
class AuthRefreshEndpointIntegrationTest {

    private static final String ALICE = "refresh_alice";

    /** A second account, for the case where the row and the subject name different users. */
    private static final String BOB = "refresh_bob";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

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
        saveUser(ALICE);
    }

    @AfterEach
    void cleanUp() {
        cleanState();
    }

    private void cleanState() {
        // refresh_tokens first: its user_id is a FK to users and unique per user.
        jdbcTemplate.execute("DELETE FROM refresh_tokens");
        for (String name : List.of(ALICE, BOB)) {
            userRepository.findByUsername(name).ifPresent(userRepository::delete);
        }
        refreshTokenRepository.deleteAll();
    }

    private User saveUser(String username) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@test.local");
        user.setPassword("not-a-real-hash");
        user.setRole(Role.USER);
        return userRepository.save(user);
    }

    /** A refresh token as login issues one: minted by JwtUtil and persisted. */
    private String issuePersistedRefreshToken() {
        return authService.generateAndPersistRefreshToken(userRepository.findByUsername(ALICE).orElseThrow());
    }

    private String refreshBody(String token) throws Exception {
        return objectMapper.writeValueAsString(Map.of("refreshToken", token));
    }

    // ── the happy path ────────────────────────────────────────────────────────

    @Test
    @DisplayName("A persisted refresh token yields a new access token that actually works")
    void refreshYieldsUsableAccessToken() throws Exception {
        String refreshToken = issuePersistedRefreshToken();

        MvcResult result = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType("application/json")
                        .content(refreshBody(refreshToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").value(refreshToken))
                .andExpect(jsonPath("$.user.username").value(ALICE))
                .andReturn();

        // The point of the endpoint is not that it returns a string, it is that the
        // string authenticates. Without this assertion the test would pass on a token
        // the filter then refuses.
        String accessToken = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("accessToken").asText();

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(ALICE));

        // And the new access token is a real ACCESS token, not the refresh token
        // handed back unchanged.
        org.junit.jupiter.api.Assertions.assertNotEquals(accessToken, refreshToken);
        org.junit.jupiter.api.Assertions.assertEquals("ACCESS", jwtUtil.getTokenType(accessToken));
    }

    @Test
    @DisplayName("The refresh token is echoed back unchanged, so the client keeps one it owns")
    void refreshTokenIsNotRotated() throws Exception {
        String refreshToken = issuePersistedRefreshToken();

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType("application/json")
                        .content(refreshBody(refreshToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshToken").value(refreshToken));

        // Still exactly one live row for the account — the exchange did not mint a second
        // credential behind the client's back.
        Long rows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM refresh_tokens", Long.class);
        org.junit.jupiter.api.Assertions.assertEquals(1L, rows);
    }

    @Test
    @DisplayName("The endpoint is reachable without an Authorization header")
    void refreshNeedsNoBearerHeader() throws Exception {
        // It used to be a 404. A 401 here would be its own regression: the credential
        // being exchanged IS the authentication, so requiring a bearer token at this
        // door would make an expired session impossible to renew.
        String refreshToken = issuePersistedRefreshToken();

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType("application/json")
                        .content(refreshBody(refreshToken)))
                .andExpect(status().isOk());
    }

    // ── the door between the two token types ──────────────────────────────────

    @Test
    @DisplayName("An ACCESS token presented as a refresh token is refused")
    void accessTokenIsNotARefreshToken() throws Exception {
        // The case that makes the 24-hour access lifetime mean something again. Before
        // the type check, an access token renewed itself indefinitely and the lifetime
        // was decorative; if this assertion ever goes red, the two types have collapsed
        // back into one credential.
        String accessToken = jwtUtil.generateToken(ALICE);

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType("application/json")
                        .content(refreshBody(accessToken)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("A refresh token that was never persisted is refused even though it verifies")
    void unpersistedRefreshTokenIsRefused() throws Exception {
        // Correctly signed, correct type, unexpired — and still not a credential,
        // because a refresh token is also a row. This is the half that a signature-only
        // check would miss, and it is what makes rotation and deletion real: once the
        // row is gone the token stops working no matter how valid its JWT looks.
        String mintedButNotStored = jwtUtil.generateRefreshToken(ALICE);

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType("application/json")
                        .content(refreshBody(mintedButNotStored)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("A refresh token whose stored row has expired is refused")
    void expiredRowIsRefused() throws Exception {
        String refreshToken = issuePersistedRefreshToken();

        // The JWT is still inside its own 7-day window; only the DB row says the
        // credential is done. The row's expires_at is the operator-controlled lifetime,
        // so it has to win over the JWT's.
        jdbcTemplate.update("UPDATE refresh_tokens SET expires_at = ? WHERE token = ?",
                Timestamp.valueOf(LocalDateTime.now().minusMinutes(1)), refreshToken);

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType("application/json")
                        .content(refreshBody(refreshToken)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("A refresh token naming an account that does not exist is refused")
    void tokenNamingUnknownAccountIsRefused() throws Exception {
        // Signed with the real key and typed REFRESH, so only the identity check stands
        // between it and a fresh access token. The account it names is nobody.
        String tokenNamingNobody = jwtUtil.generateRefreshToken("refresh_ghost");
        refreshTokenRepository.save(com.wiki4ai.model.RefreshToken.builder()
                .user(userRepository.findByUsername(ALICE).orElseThrow())
                .token(tokenNamingNobody)
                .expiresAt(LocalDateTime.now().plusDays(7))
                .build());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType("application/json")
                        .content(refreshBody(tokenNamingNobody)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("A row belonging to one account is refused under a subject naming another")
    void rowOwnedByAnotherAccountIsRefused() throws Exception {
        // The two halves of the credential can be recombined by anyone who can read one
        // table and hold one token: a live row for ALICE, presented under a JWT whose
        // subject says BOB. Without the id match this would hand BOB's identity to
        // whoever held ALICE's row.
        User bob = saveUser(BOB);
        String tokenNamingBob = jwtUtil.generateRefreshToken(BOB);
        refreshTokenRepository.save(com.wiki4ai.model.RefreshToken.builder()
                .user(userRepository.findByUsername(ALICE).orElseThrow())
                .token(tokenNamingBob)
                .expiresAt(LocalDateTime.now().plusDays(7))
                .build());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType("application/json")
                        .content(refreshBody(tokenNamingBob)))
                .andExpect(status().isUnauthorized());

        // Sanity: BOB exists, so the refusal came from the mismatch and not from an
        // unknown account.
        org.junit.jupiter.api.Assertions.assertNotNull(bob.getId());
    }

    // ── unusable input ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Missing, blank, empty-body and garbage input all answer 401, never 400 or 500")
    void unusableInputAnswers401() throws Exception {
        // 400 from bean validation would be a different body shape than the one the
        // frontend reads, and a 500 would mean the endpoint trusts its input.
        mockMvc.perform(post("/api/v1/auth/refresh"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType("application/json")
                        .content(refreshBody("")))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType("application/json")
                        .content(refreshBody("not-a-jwt-at-all")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Every rejection answers one indistinguishable message")
    void rejectionsDoNotRevealWhichCheckFailed() throws Exception {
        // No row is persisted in this test on purpose. generateRefreshToken has
        // second-granularity 'iat', so minting one in the same second as a persisted one
        // would produce the identical string and the "no row" candidate would silently
        // become the happy path.
        String[] candidates = {
                jwtUtil.generateToken(ALICE),          // wrong type
                jwtUtil.generateRefreshToken(ALICE),   // right type, no row
                "garbage",                             // not a JWT
                "",                                    // nothing at all
        };

        java.util.ArrayList<String> seen = new java.util.ArrayList<>();
        for (String candidate : candidates) {
            MvcResult r = mockMvc.perform(post("/api/v1/auth/refresh")
                            .contentType("application/json")
                            .content(refreshBody(candidate)))
                    .andExpect(status().isUnauthorized())
                    .andReturn();
            seen.add(r.getResponse().getContentAsString());
        }

        // One message for all four, so the response cannot be used to probe which
        // refresh tokens a deployment knows about.
        org.junit.jupiter.api.Assertions.assertEquals(1, seen.stream().distinct().count(),
                "rejections must be indistinguishable, got: " + seen);
        org.junit.jupiter.api.Assertions.assertTrue(seen.get(0).contains("Invalid or expired refresh token"),
                "unexpected body: " + seen.get(0));
        org.junit.jupiter.api.Assertions.assertFalse(
                seen.get(0).contains("type") || seen.get(0).contains("row") || seen.get(0).contains("signature"),
                "the body must not name the check that failed: " + seen.get(0));
    }
}
