package com.wiki4ai.config;

import com.wiki4ai.service.ApiTokenService;
import com.wiki4ai.service.TokenRateLimiter;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests for JwtAuthenticationFilter.
 * Uses mocked JwtUtil to isolate filter behavior from JWT library internals.
 */
class JwtAuthenticationFilterTest {

    private JwtUtil jwtUtil;
    private JwtAuthenticationFilter filter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        // Mock JwtUtil to isolate filter testing from JWT library
        jwtUtil = mock(JwtUtil.class);
        filter = new JwtAuthenticationFilter(jwtUtil);

        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();

        // Clear security context before each test
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void doFilterInternal_withInvalidToken_shouldReturn401() throws Exception {
        when(jwtUtil.validateToken("invalid-token")).thenReturn(false);

        request.addHeader("Authorization", "Bearer invalid-token");
        FilterChain chain = new MockFilterChain();

        filter.doFilterInternal(request, response, chain);

        // Verify 401 status is returned
        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.getStatus());
        // Verify authentication context is cleared
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void doFilterInternal_withNoAuthorizationHeader_shouldPassThrough() throws Exception {
        FilterChain chain = new MockFilterChain();

        filter.doFilterInternal(request, response, chain);

        // Verify no authentication is set (filter passes through)
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals(HttpServletResponse.SC_OK, response.getStatus());
    }

    @Test
    void doFilterInternal_withEmptyAuthorizationHeader_shouldPassThrough() throws Exception {
        request.addHeader("Authorization", "");
        FilterChain chain = new MockFilterChain();

        filter.doFilterInternal(request, response, chain);

        // Verify no authentication is set (filter passes through)
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void doFilterInternal_withNonBearerAuthorization_shouldPassThrough() throws Exception {
        request.addHeader("Authorization", "Basic abc123");
        FilterChain chain = new MockFilterChain();

        filter.doFilterInternal(request, response, chain);

        // Verify no authentication is set (filter passes through)
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void doFilterInternal_withExpiredToken_shouldReturn401() throws Exception {
        when(jwtUtil.validateToken("expired-token")).thenReturn(false);

        request.addHeader("Authorization", "Bearer expired-token");
        FilterChain chain = new MockFilterChain();

        filter.doFilterInternal(request, response, chain);

        // Verify 401 status is returned for expired token
        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.getStatus());
    }

    @Test
    void doFilterInternal_withTamperedToken_shouldReturn401() throws Exception {
        when(jwtUtil.validateToken("tampered-token")).thenReturn(false);

        request.addHeader("Authorization", "Bearer tampered-token");
        FilterChain chain = new MockFilterChain();

        filter.doFilterInternal(request, response, chain);

        // Verify 401 status is returned for tampered token
        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.getStatus());
    }

    @Test
    void doFilterInternal_withWhitespaceBearer_shouldPassThrough() throws Exception {
        request.addHeader("Authorization", "Bearer");
        FilterChain chain = new MockFilterChain();

        filter.doFilterInternal(request, response, chain);

        // Verify no authentication is set (filter passes through)
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void doFilterInternal_withNullToken_shouldReturn401() throws Exception {
        when(jwtUtil.validateToken("null")).thenReturn(false);

        request.addHeader("Authorization", "Bearer null");
        FilterChain chain = new MockFilterChain();

        filter.doFilterInternal(request, response, chain);

        // Verify 401 status is returned for null token
        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.getStatus());
    }

    /**
     * WIKI4AI-136: the additive {@code w4a_} branch.
     * <p>
     * The tests above are the legacy branch and must keep passing unchanged — they
     * are the regression net that proves the JWT path was not rewritten. This nested
     * class covers only the new path: a hashed token is validated against the token
     * service (which performs the hash lookup, the revocation check and the expiry
     * check), and anything the service refuses becomes a 401.
     * <p>
     * The service is mocked here on purpose: the filter's job is the branch and the
     * 401, not the hashing. {@code ApiTokenServiceTest} proves that a revoked row is
     * actually refused, including when it was cached.
     */
    @Nested
    @DisplayName("w4a_ hashed API token branch (WIKI4AI-136)")
    class HashedApiTokenBranch {

        /** w4a_ + 32 base62 characters — the shape ApiTokenService issues. */
        private static final String W4A_TOKEN = "w4a_0123456789abcdefghijABCDEFGHIJKL";

        private ApiTokenService apiTokenService;
        private JwtAuthenticationFilter statefulFilter;

        @BeforeEach
        void setUpStatefulFilter() {
            apiTokenService = mock(ApiTokenService.class);
            statefulFilter = new JwtAuthenticationFilter(jwtUtil, apiTokenService);
        }

        private ApiTokenService.ApiTokenPrincipal principal() {
            return new ApiTokenService.ApiTokenPrincipal(11L, 7L, "token_alice", 42L,
                    List.of(ApiTokenService.SCOPE_READ, ApiTokenService.SCOPE_WRITE));
        }

        private List<String> authoritiesOf(Authentication authentication) {
            return authentication.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .collect(Collectors.toList());
        }

        @Test
        @DisplayName("A valid hashed token authenticates with authorities derived from its scopes")
        void validHashedToken_shouldAuthenticate() throws Exception {
            when(apiTokenService.authenticate(W4A_TOKEN)).thenReturn(Optional.of(principal()));

            request.addHeader("Authorization", "Bearer " + W4A_TOKEN);
            FilterChain chain = new MockFilterChain();

            statefulFilter.doFilterInternal(request, response, chain);

            assertEquals(HttpServletResponse.SC_OK, response.getStatus());
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            assertNotNull(authentication);
            assertEquals("token_alice", authentication.getName());

            List<String> authorities = authoritiesOf(authentication);
            assertTrue(authorities.contains("ROLE_USER"));
            assertTrue(authorities.contains("SCOPE_read"));
            assertTrue(authorities.contains("SCOPE_write"));
            assertFalse(authorities.contains("SCOPE_admin"));
        }

