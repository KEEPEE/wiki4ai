package com.wiki4ai.controller;

import com.wiki4ai.config.JwtUtil;
import com.wiki4ai.dto.CreateUserRequestDTO;
import com.wiki4ai.dto.ProfileUpdateRequestDTO;
import com.wiki4ai.model.Role;
import com.wiki4ai.model.User;
import com.wiki4ai.repository.ApiTokenRepository;
import com.wiki4ai.repository.UserRepository;
import com.wiki4ai.service.AdminService;
import com.wiki4ai.service.ApiTokenService;
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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end enforcement of reserved usernames (WIKI4AI-197, REV-2 P0 #4), through the
 * real filter chain and the real services on H2.
 *
 * <p><b>The hole in one sentence.</b> Roughly fifteen controllers resolve their caller as
 * {@code authentication.getName()}, falling back to the literal {@code "anonymous"} when
 * nothing authenticated, and the permission checks compare against that literal. An
 * account actually named {@code anonymous} therefore produced a request that every one of
 * those comparisons classified as unauthenticated — the account had proved who it was and
 * was treated as nobody.
 *
 * <p><b>Both ends have to be closed, and this class tests both.</b> Refusing the name at
 * every write stops it from ever being handed out; refusing it at authentication covers
 * the rows and tokens that predate the rule. Only the first would leave an existing
 * account forging the sentinel; only the second would let someone register the name and
 * then be silently locked out.
 *
 * <p><b>The control cases matter as much as the rejections.</b> Every false positive here
 * is a real person who cannot sign up, so {@code anonymous_reporter} and a normal account
 * are asserted to keep working.
 *
 * <p><b>Where the registration half lives.</b> The write side of the rule —
 * {@code POST /auth/register} refusing the name — is asserted in
 * {@code AuthRegistrationOptInTest}, which already runs with
 * {@code auth.registration.open=true} and its own named H2. This class deliberately uses
 * the plain {@code security.enabled=true} property set so it shares the Spring context
 * that eight other classes already use: a new property combination means a ninth context,
 * one past the test context cache, and evicting one closes a {@code create-drop} context
 * that drops the H2 schema every other class is still reading.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "security.enabled=true")
class ReservedUsernameEnforcementIntegrationTest {

    private static final String ANONYMOUS = "anonymous";
    private static final String ANONYMOUS_UPPER = "Anonymous";
    private static final String ANONYMOUS_USER = "anonymoususer";
    private static final String ORDINARY = "reserved_rule_control";

