package com.wiki4ai.config;

import com.wiki4ai.service.ReservedUsernames;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * JWT utility class for token generation, validation, and claim extraction.
 * Uses jjwt (Java JWT) library version 0.12.x for all JWT operations.
 * Only loaded when security.enabled=true.
 */
@Component
@ConditionalOnProperty(name = "security.enabled", havingValue = "true", matchIfMissing = true)
public class JwtUtil {

    private final SecretKey secretKey;
    private final long expiration;
    private final long refreshExpiration;

    /**
     * The two and only two values the {@code type} claim takes. Named because
     * WIKI4AI-197 made the value load-bearing: {@link #parseAccessToken} refuses a
     * token whose {@code type} is anything other than {@code ACCESS}, so the string
     * written at mint time and the string required at verify time must be the same
     * constant rather than two literals that happen to agree.
     * <p>
     * Agent credentials are deliberately absent from this list. A {@code w4a_} token is
     * an opaque secret checked by hash in {@code api_tokens}, not a JWT, and the filter
     * routes it by its prefix before any JWT is parsed.
     */
    public static final String ACCESS_TYPE = "ACCESS";

    /** See {@link #ACCESS_TYPE}. Exchanged only at {@code POST /api/v1/auth/refresh}. */
    public static final String REFRESH_TYPE = "REFRESH";

    /**
     * The secret that used to be the default value of {@code jwt.secret} in
     * {@code application.yml}. Named here because it is the one value this class must
     * refuse even though it is long enough to pass every length check: it is 36 bytes,
     * so jjwt's own 32-byte HMAC minimum accepts it without complaint. Being public —
     * printed in the YAML, in the seed deployment doc and in the repo's compose file —
     * it is not a secret at all, and a deployment that boots with it has forgeable
     * tokens for every account. Rejecting it by value, and not only by length, is what
     * closes the hole for anyone who copies the old line instead of setting the
     * variable.
     */
    static final String KNOWN_PLACEHOLDER_SECRET = "your-secret-key-change-in-production";

    /**
     * Minimum length of {@code jwt.secret} in UTF-8 bytes. 32 is the key size
     * HMAC-SHA256 needs for its own security margin, and it is the size jjwt enforces;
     * it is checked here as well so the failure names the property the operator has to
     * set rather than surfacing as a library exception about a weak key.
     */
    static final int MIN_SECRET_BYTES = 32;