        @Test
        @DisplayName("The actor id and the token id reach the request context")
        void hashedToken_shouldCarryActorIntoContext() throws Exception {
            when(apiTokenService.authenticate(W4A_TOKEN)).thenReturn(Optional.of(principal()));

            request.addHeader("Authorization", "Bearer " + W4A_TOKEN);
            FilterChain chain = new MockFilterChain();

            statefulFilter.doFilterInternal(request, response, chain);

            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            assertNotNull(authentication);

            Object details = authentication.getDetails();
            assertTrue(details instanceof JwtAuthenticationFilter.ApiTokenAuthenticationDetails);
            JwtAuthenticationFilter.ApiTokenAuthenticationDetails tokenDetails =
                    (JwtAuthenticationFilter.ApiTokenAuthenticationDetails) details;
            assertEquals(11L, tokenDetails.tokenId().longValue());
            assertEquals(7L, tokenDetails.userId().longValue());
            assertEquals(42L, tokenDetails.actorId().longValue());
            assertEquals(List.of(ApiTokenService.SCOPE_READ, ApiTokenService.SCOPE_WRITE), tokenDetails.scopes());

            // Also exposed as a request attribute for controllers that want it.
            assertSame(details, request.getAttribute(
                    JwtAuthenticationFilter.ApiTokenAuthenticationDetails.REQUEST_ATTRIBUTE));
        }

        @Test
        @DisplayName("A hashed token never goes through the JWT signature path")
        void hashedToken_shouldNotConsultJwtUtil() throws Exception {
            when(apiTokenService.authenticate(W4A_TOKEN)).thenReturn(Optional.of(principal()));

            request.addHeader("Authorization", "Bearer " + W4A_TOKEN);
            FilterChain chain = new MockFilterChain();

            statefulFilter.doFilterInternal(request, response, chain);

            assertEquals(HttpServletResponse.SC_OK, response.getStatus());
            verifyNoInteractions(jwtUtil);
        }

        @Test
        @DisplayName("A revoked hashed token returns 401 and clears the context")
        void revokedHashedToken_shouldReturn401() throws Exception {
            // The service refuses a revoked row: empty Optional.
            when(apiTokenService.authenticate(W4A_TOKEN)).thenReturn(Optional.empty());

            request.addHeader("Authorization", "Bearer " + W4A_TOKEN);
            FilterChain chain = new MockFilterChain();

            statefulFilter.doFilterInternal(request, response, chain);

            assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.getStatus());
            assertNull(SecurityContextHolder.getContext().getAuthentication());
            assertTrue(response.getContentAsString().contains("revoked"));
        }

