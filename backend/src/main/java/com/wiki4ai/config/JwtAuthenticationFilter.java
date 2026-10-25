package com.wiki4ai.config;

import com.wiki4ai.exception.RateLimitExceededException;
import com.wiki4ai.model.ApiToken;
import com.wiki4ai.service.ApiTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authentication filter that extends OncePerRequestFilter.
 * Extracts the Authorization: Bearer &lt;token&gt; header and sets the
 * Authentication in SecurityContextHolder if the token is valid.
 * Handles invalid tokens gracefully by returning 401 Unauthorized.
 * Only loaded when security.enabled=true.
 * <p>
 * <b>Two token kinds, two branches (WIKI4AI-136).</b> The branch is chosen by the
 * token itself, so nothing has to be configured and nothing existing changes:
 * <ul>
 *   <li><b>{@code w4a_} prefix → stateful branch.</b> The token is an opaque secret;
 *       its SHA-256 digest is looked up in {@code api_tokens}. A hash that is not in
 *       the table, a row with {@code revoked_at} set, or a row whose {@code expires_at}
 *       has passed all produce 401. Authorities come from the token's scopes and the
 *       actor the token acts as travels in the authentication details. This is what
 *       makes revocation real: before this branch existed, deleting a token row
 *       revoked nothing because only the JWT signature was checked.</li>
 *   <li><b>everything else → the legacy branch, unchanged.</b> Signature verification
 *       through {@link JwtUtil} and {@code ROLE_USER}, exactly as before, including
 *       infinite-expiry tokens from {@link JwtUtil#generateInfiniteToken}. No database
 *       lookup, no new failure mode. Such a token is never revocable and never
 *       expires, so using one now leaves a deduplicated {@code log.warn} behind
 *       (WIKI4AI-138) that names the account it belongs to (WIKI4AI-167). Logging is
 *       the only addition: the status codes, the authorities and the filter chain
 *       behave exactly as they did.</li>
 * </ul>
 * The stateful lookup is cached in {@link ApiTokenService} (60 s TTL) and
 * {@code last_used_at} is written throttled (30 s window), so authenticating a
 * request does not add a database write per request.
 */
@Component
@ConditionalOnProperty(name = "security.enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    // ── Deprecation logging of legacy tokens (WIKI4AI-138) ───────────────────

    /**
     * How many leading characters of a legacy token are shown in the deprecation
     * warning. A display prefix, never the credential: for a JWT these characters
     * are the base64url of its JSON header, which is public format, not secret
     * material. The rest of the token — and therefore the signature that makes it
     * a working credential — is never logged.
     * <p>
     * Package-private rather than private, like the window and the ceiling above it,
     * because the tests assert what the key is built from.
     */
    static final int DEPRECATION_DISPLAY_PREFIX_LENGTH = 8;

    /**
     * Window in which the same legacy token of the same account produces at most one
     * warning. A legacy token can be replayed thousands of times a minute; without a
     * window the warning would flood the log and drown out everything else in it.
     */
    static final long DEPRECATION_LOG_WINDOW_MILLIS = 3_600_000L; // 1 hour

    /**
     * Hard ceiling on how many distinct deduplication keys the warning remembers.
     * The map is keyed by display prefix plus account, so it can only grow with the
     * number of different (token, account) pairs actually being used inside the
     * window; this is the backstop that turns "grows with traffic" into "bounded
     * memory".
     */
    static final int DEPRECATION_LOG_MAX_KEYS = 1_000;

    /**
     * Longest account name the warning echoes. The account is not a secret — it is
     * the {@code sub} claim of a token this deployment already accepted — but it is
     * still text that came from outside, so it is bounded and sanitized before it
     * reaches the log.
     */
    private static final int DEPRECATION_ACCOUNT_MAX_LENGTH = 64;

    /**
     * display prefix + account → moment its last warning was written.
     * <p>
     * The key is deliberately <b>not</b> the display prefix alone (WIKI4AI-167). For a
     * real JWT those first characters are the base64url of its JSON header — always
     * {@code eyJhbGci} — so every legacy token in the deployment would share one key,
     * the log would say at most "a legacy token is in use" once an hour, and it would
     * never say <i>whose</i> legacy token that is. Which accounts still use legacy
     * tokens is exactly the information the migration to {@code w4a_} tokens needs.
     * <p>
     * Deliberately a plain {@link ConcurrentHashMap} and deliberately static: the
     * filter is one singleton in the application, and the warning is about the
     * deployment as a whole rather than about one filter instance. No new
     * dependency, no eviction daemon, no scheduler — expired entries are dropped
     * from the same write path that adds them, exactly like
     * {@link com.wiki4ai.service.TokenRateLimiter} maintains itself.
     */
    private static final Map<String, Long> DEPRECATION_LOGGED = new ConcurrentHashMap<>();

    private final JwtUtil jwtUtil;

    /**
     * Null only when the filter is built by hand (unit tests of the legacy branch).
     * In the application context it is always present, and a {@code w4a_} token
     * without it is rejected rather than silently accepted.
     */
    private final ApiTokenService apiTokenService;

    /**
     * Legacy single-argument constructor, kept so existing tests of the JWT branch
     * construct the filter exactly as before.
     */
    public JwtAuthenticationFilter(JwtUtil jwtUtil) {
        this(jwtUtil, null);
    }

    @Autowired
    public JwtAuthenticationFilter(JwtUtil jwtUtil, ApiTokenService apiTokenService) {
        this.jwtUtil = jwtUtil;
        this.apiTokenService = apiTokenService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String authorizationHeader = request.getHeader("Authorization");

        // If no Authorization header or not a Bearer token, skip validation
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        // Extract the token from the header
        String token = authorizationHeader.substring(7).trim(); // Remove "Bearer " prefix (7 chars) and trim whitespace

        // ── Additive branch: hashed API token (WIKI4AI-136) ───────────────────
        // Chosen purely by the token's own prefix, so the legacy path below is
        // untouched for every token that does not start with "w4a_".
        if (token.startsWith(ApiToken.HASHED_TOKEN_PREFIX)) {
            authenticateHashedToken(token, request, response, filterChain);
            return;
        }

        // ── Legacy branch: JWT, verified by signature only — unchanged ─────────
        try {
            // Validate the token
            boolean isValid = jwtUtil.validateToken(token);
            if (isValid) {
                // Get username from token
                String username = jwtUtil.getUsernameFromToken(token);

                // Create authentication with ROLE_USER authority
                List<SimpleGrantedAuthority> authorities = Collections.singletonList(
                        new SimpleGrantedAuthority("ROLE_USER"));

                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(username, null, authorities);

                // Set the authentication in SecurityContextHolder
                SecurityContextHolder.getContext().setAuthentication(authentication);

                // WIKI4AI-138: this token authenticates, but it is a clear-text JWT
                // with no expiry and no revocation path. Say so in the log — at most
                // once per account per window. Nothing about the request changes: the
                // authentication is already in place, the username is already read
                // above for the authentication itself, and the method below cannot
                // throw, so a warning can never become a 401 or a 500.
                logLegacyTokenDeprecation(token, username);
            } else {
                // Invalid token - clear context and return 401
                SecurityContextHolder.clearContext();
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\": \"Invalid or expired JWT token\"}");
                return;
            }
        } catch (Exception e) {
            // Any other exception during validation - clear context and return 401
            SecurityContextHolder.clearContext();
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\": \"Token validation failed: " + e.getMessage() + "\"}");
            return;
        }

        // Continue the filter chain
        filterChain.doFilter(request, response);
    }

    /**
     * Warn — at most once per account per window — that a legacy clear-text API token
     * is actually being used (WIKI4AI-138), and name the account it belongs to
     * (WIKI4AI-167).
     * <p>
     * Why it is worth logging: a legacy token is a JWT that {@link JwtUtil} verifies
     * by signature alone. It has no {@code exp} claim when it was issued by
     * {@link JwtUtil#generateInfiniteToken}, and deleting its row in {@code api_tokens}
     * revokes nothing, because nothing reads that row. The only way such a token ever
     * stops being used is someone rotating it to a {@code w4a_} token, and that needs
     * the deployment to know the legacy one is still live — and <b>whose</b> it is,
     * because rotating it is something the owner of that account has to do.
     * <p>
     * What is logged is the token's <b>display prefix</b> — the first few characters
     * of a JWT, which are its public JSON header — plus the account name from the
     * token's {@code sub} claim. Never the whole token, never its plaintext body,
     * never its signature segment and never a hash of it. The pair
     * {@code display prefix + account} is also the deduplication key, so the map never
     * holds a credential.
     * <p>
     * Why the account is part of the key: the display prefix alone cannot tell two
     * accounts apart, because for every JWT it is the same eight characters
     * ({@code eyJhbGci}, the base64url of {@code {"alg":"HS…"}}). Keyed on the prefix
     * alone, a deployment where three accounts still use legacy tokens produces one
     * warning an hour and names nobody.
     * <p>
     * Two limits keep this safe under load, which is the point of the story: a legacy
     * token may be replayed thousands of times a minute.
     * <ul>
     *   <li><b>time:</b> one warning per key per {@value #DEPRECATION_LOG_WINDOW_MILLIS} ms,</li>
     *   <li><b>memory:</b> at most {@value #DEPRECATION_LOG_MAX_KEYS} keys, with expired
     *       keys dropped from the same write path that adds new ones.</li>
     * </ul>
     * This method never consults {@link ApiTokenService}: the legacy branch stays
     * stateless, unthrottled and free of database work, exactly as before. And it
     * never throws — a diagnostic must not be the reason a valid request fails.
     *
     * @param token    the legacy token that just authenticated; only its display
     *                 prefix is ever used
     * @param username the account the token belongs to, already read by the filter
     *                 for the authentication itself — no extra parsing, no extra work
     */
    private static void logLegacyTokenDeprecation(String token, String username) {
        try {
            if (token == null || token.isBlank()) {
                return;
            }

            String prefix = deprecationDisplayPrefix(token);
            String account = deprecationAccountLabel(username);
            String key = deprecationDeduplicationKey(prefix, account);
            long now = System.currentTimeMillis();

            // compute() makes the check-and-stamp atomic per key, so two concurrent
            // requests carrying the same token cannot both write a warning. Same
            // pattern as TokenRateLimiter.tryAcquire.
            boolean[] warnNow = new boolean[1];
            DEPRECATION_LOGGED.compute(key, (ignored, lastWarnedAt) -> {
                if (lastWarnedAt != null && now - lastWarnedAt < DEPRECATION_LOG_WINDOW_MILLIS) {
                    return lastWarnedAt;  // already warned for this account inside the window
                }
                warnNow[0] = true;
                return now;
            });

            if (!warnNow[0]) {
                return;
            }

            log.warn("Legacy API token {} used by account {} is not revocable and never expires; "
                            + "rotate it to a w4a_ token.", prefix, account);

            // Bounded memory: first drop the keys whose window has passed, and only if
            // the map is still at its ceiling — an abnormal number of distinct legacy
            // tokens in one window — start the window over. The cost is that some
            // tokens warn again; the guarantee is that this map never grows without
            // limit and no scheduler is needed to maintain it.
            if (DEPRECATION_LOGGED.size() > DEPRECATION_LOG_MAX_KEYS) {
                long cutoff = now - DEPRECATION_LOG_WINDOW_MILLIS;
                DEPRECATION_LOGGED.entrySet().removeIf(entry -> entry.getValue() <= cutoff);

                if (DEPRECATION_LOGGED.size() > DEPRECATION_LOG_MAX_KEYS) {
                    log.warn("Deprecation warnings tracked {} distinct legacy tokens; "
                                    + "restarting the deduplication window.",
                            DEPRECATION_LOGGED.size());
                    DEPRECATION_LOGGED.clear();
                    DEPRECATION_LOGGED.put(key, now);
                }
            }
        } catch (Exception unexpected) {
            // Deliberately swallowed. The call sits inside the legacy branch's own
            // try/catch, whose only other outcome is a 401 — a logging failure must
            // not become an authentication failure.
        }
    }

    /**
     * The identifier the deprecation warning shows: the first
     * {@value #DEPRECATION_DISPLAY_PREFIX_LENGTH} characters of the token followed by
     * an ellipsis that marks it as truncated. A token shorter than that is shortened
     * further rather than echoed back whole — the whole token is never logged.
     */
    private static String deprecationDisplayPrefix(String token) {
        int shown = token.length() <= DEPRECATION_DISPLAY_PREFIX_LENGTH
                ? Math.max(0, Math.min(2, token.length() - 1))
                : DEPRECATION_DISPLAY_PREFIX_LENGTH;
        return token.substring(0, shown) + "...";
    }

    /**
     * The account the deprecation warning names: the token's {@code sub} claim, which
     * is the username this filter already authenticated the request as. An account
     * name is not a secret and is not a hash — it is what the migration to
     * {@code w4a_} tokens has to be driven from.
     * <p>
     * Sanitized rather than echoed: whitespace and control characters would let one
     * account occupy several log lines (a log-injection shape), and an unbounded claim
     * would make the deduplication key unbounded. A missing or blank subject becomes
     * {@code "unknown"} instead of an empty field, so a warning always names
     * something — and a token with no subject still gets exactly one key.
     */
    private static String deprecationAccountLabel(String username) {
        if (username == null || username.isBlank()) {
            return "unknown";
        }

        StringBuilder label = new StringBuilder(DEPRECATION_ACCOUNT_MAX_LENGTH);
        for (int i = 0; i < username.length() && label.length() < DEPRECATION_ACCOUNT_MAX_LENGTH; i++) {
            char c = username.charAt(i);
            label.append(c > ' ' && c != '\u007f' ? c : '_');
        }
        return label.isEmpty() ? "unknown" : label.toString();
    }

    /**
     * The deduplication key (WIKI4AI-167): display prefix and account, joined by a
     * separator that cannot appear in the prefix. Neither half is a credential, so the
     * map never holds one — and unlike the prefix alone, this key separates accounts.
     */
    private static String deprecationDeduplicationKey(String prefix, String account) {
        return prefix + "|" + account;
    }

    /**
     * How many distinct (legacy token display prefix, account) pairs the deprecation
     * deduplication currently remembers — for tests and observability, the same role
     * {@code TokenRateLimiter#trackedKeys()} plays for the limiter.
     */
    static int legacyDeprecationTrackedTokens() {
        return DEPRECATION_LOGGED.size();
    }

    /**
     * Forget every deduplication entry. Test hook, mirroring
     * {@code ApiTokenService#clearValidationCache()}: it keeps tests of the warning
     * independent of each other and of execution order without changing how the
     * filter behaves in production.
     */
    static void clearLegacyDeprecationLog() {
        DEPRECATION_LOGGED.clear();
    }

    /**
     * Stateful branch for a {@code w4a_} token: hash lookup, revocation and expiry
     * check, then authorities derived from the token's scopes.
     * <p>
     * A failure here is always a 401 — including an unexpected error inside the
     * lookup. An authentication filter must never fall through to an unauthenticated
     * request, and it must never turn a database problem into a 500 that leaks
     * internals to an unauthenticated caller.
     */
    private void authenticateHashedToken(String token, HttpServletRequest request,
                                         HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        if (apiTokenService == null) {
            // Hand-built filter without the token service: a hashed token cannot be
            // validated, so it is rejected instead of being trusted.
            SecurityContextHolder.clearContext();
            writeUnauthorized(response, "API token validation unavailable");
            return;
        }

        Optional<ApiTokenService.ApiTokenPrincipal> principal;
        try {
            principal = apiTokenService.authenticate(token);
        } catch (RateLimitExceededException limited) {
            // WIKI4AI-139: the token is valid but this request exceeded the token's
            // budget. Answer 429 with Retry-After — not 401 (the credential works) and
            // not 500 (a throttle is a normal outcome). A filter sits before the
            // DispatcherServlet, so GlobalExceptionHandler cannot see an exception that
            // escapes here; the response is written directly, exactly like the 401s.
            SecurityContextHolder.clearContext();
            writeTooManyRequests(response, limited);
            return;
        } catch (Exception e) {
            SecurityContextHolder.clearContext();
            writeUnauthorized(response, "API token validation failed");
            return;
        }

        if (principal.isEmpty()) {
            // Unknown hash, revoked, or expired — one indistinguishable 401, so the
            // response cannot be used to probe which tokens exist.
            SecurityContextHolder.clearContext();
            writeUnauthorized(response, "Invalid, expired or revoked API token");
            return;
        }

        ApiTokenService.ApiTokenPrincipal resolved = principal.get();

        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
        for (String scope : resolved.scopes()) {
            authorities.add(new SimpleGrantedAuthority("SCOPE_" + scope));
        }

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(resolved.username(), null, authorities);

        // Carry the token id and the actor into the request context so later stories
        // (attribution, whoami) can tell a human from an agent on this request.
        authentication.setDetails(new ApiTokenAuthenticationDetails(
                resolved.tokenId(), resolved.userId(), resolved.actorId(), resolved.scopes()));

        SecurityContextHolder.getContext().setAuthentication(authentication);

        request.setAttribute(ApiTokenAuthenticationDetails.REQUEST_ATTRIBUTE,
                authentication.getDetails());

        filterChain.doFilter(request, response);
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\": \"" + message + "\"}");
    }

    /**
     * 429 Too Many Requests with the {@code Retry-After} hint the limiter computed
     * (WIKI4AI-139). Same body shape as the 401 responses so clients can read the
     * reason from one place.
     */
    private void writeTooManyRequests(HttpServletResponse response, RateLimitExceededException limited)
            throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", String.valueOf(limited.getRetryAfterSeconds()));
        response.setContentType("application/json");
        response.getWriter().write("{\"error\": \"" + limited.getMessage() + "\"}");
    }

    /**
     * What a validated hashed API token contributes to the request context: the token
     * row, its owner, the actor it acts as (null when unbound) and its scopes.
     * Attached as {@code Authentication#getDetails} and as a request attribute.
     */
    public record ApiTokenAuthenticationDetails(Long tokenId, Long userId, Long actorId, List<String> scopes) {

        /** Request attribute under which the same details are exposed to controllers. */
        public static final String REQUEST_ATTRIBUTE = "wiki4ai.apiToken";
    }
}