    /**
     * @param secret the HMAC signing key, from {@code jwt.secret} / {@code JWT_SECRET}.
     *               Must be present, at least {@value #MIN_SECRET_BYTES} UTF-8 bytes
     *               long, and not the publicly known placeholder.
     * @throws IllegalStateException when the secret is missing, too short, or the
     *         placeholder — i.e. when this process would sign and verify tokens with a
     *         key an attacker could know. Thrown from the constructor on purpose: it
     *         aborts startup, so a misconfigured deployment never serves a single
     *         request under a forgeable key.
     *         <p>
     *         The message never contains the secret itself, only its length and the
     *         property to fix. A secret echoed into a startup stack trace is a secret
     *         in the log.
     */
    public JwtUtil(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.expiration}") long expiration,
            @Value("${jwt.refreshExpiration}") long refreshExpiration) {
        // WIKI4AI-197 (REV-2 P0 #1): fail fast on a secret that cannot be trusted.
        //
        // Order of the three checks matters for the message, not for the outcome:
        // blank first (the common case, an unset JWT_SECRET now arriving as ""),
        // then the placeholder (long enough to look legitimate), then length.
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "jwt.secret is not set. Generate a long random value and export it as JWT_SECRET "
                            + "(at least " + MIN_SECRET_BYTES + " bytes). The previous default was a "
                            + "publicly known placeholder, so starting without a real secret would let "
                            + "anyone mint a valid token for any account.");
        }
        if (KNOWN_PLACEHOLDER_SECRET.equals(secret)) {
            throw new IllegalStateException(
                    "jwt.secret is still the publicly known placeholder value. Replace it with a long "
                            + "random value exported as JWT_SECRET. The placeholder is 36 bytes, which is "
                            + "why a length check alone does not catch it.");
        }
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "jwt.secret is too short: " + secretBytes.length + " bytes, minimum "
                            + MIN_SECRET_BYTES + ". Set a longer value in JWT_SECRET. The secret value "
                            + "itself is not shown here.");
        }
        this.secretKey = (SecretKey) Keys.hmacShaKeyFor(secretBytes);
        this.expiration = expiration;
        this.refreshExpiration = refreshExpiration;
    }

    /**
     * Get the refresh token expiration in seconds.
     */
    public long getRefreshExpirationSeconds() {
        return refreshExpiration;
    }

    /**
     * Generate an access token for the given username.
     *
     * @param username the username to include in the token
     * @return the generated JWT access token string
     */
    public String generateToken(String username) {
        return generateToken(username, ACCESS_TYPE, new HashMap<>());
    }

    /**
     * Generate a token with additional claims for the given username.
     *
     * @param username    the username to include in the token
     * @param extraClaims additional claims to include
     * @return the generated JWT token string
     */
    public String generateToken(String username, Map<String, Object> extraClaims) {
        return generateToken(username, ACCESS_TYPE, extraClaims);
    }

    /**
     * Generate a token with type and additional claims for the given username.
     * Uses the default expiration configured in jwt.expiration.
     *
     * @param username    the username to include in the token
     * @param type        the token type (e.g., "ACCESS" or "REFRESH")
     * @param extraClaims additional claims to include
     * @return the generated JWT token string
     */
    public String generateToken(String username, String type, Map<String, Object> extraClaims) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expiration);

        return Jwts.builder()
                .subject(username)
                .issuedAt(now)
                .expiration(expiryDate)
                .claim("type", type)
                .claims(extraClaims)
                .signWith(secretKey)
                .compact();
    }

    /**
     * Generate a token with explicit expiration date for the given username.
     * This allows tokens to have custom lifetimes (e.g., infinite or longer than default).
     *
     * @param username    the username to include in the token
     * @param type        the token type (e.g., "ACCESS" or "REFRESH")
     * @param extraClaims additional claims to include
     * @param expiryDate  explicit expiration date; if null, uses default expiration
     * @return the generated JWT token string
     */
    public String generateToken(String username, String type, Map<String, Object> extraClaims, Date expiryDate) {
        Date now = new Date();

        // If no explicit expiry provided, use default
        if (expiryDate == null) {
            return generateToken(username, type, extraClaims);
        }

        return Jwts.builder()
                .subject(username)
                .issuedAt(now)
                .expiration(expiryDate)
                .claim("type", type)
                .claims(extraClaims)
                .signWith(secretKey)
                .compact();
    }

    /**
     * Generate an access token with infinite lifetime (no expiration).
     * This is used for named API tokens that should never expire.
     * The token has no 'exp' claim, so it will never be considered expired by the parser.
     *
     * @param username the username to include in the token
     * @return the generated JWT access token string (never expires)
     */
    public String generateInfiniteToken(String username) {
        Date now = new Date();

        return Jwts.builder()
                .subject(username)
                .issuedAt(now)
                .claim("type", ACCESS_TYPE)
                .signWith(secretKey)
                .compact();
    }

    /**
     * Generate a refresh token for the given username.
     *
     * @param username the username to include in the token
     * @return the generated JWT refresh token string
     */
    public String generateRefreshToken(String username) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + refreshExpiration);

        return Jwts.builder()
                .subject(username)
                .issuedAt(now)
                .expiration(expiryDate)
                .claim("type", REFRESH_TYPE)
                .signWith(secretKey)
                .compact();
    }

    /**
     * Parse a token and decide whether it may authenticate a request as an ACCESS
     * token (WIKI4AI-197, REV-2 P0 #6).
     * <p>
     * This is the single parse the filter needs. Before this method existed the filter
     * called {@link #validateToken} and then {@link #getUsernameFromToken}, so every
     * authenticated request was parsed and signature-verified twice, and the second
     * parse could in principle disagree with the first. One call, one answer.
     * <p>
     * Four things must hold, and a token failing any of them is simply not an access
     * token:
     * <ol>
     *   <li><b>a valid signature.</b> Without it the payload is attacker-chosen text.
     *       This is the property the previous commit protects: with a public signing
     *       key, anything an attacker forges satisfies it too.</li>
     *   <li><b>{@code type} is exactly {@code "ACCESS"}.</b> A missing {@code type} is
     *       rejected rather than treated as access. Tokens minted before the claim
     *       existed have no {@code type} at all, and "absent means allowed" is exactly
     *       how a credential minted for another purpose ends up authenticating a
     *       request. A {@code REFRESH} token in particular is a 7-day credential whose
     *       only job is to be exchanged at {@code POST /api/v1/auth/refresh}; letting
     *       it act as an access token made the 24-hour access lifetime meaningless.</li>
     *   <li><b>{@code exp} is present and in the future.</b> jjwt does not require an
     *       {@code exp} claim and {@link #generateInfiniteToken} omits it, so a token
     *       parsed here can legitimately carry no expiry. Reading "no expiry" as "not
     *       expired" is what made those tokens permanent and unrevocable; an access
     *       token that can never expire can never be retired, so it is refused.</li>
     *   <li><b>{@code sub} is non-blank.</b> The subject is the account the request
     *       acts as, and an authentication with no principal is not an authentication.
     *       Reserved names are refused as well, but that check arrives with the
     *       reserved names themselves (plan commit 3) — the constant for them does not
     *       exist yet at this point in the sequence.</li>
     * </ol>
     * <p>
     * Returns empty rather than throwing for every rejection, including a malformed
     * token. The caller's only legal response is a 401, and an exception escaping a
     * filter becomes a 500 that leaks library text to an unauthenticated client.
     *
     * @param token the compact JWT from the {@code Authorization} header
     * @return the verified claims when the token is a usable access token, else empty
     */
    public Optional<Claims> parseAccessToken(String token) {
        return parseTokenOfType(token, ACCESS_TYPE);
    }

    /**
     * Parse a token as a REFRESH token — the credential
     * {@code POST /api/v1/auth/refresh} exchanges for a new access token
     * (WIKI4AI-197, plan commit 2b).
     * <p>
     * Same four rules as {@link #parseAccessToken}, with {@code type} required to be
     * {@code REFRESH} instead. The rules are not a formality here: this is the one
     * endpoint where accepting the wrong type is worse than a 401, because a refresh
     * token can be replayed to mint fresh access tokens indefinitely. An access token
     * must therefore be refused at this door, which is what makes the 24-hour lifetime
     * mean something again — before this endpoint existed, an access token could only
     * be renewed by logging in with the password again.
     * <p>
     * Passing this check is necessary but not sufficient. A refresh token is also a
     * row in {@code refresh_tokens}, and only the caller that can prove possession of
     * the value AND has a live row gets a new access token; see
     * {@code AuthService#refreshAccessToken}.
     *
     * @param token the compact JWT presented as a refresh token
     * @return the verified claims when it is a well-formed refresh token, else empty
     */
    public Optional<Claims> parseRefreshToken(String token) {
        return parseTokenOfType(token, REFRESH_TYPE);
    }

    /**
     * Shared body of the typed parses: verify the signature once, then apply the
     * {@code type}, {@code exp} and {@code sub} rules documented on
     * {@link #parseAccessToken}.
     *
     * @param token the compact JWT
     * @param type  the exact {@code type} claim value required; a token with no
     *              {@code type} claim never matches, whatever this is
     * @return the claims, or empty for any rejection
     */
    private Optional<Claims> parseTokenOfType(String token, String type) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }

        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException malformedOrUnsigned) {
            // One catch covers a structurally broken token, a bad signature and an
            // already-expired one (jjwt throws ExpiredJwtException, a JwtException).
            // The reason is deliberately not surfaced: it is library text, and the
            // caller answers with a 401 whose body must not depend on it.
            return Optional.empty();
        }

        if (!type.equals(claims.get("type", String.class))) {
            return Optional.empty();
        }

        Date expiry = claims.getExpiration();
        if (expiry == null || expiry.before(new Date())) {
            return Optional.empty();
        }

        String subject = claims.getSubject();
        if (subject == null || subject.isBlank()) {
            return Optional.empty();
        }

        // WIKI4AI-197 (REV-2 P0 #4), the rule plan commit 2 deferred until the constant
        // existed. Roughly fifteen controllers resolve their caller as
        // "authentication.getName(), or the literal 'anonymous' when there is none", and
        // permission checks compare against that literal. A token whose subject IS the
        // literal produced an authenticated request that every one of those comparisons
        // read as unauthenticated — the account had proved who it was and was then
        // treated as nobody. Refusing the name here closes that for both token types
        // without touching the fifteen call sites.
        if (ReservedUsernames.isReserved(subject)) {
            return Optional.empty();
        }

        return Optional.of(claims);
    }

    /**
     * Whether a token may authenticate a request as an access token.
     * <p>
     * <b>Its meaning narrowed in WIKI4AI-197.</b> It used to answer "is this signature
     * valid", which accepted a refresh token and accepted a token with no {@code exp}
     * claim at all. It is now {@link #parseAccessToken}'s boolean form, so it answers
     * "would this be accepted as an access token". Callers that used it as a gate for
     * access keep the behaviour they actually wanted; a test that asserted a refresh
     * token or an infinite token "validates" now reads the honest answer, which is no.
     *
     * @param token the JWT token to check
     * @return true only when the token is a usable ACCESS token
     */
    public boolean validateToken(String token) {
        return parseAccessToken(token).isPresent();
    }

    /**
     * Extract the username (subject) from a JWT token.
     *
     * @param token the JWT token
     * @return the username extracted from the token
     */
    public String getUsernameFromToken(String token) {
        return getClaimFromToken(token, Claims::getSubject);
    }

    /**
     * Extract the expiration date from a JWT token.
     *
     * @param token the JWT token
     * @return the expiration date
     */
    public Date getExpirationDateFromToken(String token) {
        return getClaimFromToken(token, Claims::getExpiration);
    }

    /**
     * Extract a specific claim from the token.
     *
     * @param resolver the function to extract the desired claim
     * @return the extracted claim value
     */
    public <T> T getClaimFromToken(String token, Function<Claims, T> resolver) {
        var claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return resolver.apply(claims);
    }

    /**
     * Check if the token has expired.
     * Tokens without an 'exp' claim (infinite-expiry tokens) are never considered expired.
     *
     * @param token the JWT token
     * @return true if the token is expired, false otherwise
     */
    public boolean isTokenExpired(String token) {
        try {
            Date expiration = getExpirationDateFromToken(token);
            // If there's no 'exp' claim (infinite-expiry token), expiration will be null → not expired
            if (expiration == null) {
                return false;
            }
            return expiration.before(new Date());
        } catch (Exception e) {
            // If we can't parse the token, treat as expired
            return true;
        }
    }

    /**
     * Get the token type (ACCESS or REFRESH).
     *
     * @param token the JWT token
     * @return the token type claim value
     */
    public String getTokenType(String token) {
        var claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return claims.get("type", String.class);
    }
}