        @Test
        @DisplayName("A hashed token whose hash is not in the database returns 401")
        void unknownHash_shouldReturn401() throws Exception {
            when(apiTokenService.authenticate(W4A_TOKEN)).thenReturn(Optional.empty());

            request.addHeader("Authorization", "Bearer " + W4A_TOKEN);
            FilterChain chain = new MockFilterChain();

            statefulFilter.doFilterInternal(request, response, chain);

            assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.getStatus());
            assertNull(SecurityContextHolder.getContext().getAuthentication());
        }

        @Test
        @DisplayName("An expired hashed token returns 401")
        void expiredHashedToken_shouldReturn401() throws Exception {
            when(apiTokenService.authenticate(W4A_TOKEN)).thenReturn(Optional.empty());

            request.addHeader("Authorization", "Bearer " + W4A_TOKEN);
            FilterChain chain = new MockFilterChain();

            statefulFilter.doFilterInternal(request, response, chain);

            assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.getStatus());
            assertNull(SecurityContextHolder.getContext().getAuthentication());
        }

        @Test
        @DisplayName("A hashed token is rejected, not trusted, when no token service is available")
        void hashedTokenWithoutService_shouldReturn401() throws Exception {
            // The legacy single-argument filter has no token service: it must refuse a
            // hashed token instead of falling through to the JWT branch.
            JwtAuthenticationFilter withoutService = new JwtAuthenticationFilter(jwtUtil);

            request.addHeader("Authorization", "Bearer " + W4A_TOKEN);
            FilterChain chain = new MockFilterChain();

            withoutService.doFilterInternal(request, response, chain);

            assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.getStatus());
            assertNull(SecurityContextHolder.getContext().getAuthentication());
        }

        @Test
        @DisplayName("A failure inside the token lookup is a 401, never a fall-through")
        void lookupFailure_shouldReturn401() throws Exception {
            when(apiTokenService.authenticate(W4A_TOKEN)).thenThrow(new IllegalStateException("db down"));

            request.addHeader("Authorization", "Bearer " + W4A_TOKEN);
            FilterChain chain = new MockFilterChain();

            statefulFilter.doFilterInternal(request, response, chain);

            assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.getStatus());
            assertNull(SecurityContextHolder.getContext().getAuthentication());
        }

        @Test
        @DisplayName("Legacy JWT behaviour is unchanged and never touches the token service")
        void legacyJwt_shouldStayUnchanged() throws Exception {
            String legacyJwt = "header.payload.signature";
            when(jwtUtil.validateToken(legacyJwt)).thenReturn(true);
            when(jwtUtil.getUsernameFromToken(legacyJwt)).thenReturn("legacy_user");

            request.addHeader("Authorization", "Bearer " + legacyJwt);
            FilterChain chain = new MockFilterChain();

            statefulFilter.doFilterInternal(request, response, chain);

            assertEquals(HttpServletResponse.SC_OK, response.getStatus());
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            assertNotNull(authentication);
            assertEquals("legacy_user", authentication.getName());
            assertEquals(List.of("ROLE_USER"), authoritiesOf(authentication));
            assertNull(authentication.getDetails());

            verifyNoInteractions(apiTokenService);
        }

        @Test
        @DisplayName("An invalid legacy JWT still returns 401 with the original message")
        void invalidLegacyJwt_shouldStillReturn401() throws Exception {
            when(jwtUtil.validateToken("bad.jwt.value")).thenReturn(false);

            request.addHeader("Authorization", "Bearer bad.jwt.value");
            FilterChain chain = new MockFilterChain();

            statefulFilter.doFilterInternal(request, response, chain);

            assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.getStatus());
            assertTrue(response.getContentAsString().contains("Invalid or expired JWT token"));
            verifyNoInteractions(apiTokenService);
        }
    }

    /**
     * WIKI4AI-139 (per-token rate limit) and WIKI4AI-137 (actor kill switch) as seen
     * from the filter.
     * <p>
     * The limit is enforced inside {@code ApiTokenService.authenticate()} — after the
     * token has already been proven valid — and the filter's job is what the caller
     * then sees: 429 with {@code Retry-After}, never 401 (the credential works) and
     * never 500 (a throttle is not a server fault). A real {@link TokenRateLimiter}
     * is used here rather than a stubbed counter, so the budget, the per-key
     * isolation and the Retry-After value are the production ones.
     * <p>
     * The legacy JWT branch is asserted to stay untouched: it is never counted, so an
     * existing client that sends many requests on a clear-text JWT is not affected.
     */
    @Nested
    @DisplayName("Per-token rate limit 429 and actor kill switch (WIKI4AI-137 / WIKI4AI-139)")
    class RateLimitAndKillSwitch {

        /** Two distinct hashed tokens — two distinct budgets, same owner. */
        private static final String TOKEN_A = "w4a_0123456789abcdefghijABCDEFGHIJKL";
        private static final String TOKEN_B = "w4a_000011112222333344445555666677aa";

        /** Small budget so the breach is reached in a test without waiting. */
        private static final int LIMIT = 3;

        private static final int HTTP_429 = 429;

        private ApiTokenService apiTokenService;
        private TokenRateLimiter rateLimiter;
        private JwtAuthenticationFilter statefulFilter;

        @BeforeEach
        void setUpStatefulFilter() {
            apiTokenService = mock(ApiTokenService.class);
            rateLimiter = new TokenRateLimiter(LIMIT, 1_000L);
            statefulFilter = new JwtAuthenticationFilter(jwtUtil, apiTokenService);

            // authenticate() is where the real service charges the token's window, and
            // it does so only after the token passed every validity check. The mock
            // reproduces exactly that enforcement point.
            when(apiTokenService.authenticate(anyString())).thenAnswer(invocation -> {
                rateLimiter.enforce(invocation.getArgument(0));
                return Optional.of(principal());
            });
        }

        private ApiTokenService.ApiTokenPrincipal principal() {
            return new ApiTokenService.ApiTokenPrincipal(11L, 7L, "token_alice", 42L,
                    List.of(ApiTokenService.SCOPE_READ, ApiTokenService.SCOPE_WRITE));
        }

        /** One request carrying {@code token}, answered by the filter. */
        private MockHttpServletResponse perform(String token) throws Exception {
            MockHttpServletRequest ownRequest = new MockHttpServletRequest();
            MockHttpServletResponse ownResponse = new MockHttpServletResponse();
            ownRequest.addHeader("Authorization", "Bearer " + token);

            statefulFilter.doFilterInternal(ownRequest, ownResponse, new MockFilterChain());
            return ownResponse;
        }

        @Test
        @DisplayName("The request over the token's budget is 429 with Retry-After: 1")
        void overLimit_shouldReturn429WithRetryAfter() throws Exception {
            for (int i = 0; i < LIMIT; i++) {
                assertEquals(HttpServletResponse.SC_OK, perform(TOKEN_A).getStatus(),
                        "request " + (i + 1) + " is inside the budget");
            }

            MockHttpServletResponse limited = perform(TOKEN_A);

            assertEquals(HTTP_429, limited.getStatus());
            assertEquals("1", limited.getHeader("Retry-After"));
            assertTrue(limited.getContentAsString().contains("limit exceeded"));
        }

        @Test
        @DisplayName("A throttled request is not authenticated")
        void throttledRequest_shouldNotLeaveAuthenticationBehind() throws Exception {
            for (int i = 0; i < LIMIT; i++) {
                perform(TOKEN_A);
            }

            SecurityContextHolder.clearContext();
            perform(TOKEN_A);

            assertNull(SecurityContextHolder.getContext().getAuthentication());
        }

        @Test
        @DisplayName("Another token of the same user is not affected by the breach")
        void otherTokenOfSameUser_shouldStayUsable() throws Exception {
            for (int i = 0; i < LIMIT + 1; i++) {
                perform(TOKEN_A);
            }
            assertEquals(HTTP_429, perform(TOKEN_A).getStatus());

            // Same owner (userId 7), different credential: a full window for TOKEN_A
            // says nothing about TOKEN_B.
            assertEquals(HttpServletResponse.SC_OK, perform(TOKEN_B).getStatus());
            assertEquals(HttpServletResponse.SC_OK, perform(TOKEN_B).getStatus());
        }

        @Test
        @DisplayName("The legacy JWT branch is never rate limited")
        void legacyJwt_shouldNeverBeRateLimited() throws Exception {
            String legacyJwt = "header.payload.signature";
            when(jwtUtil.validateToken(legacyJwt)).thenReturn(true);
            when(jwtUtil.getUsernameFromToken(legacyJwt)).thenReturn("legacy_user");

            // Far more requests than the hashed-token budget allows.
            for (int i = 0; i < LIMIT + 5; i++) {
                assertEquals(HttpServletResponse.SC_OK, perform(legacyJwt).getStatus(),
                        "legacy request " + (i + 1) + " must not be throttled");
            }

            // The limiter was never consulted, and the token service was never called.
            assertEquals(0, rateLimiter.trackedKeys());
            verifyNoInteractions(apiTokenService);
        }

        @Test
        @DisplayName("A refused token lookup is still 401, not 429")
        void invalidToken_shouldStay401() throws Exception {
            // doReturn rather than when(): the general stubbing in setUpStatefulFilter
            // has a side effect (it charges the window), and when() would run it while
            // re-stubbing. doReturn never invokes the previous answer.
            doReturn(Optional.empty()).when(apiTokenService).authenticate(TOKEN_A);

            MockHttpServletResponse response = perform(TOKEN_A);

            assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.getStatus());
            assertNull(response.getHeader("Retry-After"));
            // No budget was charged for a token that is not valid.
            assertEquals(0, rateLimiter.trackedKeys());
        }

        @Test
        @DisplayName("After the actor kill switch, the same token gets 401 on its next request")
        void killSwitch_shouldMakeCachedTokenReturn401() throws Exception {
            // A token that is live — and already cached by a previous request.
            AtomicBoolean tokenLive = new AtomicBoolean(true);
            doAnswer(invocation -> tokenLive.get() ? Optional.of(principal()) : Optional.empty())
                    .when(apiTokenService).authenticate(TOKEN_A);
            doAnswer(invocation -> {
                tokenLive.set(false);   // the service revoked the rows and evicted the cache
                return 2;               // two tokens of this actor were revoked
            }).when(apiTokenService).revokeActorTokens(42L, "token_alice");

            assertEquals(HttpServletResponse.SC_OK, perform(TOKEN_A).getStatus());

            // What DELETE /api/v1/actors/42/tokens does.
            apiTokenService.revokeActorTokens(42L, "token_alice");

            // The very next request carrying the same secret is refused.
            MockHttpServletResponse afterSwitch = perform(TOKEN_A);
            assertEquals(HttpServletResponse.SC_UNAUTHORIZED, afterSwitch.getStatus());
            assertNull(SecurityContextHolder.getContext().getAuthentication());
        }
    }

    /**
     * WIKI4AI-138: the deprecation warning for legacy API tokens.
     * <p>
     * Everything above this class is the regression net for what this story must not
     * change; this class covers only what it adds — a {@code log.warn} on the legacy
     * branch, deduplicated in time and bounded in memory.
     * <p>
     * Logback's {@link ListAppender} is attached to the filter's own logger instead
     * of a {@code @MockBean}: this class is a plain unit test with no Spring context,
     * and adding one to observe a log line would be a bigger change than the story.
     * logback-classic is already on the test classpath through
     * spring-boot-starter-logging, so this adds no dependency.
     * <p>
     * The deduplication map is static because the filter is one singleton in the
     * application. It is therefore cleared before and after every test here, so no
     * test depends on another one or on execution order.
     */
    @Nested
    @DisplayName("Legacy API token deprecation warning (WIKI4AI-138)")
    class LegacyTokenDeprecationWarning {

        private static final String LEGACY_TOKEN = "header.payload.signature";
        private static final String W4A_TOKEN = "w4a_0123456789abcdefghijABCDEFGHIJKL";

        /** Stable fragment of the message, so assertions do not restate the sentence. */
        private static final String WARNING_MARKER = "is not revocable and never expires";

        private ApiTokenService apiTokenService;
        private Logger filterLogger;
        private Level previousLevel;
        private boolean previousAdditive;
        private ListAppender<ILoggingEvent> appender;

        @BeforeEach
        void attachAppenderToFilterLogger() {
            JwtAuthenticationFilter.clearLegacyDeprecationLog();

            apiTokenService = mock(ApiTokenService.class);
            // The two-argument filter, so a w4a_ token can be proven not to warn.
            filter = new JwtAuthenticationFilter(jwtUtil, apiTokenService);

            filterLogger = (Logger) LoggerFactory.getLogger(JwtAuthenticationFilter.class);
            previousLevel = filterLogger.getLevel();
            previousAdditive = filterLogger.isAdditive();
            filterLogger.setLevel(Level.WARN);      // WARN must reach the appender
            filterLogger.setAdditive(false);        // keep the flood out of the console

            appender = new ListAppender<>();
            appender.start();
            filterLogger.addAppender(appender);
        }

        @AfterEach
        void detachAppenderFromFilterLogger() {
            filterLogger.detachAppender(appender);
            appender.stop();
            filterLogger.setLevel(previousLevel);
            filterLogger.setAdditive(previousAdditive);
            JwtAuthenticationFilter.clearLegacyDeprecationLog();
        }

        /** Every logged deprecation warning, as its final rendered text. */
        private List<String> deprecationWarnings() {
            return appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(message -> message.contains(WARNING_MARKER))
                    .collect(Collectors.toList());
        }

        /** Any non-{@code w4a_} string is accepted by the mocked JwtUtil. */
        private void stubValidLegacyJwt() {
            when(jwtUtil.validateToken(anyString())).thenReturn(true);
            when(jwtUtil.getUsernameFromToken(anyString())).thenReturn("legacy_user");
        }

        /** One request carrying {@code token}, answered by the filter. */
        private MockHttpServletResponse perform(String token) throws Exception {
            MockHttpServletRequest ownRequest = new MockHttpServletRequest();
            MockHttpServletResponse ownResponse = new MockHttpServletResponse();
            ownRequest.addHeader("Authorization", "Bearer " + token);

            filter.doFilterInternal(ownRequest, ownResponse, new MockFilterChain());
            return ownResponse;
        }

        @Test
        @DisplayName("A legacy token in use leaves one WARN carrying its display prefix")
        void legacyToken_shouldWarnWithDisplayPrefix() throws Exception {
            stubValidLegacyJwt();

            MockHttpServletResponse response = perform(LEGACY_TOKEN);

            assertEquals(HttpServletResponse.SC_OK, response.getStatus());

            List<String> warnings = deprecationWarnings();
            assertEquals(1, warnings.size());
            assertTrue(warnings.get(0).contains("header.p..."),
                    "the warning must name the token by its display prefix");
            assertTrue(warnings.get(0).contains("w4a_"),
                    "the warning must say what to rotate the token to");
            assertEquals(Level.WARN, appender.list.get(0).getLevel());
        }

        @Test
        @DisplayName("The warning never carries the token, its plaintext or a full hash")
        void deprecationWarning_shouldNeverContainTheToken() throws Exception {
            stubValidLegacyJwt();

            perform(LEGACY_TOKEN);
            perform("separate.token.value");

            assertEquals(2, deprecationWarnings().size());
            for (ILoggingEvent event : appender.list) {
                String message = event.getFormattedMessage();
                assertFalse(message.contains(LEGACY_TOKEN), "whole token logged: " + message);
                assertFalse(message.contains("separate.token.value"), "whole token logged: " + message);
                // The signature segment is the part that makes a JWT a working credential.
                assertFalse(message.contains("payload.signature"), "token body logged: " + message);
                // And no SHA-256 digest of it either — 64 hex characters.
                assertFalse(message.matches("(?s).*[0-9a-f]{64}.*"), "hash logged: " + message);
            }
        }

        @Test
        @DisplayName("The same legacy token warns once per window, however often it is replayed")
        void sameLegacyTokenTwice_shouldWarnOnlyOnce() throws Exception {
            stubValidLegacyJwt();

            for (int i = 0; i < 5; i++) {
                assertEquals(HttpServletResponse.SC_OK, perform(LEGACY_TOKEN).getStatus(),
                        "request " + (i + 1) + " must still authenticate");
            }

            assertEquals(1, deprecationWarnings().size(),
                    "one warning per token per window, not one per request");
            assertEquals(1, JwtAuthenticationFilter.legacyDeprecationTrackedTokens());

            // The window is one hour: long enough to stay useful, short enough that a
            // token nobody rotates keeps being reported.
            assertEquals(3_600_000L, JwtAuthenticationFilter.DEPRECATION_LOG_WINDOW_MILLIS);
        }

        @Test
        @DisplayName("Deduplication is per token: two legacy tokens warn separately")
        void distinctLegacyTokens_shouldEachWarnOnce() throws Exception {
            stubValidLegacyJwt();

            perform("aaaaaaaaaa.first.jwt");
            perform("bbbbbbbbbb.second.jwt");
            perform("aaaaaaaaaa.first.jwt");
            perform("bbbbbbbbbb.second.jwt");

            assertEquals(2, deprecationWarnings().size());
            assertEquals(2, JwtAuthenticationFilter.legacyDeprecationTrackedTokens());
        }

        @Test
        @DisplayName("A w4a_ token never produces a deprecation warning")
        void hashedToken_shouldNeverWarn() throws Exception {
            when(apiTokenService.authenticate(W4A_TOKEN)).thenReturn(Optional.of(
                    new ApiTokenService.ApiTokenPrincipal(11L, 7L, "token_alice", 42L,
                            List.of(ApiTokenService.SCOPE_READ))));

            assertEquals(HttpServletResponse.SC_OK, perform(W4A_TOKEN).getStatus());

            assertTrue(deprecationWarnings().isEmpty(),
                    "a hashed token is revocable, so it is not deprecated");
            assertEquals(0, JwtAuthenticationFilter.legacyDeprecationTrackedTokens(),
                    "the stateful branch must not touch the legacy deduplication");
            verify(apiTokenService).authenticate(W4A_TOKEN);
        }

        @Test
        @DisplayName("Deduplication memory has a ceiling and does not grow past it")
        void deduplicationMemoryIsBounded() throws Exception {
            stubValidLegacyJwt();

            int ceiling = JwtAuthenticationFilter.DEPRECATION_LOG_MAX_KEYS;
            int distinctTokens = ceiling + 50;

            int highestSizeSeen = 0;
            for (int i = 0; i < distinctTokens; i++) {
                // Distinct display prefixes: eight digits, then the rest of a JWT.
                perform(String.format("%08d-legacy.jwt.signature", i));
                highestSizeSeen = Math.max(highestSizeSeen,
                        JwtAuthenticationFilter.legacyDeprecationTrackedTokens());
            }

            assertTrue(highestSizeSeen <= ceiling,
                    "deduplication tracked " + highestSizeSeen + " keys, ceiling is " + ceiling);
            assertTrue(JwtAuthenticationFilter.legacyDeprecationTrackedTokens() <= ceiling);

            // Every distinct token was still reported once — the ceiling bounds memory,
            // it does not silence the warning.
            assertEquals(distinctTokens, deprecationWarnings().size());
        }

        @Test
        @DisplayName("Behaviour is unchanged: ROLE_USER, no details, filter chain continues")
        void legacyBehaviour_shouldBeUnchangedByTheWarning() throws Exception {
            stubValidLegacyJwt();

            MockFilterChain chain = new MockFilterChain();
            request.addHeader("Authorization", "Bearer " + LEGACY_TOKEN);
            filter.doFilterInternal(request, response, chain);

            assertEquals(HttpServletResponse.SC_OK, response.getStatus());
            assertNotNull(chain.getRequest(), "the filter chain must still continue");

            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            assertNotNull(authentication);
            assertEquals("legacy_user", authentication.getName());
            assertEquals(List.of("ROLE_USER"), authentication.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .collect(Collectors.toList()));
            assertNull(authentication.getDetails());

            assertEquals(1, deprecationWarnings().size());
        }

        @Test
        @DisplayName("A rejected legacy token is still 401, still silent, still untracked")
        void invalidLegacyToken_shouldStay401AndSilent() throws Exception {
            when(jwtUtil.validateToken(LEGACY_TOKEN)).thenReturn(false);

            MockFilterChain chain = new MockFilterChain();
            request.addHeader("Authorization", "Bearer " + LEGACY_TOKEN);
            filter.doFilterInternal(request, response, chain);

            assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.getStatus());
            assertTrue(response.getContentAsString().contains("Invalid or expired JWT token"));
            assertNull(chain.getRequest(), "a 401 must not continue the chain");
            assertNull(SecurityContextHolder.getContext().getAuthentication());

            assertTrue(deprecationWarnings().isEmpty(),
                    "a token that did not authenticate is not a legacy token in use");
            assertEquals(0, JwtAuthenticationFilter.legacyDeprecationTrackedTokens());
        }

        @Test
        @DisplayName("The warning never reaches into ApiTokenService")
        void legacyWarning_shouldNeverTouchApiTokenService() throws Exception {
            stubValidLegacyJwt();

            perform(LEGACY_TOKEN);
            perform(LEGACY_TOKEN);

            assertEquals(1, deprecationWarnings().size());
            // The existing guarantee for the legacy branch: no token service, no state,
            // no rate limit. Deprecation logging must not break it.
            verifyNoInteractions(apiTokenService);
        }
    }

    /**
     * WIKI4AI-167: the deduplication key of the deprecation warning is the account,
     * not the token's display prefix.
     * <p>
     * What the previous key could not do. For a real JWT the first eight characters
     * are the base64url of its JSON header — {@code eyJhbGci} for {@code {"alg":"HS…"}}
     * — so <b>every</b> legacy token this deployment ever issued starts with the same
     * eight characters. Keyed on the prefix alone, three accounts still running legacy
     * tokens produced one warning an hour and named nobody, which is exactly the
     * information the migration to {@code w4a_} tokens needs. This class is the
     * regression net for that: it proves the warning separates accounts.
     * <p>
     * A real {@link JwtUtil} and real {@link JwtUtil#generateInfiniteToken} tokens are
     * used here, not the mocked one the rest of this file uses. The premise of the bug
     * is a property of real JWTs, so the test has to carry real JWTs: it asserts the
     * shared prefix itself, and it exercises the real {@code sub} extraction rather
     * than a stubbed username. The token service is still mocked, because the legacy
     * branch must be proven to never touch it.
     * <p>
     * No Spring context and no database: this is the filter's own unit test, same as
     * the deprecation class above it. Logback's {@link ListAppender} observes the log.
     */
    @Nested
    @DisplayName("Deprecation dedup key is per account, not per shared JWT header (WIKI4AI-167)")
    class PerAccountDeprecationDeduplication {

        /**
         * Test-only signing secret. It signs the throwaway tokens this test mints for
         * itself; it is not a deployment secret and it is never logged.
         */
        private static final String TEST_JWT_SECRET =
                "wiki4ai-167-per-account-dedup-secret-0123";

        /** Stable fragment of the message, so assertions do not restate the sentence. */
        private static final String WARNING_MARKER = "is not revocable and never expires";

        /** base64url of {@code {"alg":"HS…"}} — the first eight characters of any JWT. */
        private static final String SHARED_JWT_HEADER_PREFIX = "eyJhbGci";

        private static final String ACCOUNT_ALICE = "alice167";
        private static final String ACCOUNT_BOB = "bob167";

        private JwtUtil realJwtUtil;
        private ApiTokenService apiTokenService;
        private JwtAuthenticationFilter perAccountFilter;

        private String tokenAlice;
        private String tokenBob;

        private Logger filterLogger;
        private Level previousLevel;
        private boolean previousAdditive;
        private ListAppender<ILoggingEvent> appender;

        @BeforeEach
        void mintRealLegacyTokensAndAttachAppender() {
            JwtAuthenticationFilter.clearLegacyDeprecationLog();

            // 41 bytes: at least 32 and under 48, so jjwt signs with HMAC-SHA256 —
            // the same algorithm a real legacy API token is signed with.
            realJwtUtil = new JwtUtil(TEST_JWT_SECRET, 86_400_000L, 604_800_000L);

            // generateInfiniteToken: no 'exp' claim. This is precisely how a named API
            // token was issued before WIKI4AI-135, so these are real legacy tokens.
            tokenAlice = realJwtUtil.generateInfiniteToken(ACCOUNT_ALICE);
            tokenBob = realJwtUtil.generateInfiniteToken(ACCOUNT_BOB);

            apiTokenService = mock(ApiTokenService.class);
            perAccountFilter = new JwtAuthenticationFilter(realJwtUtil, apiTokenService);

            filterLogger = (Logger) LoggerFactory.getLogger(JwtAuthenticationFilter.class);
            previousLevel = filterLogger.getLevel();
            previousAdditive = filterLogger.isAdditive();
            filterLogger.setLevel(Level.WARN);
            filterLogger.setAdditive(false);

            appender = new ListAppender<>();
            appender.start();
            filterLogger.addAppender(appender);
        }

        @AfterEach
        void detachAppenderAndForgetDedupState() {
            filterLogger.detachAppender(appender);
            appender.stop();
            filterLogger.setLevel(previousLevel);
            filterLogger.setAdditive(previousAdditive);
            JwtAuthenticationFilter.clearLegacyDeprecationLog();
            SecurityContextHolder.clearContext();
        }

        /** Every logged deprecation warning, as its final rendered text. */
        private List<String> deprecationWarnings() {
            return appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(message -> message.contains(WARNING_MARKER))
                    .collect(Collectors.toList());
        }

        /** The signature segment of a JWT — the part that makes it a working credential. */
        private String signatureOf(String token) {
            return token.substring(token.lastIndexOf('.') + 1);
        }

        /** One request carrying {@code token}, answered by the filter. */
        private MockHttpServletResponse perform(String token) throws Exception {
            MockHttpServletRequest ownRequest = new MockHttpServletRequest();
            MockHttpServletResponse ownResponse = new MockHttpServletResponse();
            ownRequest.addHeader("Authorization", "Bearer " + token);

            perAccountFilter.doFilterInternal(ownRequest, ownResponse, new MockFilterChain());
            return ownResponse;
        }

        /**
         * The premise of the whole story, asserted rather than assumed: two real legacy
         * tokens of two different accounts are indistinguishable by their first eight
         * characters. If this ever stopped being true, the old key would be fine again
         * and this test would say so loudly.
         */
        @Test
        @DisplayName("Premise: two accounts' real legacy tokens share their whole display prefix")
        void legacyTokensOfDifferentAccounts_shareTheSameDisplayPrefix() {
            assertTrue(realJwtUtil.validateToken(tokenAlice), "a legacy token must authenticate");
            assertTrue(realJwtUtil.validateToken(tokenBob), "a legacy token must authenticate");

            assertEquals(ACCOUNT_ALICE, realJwtUtil.getUsernameFromToken(tokenAlice));
            assertEquals(ACCOUNT_BOB, realJwtUtil.getUsernameFromToken(tokenBob));

            assertTrue(tokenAlice.startsWith(SHARED_JWT_HEADER_PREFIX),
                    "a real JWT starts with its base64url JSON header");
            assertTrue(tokenBob.startsWith(SHARED_JWT_HEADER_PREFIX),
                    "a real JWT starts with its base64url JSON header");

            String prefixAlice = tokenAlice.substring(0, JwtAuthenticationFilter.DEPRECATION_DISPLAY_PREFIX_LENGTH);
            String prefixBob = tokenBob.substring(0, JwtAuthenticationFilter.DEPRECATION_DISPLAY_PREFIX_LENGTH);
            assertEquals(prefixAlice, prefixBob,
                    "the display prefix cannot tell these two accounts apart — that is the bug");

            // And the tokens themselves are different credentials.
            assertNotEquals(tokenAlice, tokenBob);
        }

        @Test
        @DisplayName("Two accounts on legacy tokens warn separately, each naming its account")
        void twoAccountsOnLegacyTokens_shouldWarnOnceEach() throws Exception {
            assertEquals(HttpServletResponse.SC_OK, perform(tokenAlice).getStatus());
            assertEquals(HttpServletResponse.SC_OK, perform(tokenBob).getStatus());

            List<String> warnings = deprecationWarnings();
            assertEquals(2, warnings.size(),
                    "the log must distinguish the accounts that use legacy tokens, "
                            + "not merge them into one shared JWT-header key");

            assertTrue(warnings.stream().anyMatch(message -> message.contains(ACCOUNT_ALICE)),
                    "no warning names " + ACCOUNT_ALICE + ": " + warnings);
            assertTrue(warnings.stream().anyMatch(message -> message.contains(ACCOUNT_BOB)),
                    "no warning names " + ACCOUNT_BOB + ": " + warnings);

            // One key per account, so the log can be read as a list of accounts.
            assertEquals(2, JwtAuthenticationFilter.legacyDeprecationTrackedTokens());
        }

        @Test
        @DisplayName("One account replaying its legacy token still warns once per window")
        void sameAccountReplayed_shouldWarnOnlyOnce() throws Exception {
            for (int i = 0; i < 6; i++) {
                assertEquals(HttpServletResponse.SC_OK, perform(tokenAlice).getStatus(),
                        "request " + (i + 1) + " must still authenticate");
            }

            assertEquals(1, deprecationWarnings().size(),
                    "per account per window — the dedup window is unchanged by naming the account");
            assertEquals(1, JwtAuthenticationFilter.legacyDeprecationTrackedTokens());

            // The window itself is untouched by this story.
            assertEquals(3_600_000L, JwtAuthenticationFilter.DEPRECATION_LOG_WINDOW_MILLIS);
            assertEquals(1_000, JwtAuthenticationFilter.DEPRECATION_LOG_MAX_KEYS);
        }

        @Test
        @DisplayName("Two legacy tokens of the same account are one account: one warning")
        void sameAccountSecondLegacyToken_shouldWarnOnceForThatAccount() throws Exception {
            // A second legacy credential for the same account: same display prefix, same
            // sub, different token. The key is the account, so this is one entry.
            //
            // The extra claim and the explicit expiry are what make the second token
            // deterministically different from the first one: a JWT 'iat' has second
            // granularity, so two infinite tokens minted back to back would be the same
            // string and this test would prove nothing.
            String secondTokenForAlice = realJwtUtil.generateToken(ACCOUNT_ALICE, "ACCESS",
                    Map.of("jti", "wiki4ai-167-second-legacy-token"),
                    new Date(System.currentTimeMillis() + 86_400_000L));
            assertTrue(realJwtUtil.validateToken(secondTokenForAlice));
            assertEquals(ACCOUNT_ALICE, realJwtUtil.getUsernameFromToken(secondTokenForAlice));
            assertNotEquals(tokenAlice, secondTokenForAlice);

            assertEquals(HttpServletResponse.SC_OK, perform(tokenAlice).getStatus());
            assertEquals(HttpServletResponse.SC_OK, perform(secondTokenForAlice).getStatus());

            List<String> warnings = deprecationWarnings();
            assertEquals(1, warnings.size(),
                    "the warning is per account; a second token of that account is the same action item");
            assertTrue(warnings.get(0).contains(ACCOUNT_ALICE));
            assertEquals(1, JwtAuthenticationFilter.legacyDeprecationTrackedTokens());
        }

        @Test
        @DisplayName("The warning names the account but never the token, its payload or its signature")
        void warning_shouldNameAccountButNeverTheCredential() throws Exception {
            perform(tokenAlice);
            perform(tokenBob);

            assertEquals(2, deprecationWarnings().size());

            for (ILoggingEvent event : appender.list) {
                String message = event.getFormattedMessage();

                for (String token : List.of(tokenAlice, tokenBob)) {
                    assertFalse(message.contains(token), "whole token logged: " + message);
                    assertFalse(message.contains(signatureOf(token)),
                            "signature segment logged: " + message);
                    // The payload carries the 'sub' claim as base64url; the account is
                    // logged in plain text instead, never as the encoded body.
                    String payload = token.substring(token.indexOf('.') + 1, token.lastIndexOf('.'));
                    assertFalse(message.contains(payload), "token body logged: " + message);
                }

                // No SHA-256 digest of anything either — 64 hex characters.
                assertFalse(message.matches("(?s).*[0-9a-f]{64}.*"), "hash logged: " + message);

                // The display prefix is still there: the warning keeps naming the token
                // by the same public fragment it always did.
                assertTrue(message.contains(SHARED_JWT_HEADER_PREFIX + "..."),
                        "the display prefix must stay in the warning: " + message);
            }
        }

        @Test
        @DisplayName("Identical display prefixes with different accounts are two keys")
        void identicalPrefixDifferentAccount_shouldWarnTwice() throws Exception {
            // The mocked JwtUtil isolates the key itself: same eight characters, two
            // different subjects. Under the old key these were one entry.
            JwtUtil stubbed = mock(JwtUtil.class);
            when(stubbed.validateToken(anyString())).thenReturn(true);
            when(stubbed.getUsernameFromToken("aaaaaaaaaa.one.jwt")).thenReturn(ACCOUNT_ALICE);
            when(stubbed.getUsernameFromToken("aaaaaaaaaa.two.jwt")).thenReturn(ACCOUNT_BOB);

            JwtAuthenticationFilter stubbedFilter = new JwtAuthenticationFilter(stubbed, apiTokenService);

            for (String token : List.of("aaaaaaaaaa.one.jwt", "aaaaaaaaaa.two.jwt")) {
                MockHttpServletRequest ownRequest = new MockHttpServletRequest();
                MockHttpServletResponse ownResponse = new MockHttpServletResponse();
                ownRequest.addHeader("Authorization", "Bearer " + token);
                stubbedFilter.doFilterInternal(ownRequest, ownResponse, new MockFilterChain());
                assertEquals(HttpServletResponse.SC_OK, ownResponse.getStatus());
            }

            List<String> warnings = deprecationWarnings();
            assertEquals(2, warnings.size(),
                    "the account is part of the key, so an identical prefix is not a shared key");
            assertEquals(2, JwtAuthenticationFilter.legacyDeprecationTrackedTokens());
        }

        @Test
        @DisplayName("A legacy token with no subject still warns, once, as an unknown account")
        void missingSubject_shouldStillWarnOnceAndNeverBreak() throws Exception {
            JwtUtil stubbed = mock(JwtUtil.class);
            when(stubbed.validateToken(anyString())).thenReturn(true);
            when(stubbed.getUsernameFromToken(anyString())).thenReturn(null);

            JwtAuthenticationFilter stubbedFilter = new JwtAuthenticationFilter(stubbed, apiTokenService);

            for (int i = 0; i < 3; i++) {
                MockHttpServletRequest ownRequest = new MockHttpServletRequest();
                MockHttpServletResponse ownResponse = new MockHttpServletResponse();
                ownRequest.addHeader("Authorization", "Bearer eyJhbGciOiJIUzI1NiJ9.no-subject.signature");
                stubbedFilter.doFilterInternal(ownRequest, ownResponse, new MockFilterChain());
                assertEquals(HttpServletResponse.SC_OK, ownResponse.getStatus(),
                        "a missing claim must not turn a working request into a failure");
            }

            List<String> warnings = deprecationWarnings();
            assertEquals(1, warnings.size(), "no subject is still one key, not one per request");
            assertTrue(warnings.get(0).contains("unknown"),
                    "a warning that cannot name the account says so instead of naming nothing: "
                            + warnings);
        }

        @Test
        @DisplayName("Naming the account changes nothing about the authenticated request")
        void legacyRequest_behaviourIsUnchanged() throws Exception {
            MockFilterChain chain = new MockFilterChain();
            MockHttpServletRequest legacyRequest = new MockHttpServletRequest();
            MockHttpServletResponse legacyResponse = new MockHttpServletResponse();
            legacyRequest.addHeader("Authorization", "Bearer " + tokenAlice);

            perAccountFilter.doFilterInternal(legacyRequest, legacyResponse, chain);

            assertEquals(HttpServletResponse.SC_OK, legacyResponse.getStatus());
            assertNotNull(chain.getRequest(), "the filter chain must still continue");

            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            assertNotNull(authentication);
            assertEquals(ACCOUNT_ALICE, authentication.getName());
            assertEquals(List.of("ROLE_USER"), authentication.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .collect(Collectors.toList()));
            assertNull(authentication.getDetails(),
                    "the legacy branch still produces no API token details");

            verifyNoInteractions(apiTokenService);
            assertEquals(1, deprecationWarnings().size());
        }
    }
}