    /** An ADMIN account, only so AdminService gets past its own caller check. */
    private static final String ADMIN_CTRL = "reserved_rule_admin";

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
    private AdminService adminService;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        cleanState();
    }

    /**
     * Every test here writes accounts into the H2 the whole JVM shares, and an account
     * named {@code anonymous} is precisely the row other tests must not inherit:
     * {@code AuthFirstRunFlowTest}'s premise is an instance with no accounts at all.
     * Children (tokens) before parents (users), because the FK makes a referenced user
     * undeletable.
     */
    @AfterEach
    void cleanUp() {
        cleanState();
    }

    private void cleanState() {
        apiTokenRepository.deleteAll();
        jdbcTemplate.execute("DELETE FROM refresh_tokens");
        for (String name : List.of(ANONYMOUS, ANONYMOUS_UPPER, ANONYMOUS_USER, ORDINARY, ADMIN_CTRL)) {
            userRepository.findByUsername(name).ifPresent(userRepository::delete);
        }
        apiTokenService.clearValidationCache();
    }

    private User saveUser(String username, Role role) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@test.local");
        user.setPassword(authService.encodePassword("a-real-password-123"));
        user.setRole(role);
        return userRepository.save(user);
    }

    /** SHA-256 as 64 lowercase hex characters — the form api_tokens.token_hash stores. */
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

    // ── the write side ────────────────────────────────────────────────────────
    //
    // POST /auth/register refusing the name is asserted in AuthRegistrationOptInTest,
    // which already has a context with registration open. What is left here is the other
    // two doors, which no registration-policy test can reach.

    @Test
    @DisplayName("Admin create and profile rename are username writes like registration is")
    void otherWritePathsRefuseTheName() {
        // Registration is not the only door. An admin can create the account and
        // PUT /auth/me can rename into the reserved name — either one walks around a
        // rule that only guards the register form.
        //
        // AdminService.createUser verifies the caller is an ADMIN before anything else,
        // so the SecurityContext has to carry one for the call to reach the username
        // rule at all. The role is read from the database, not from the authorities, so
        // a plain authentication naming an ADMIN account is enough.
        saveUser(ADMIN_CTRL, Role.ADMIN);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        ADMIN_CTRL, null,
                        java.util.List.of(new org.springframework.security.core.authority
                                .SimpleGrantedAuthority("ROLE_USER"))));
        try {
            assertThrows(IllegalArgumentException.class,
                    () -> adminService.createUser(CreateUserRequestDTO.builder()
                            .username(ANONYMOUS)
                            .email("x@test.local")
                            .password("a-real-password-123")
                            .role(Role.ADMIN)
                            .build()));
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }

        saveUser(ORDINARY, Role.USER);
        assertThrows(IllegalArgumentException.class,
                () -> authService.updateUserProfile(ORDINARY,
                        ProfileUpdateRequestDTO.builder().username(ANONYMOUS_UPPER).build()));

        // And the rename really did not happen — a rejected write must leave no row and
        // must not move the existing one.
        assertTrue(userRepository.findByUsername(ORDINARY).isPresent());
        assertTrue(userRepository.findByUsername(ANONYMOUS_UPPER).isEmpty());
    }

    // ── the authentication side: the name can never be trusted ────────────────

    @Test
    @DisplayName("A JWT whose subject is the sentinel authenticates as 401, not as anonymous")
    void jwtWithReservedSubjectIsRefused() throws Exception {
        // This is the actual exploit: mint a token whose sub is the sentinel and the API
        // used to serve the request while classifying it as unauthenticated. It is now
        // refused outright, which is fail-closed — the honest answer to "who are you"
        // when the answer cannot be trusted.
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + jwtUtil.generateToken(ANONYMOUS)))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + jwtUtil.generateToken(ANONYMOUS_UPPER)))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + jwtUtil.generateToken(ANONYMOUS_USER)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Control: a JWT for an ordinary account still authenticates")
    void jwtForOrdinaryAccountStillWorks() throws Exception {
        saveUser(ORDINARY, Role.USER);

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + jwtUtil.generateToken(ORDINARY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(ORDINARY));
    }

    @Test
    @DisplayName("A w4a_ token owned by a reserved-name account is refused as well")
    void hashedTokenOfReservedOwnerIsRefused() throws Exception {
        // The other door. A w4a_ token carries no subject — the account comes from the
        // row the hash resolves to — so the rule has to be applied to the owner as well
        // as to a claim. Without it, the reserved account could still act through a
        // hashed token and be read as an unauthenticated caller.
        saveUser(ANONYMOUS, Role.USER);

        // The row is built by hand rather than through ApiTokenService#createToken,
        // and that is itself a finding worth recording: createToken resolves its owner
        // through resolveUser(), which is one of the ~15 sites that compare against the
        // "anonymous" literal — so a reserved-name account cannot even mint a token.
        // Those sites are deliberately left alone (REV-2 plan, "what not to do" #5), so
        // the test inserts the row the way an already-existing deployment would have.
        String plaintext = "w4a_reservedowner0123456789abcdefghij";
        com.wiki4ai.model.ApiToken row = com.wiki4ai.model.ApiToken.builder()
                .user(userRepository.findByUsername(ANONYMOUS).orElseThrow())
                .name("reserved owner test")
                .tokenHash(sha256Hex(plaintext))
                .tokenPrefix(plaintext.substring(0, 8))
                .scopes("read write")
                .build();
        apiTokenRepository.save(row);

        mockMvc.perform(get("/api/v1/actors/whoami")
                        .header("Authorization", "Bearer " + plaintext))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("A refresh token naming the sentinel cannot mint an access token")
    void refreshWithReservedSubjectIsRefused() throws Exception {
        // The reserved rule applies to both token types, not only to the one the filter
        // reads. Otherwise a reserved account could keep minting access tokens even
        // though it can never use one.
        saveUser(ANONYMOUS, Role.USER);
        String refreshToken = authService.generateAndPersistRefreshToken(
                userRepository.findByUsername(ANONYMOUS).orElseThrow());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken))))
                .andExpect(status().isUnauthorized());
    }
}
