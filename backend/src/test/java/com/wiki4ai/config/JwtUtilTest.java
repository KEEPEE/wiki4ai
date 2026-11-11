package com.wiki4ai.config;

import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for JwtUtil.
 * Tests token generation, validation, username extraction with various inputs
 * (valid tokens, expired tokens, malformed tokens).
 */
class JwtUtilTest {

    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        // Use a short secret key for testing
        String secret = "ThisIsASecretKeyForTestingPurposesOnly1234567890AB";
        long expiration = 86400000; // 24 hours
        long refreshExpiration = 604800000; // 7 days

        jwtUtil = new JwtUtil(secret, expiration, refreshExpiration);
    }

    @Test
    void generateToken_shouldCreateValidToken() {
        String token = jwtUtil.generateToken("testuser");

        assertNotNull(token);
        assertTrue(jwtUtil.validateToken(token));
        assertEquals("testuser", jwtUtil.getUsernameFromToken(token));
    }

    @Test
    void generateToken_withExtraClaims_shouldIncludeAllClaims() {
        java.util.Map<String, Object> extraClaims = new java.util.HashMap<>();
        extraClaims.put("role", "ADMIN");
        extraClaims.put("email", "test@example.com");

        String token = jwtUtil.generateToken("testuser", extraClaims);

        assertNotNull(token);
        assertTrue(jwtUtil.validateToken(token));
        assertEquals("testuser", jwtUtil.getUsernameFromToken(token));
    }

    @Test
    void generateRefreshToken_shouldCreateValidRefreshToken() {
        String token = jwtUtil.generateRefreshToken("refreshuser");

        assertNotNull(token);
        // WIKI4AI-197: this expectation flipped, and it is the flip the REV-2 plan
        // predicted. validateToken() now answers "would this be accepted as an ACCESS
        // token", and a REFRESH token must not be: it is a 7-day credential whose only
        // job is to be exchanged at POST /api/v1/auth/refresh. Accepting it as access
        // was the bug (it made the 24-hour access lifetime a 7-day one), so the old
        // assertTrue was pinning the hole rather than the behaviour.
        assertFalse(jwtUtil.validateToken(token));
        assertFalse(jwtUtil.parseAccessToken(token).isPresent(),
                "a refresh token must never parse as an access token");
        // Still a well-formed, correctly signed token that says what it is.
        assertEquals("refreshuser", jwtUtil.getUsernameFromToken(token));
        assertEquals("REFRESH", jwtUtil.getTokenType(token));
    }

    @Test
    void validateToken_withValidToken_shouldReturnTrue() {
        String token = jwtUtil.generateToken("validuser");
        assertTrue(jwtUtil.validateToken(token));
    }

    @Test
    void validateToken_withExpiredToken_shouldReturnFalse() throws InterruptedException {
        // Create a JwtUtil with very short expiration for testing
        JwtUtil shortLivedUtil = new JwtUtil(
                "ThisIsASecretKeyForTestingPurposesOnly1234567890AB",
                1,    // 1ms expiration
                604800000);

        String token = shortLivedUtil.generateToken("expireduser");
        Thread.sleep(10); // Wait for token to expire

        assertFalse(shortLivedUtil.validateToken(token));
    }

    @Test
    void validateToken_withMalformedToken_shouldReturnFalse() {
        assertFalse(jwtUtil.validateToken("not-a-valid-token"));
        assertFalse(jwtUtil.validateToken(""));
        assertFalse(jwtUtil.validateToken(null));
        assertFalse(jwtUtil.validateToken("Bearer "));
        assertFalse(jwtUtil.validateToken("eyJhbGciOiJIUzI1NiJ9.invalid"));
    }

    @Test
    void getUsernameFromToken_shouldExtractCorrectUsername() {
        String token = jwtUtil.generateToken("john_doe");
        assertEquals("john_doe", jwtUtil.getUsernameFromToken(token));
    }

    @Test
    void getUsernameFromToken_withDifferentUsers_shouldReturnCorrectUser() {
        String token1 = jwtUtil.generateToken("user1");
        String token2 = jwtUtil.generateToken("user2");

        assertEquals("user1", jwtUtil.getUsernameFromToken(token1));
        assertEquals("user2", jwtUtil.getUsernameFromToken(token2));
    }

    @Test
    void isTokenExpired_withValidToken_shouldReturnFalse() {
        String token = jwtUtil.generateToken("activeuser");
        assertFalse(jwtUtil.isTokenExpired(token));
    }

    @Test
    void isTokenExpired_withExpiredToken_shouldReturnTrue() throws InterruptedException {
        JwtUtil shortLivedUtil = new JwtUtil(
                "ThisIsASecretKeyForTestingPurposesOnly1234567890AB",
                1, // 1ms expiration
                604800000);

        String token = shortLivedUtil.generateToken("expireduser");
        Thread.sleep(10); // Wait for token to expire

        assertTrue(shortLivedUtil.isTokenExpired(token));
    }

    @Test
    void getTokenType_withAccessToken_shouldReturnACCESS() {
        String token = jwtUtil.generateToken("testuser");
        assertEquals("ACCESS", jwtUtil.getTokenType(token));
    }

    @Test
    void getTokenType_withRefreshToken_shouldReturnREFRESH() {
        String token = jwtUtil.generateRefreshToken("refreshuser");
        assertEquals("REFRESH", jwtUtil.getTokenType(token));
    }

    @Test
    void validateToken_withTamperedToken_shouldReturnFalse() {
        String token = jwtUtil.generateToken("testuser");
        // Tamper with the token by changing a character
        String tamperedToken = token.substring(0, 10) + "X" + token.substring(11);
        assertFalse(jwtUtil.validateToken(tamperedToken));
    }

    @Test
    void generateToken_shouldNotContainPassword() {
        String token = jwtUtil.generateToken("sensitiveuser");
        // Token should not contain any sensitive data beyond username
        assertFalse(token.contains("password"));
        assertFalse(token.contains("secret"));
    }

    @Test
    void generateToken_withExplicitExpiry_shouldUseProvidedDate() {
        // Create a token that expires in 10 years from now (definitely not expired)
        Date farFuture = new Date(System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000 * 10);

        String token = jwtUtil.generateToken("farfutureuser", "ACCESS", new java.util.HashMap<>(), farFuture);

        assertNotNull(token);
        assertTrue(jwtUtil.validateToken(token));
        assertEquals("farfutureuser", jwtUtil.getUsernameFromToken(token));
        // Token should not be expired (it expires in 10 years)
        assertFalse(jwtUtil.isTokenExpired(token));
    }

    @Test
    void generateToken_withNullExpiry_shouldUseDefault() {
        // When expiryDate is null, should use default expiration (same as regular generateToken)
        String tokenWithNull = jwtUtil.generateToken("nulluser", "ACCESS", new java.util.HashMap<>(), null);
        String tokenDefault = jwtUtil.generateToken("nulluser");

        assertNotNull(tokenWithNull);
        assertTrue(jwtUtil.validateToken(tokenWithNull));
        assertEquals("nulluser", jwtUtil.getUsernameFromToken(tokenWithNull));
    }

    @Test
    void generateToken_withPastExpiry_shouldCreateExpiredToken() {
        // Create a token that already expired (1 second in the past)
        Date past = new Date(System.currentTimeMillis() - 1000);

        String token = jwtUtil.generateToken("pastuser", "ACCESS", new java.util.HashMap<>(), past);

        assertNotNull(token);
        assertTrue(jwtUtil.isTokenExpired(token)); // Should be identified as expired
    }

    @Test
    void generateInfiniteToken_shouldCreateWellFormedButUnusableToken() {
        String token = jwtUtil.generateInfiniteToken("infinitetokenuser");

        assertNotNull(token);
        // WIKI4AI-197: flipped. generateInfiniteToken omits 'exp' entirely, and an
        // access token with no expiry can never be retired — which is exactly why the
        // named API tokens built with it were permanent and unrevocable. The parser now
        // requires 'exp', so such a token is well formed and correctly signed but is no
        // longer a credential this API accepts.
        assertFalse(jwtUtil.validateToken(token));
        assertFalse(jwtUtil.parseAccessToken(token).isPresent());
        // The token itself is still intact; only its acceptance changed.
        assertEquals("infinitetokenuser", jwtUtil.getUsernameFromToken(token));
        assertEquals("ACCESS", jwtUtil.getTokenType(token));
    }

    @Test
    void generateInfiniteToken_shouldNeverExpire() {
        String token = jwtUtil.generateInfiniteToken("neverexpires");

        // Should never be identified as expired since there's no 'exp' claim
        assertFalse(jwtUtil.isTokenExpired(token));

        // Even after waiting, it should still not be expired
        try {
            Thread.sleep(10);
        } catch (InterruptedException ignored) {
        }

        assertFalse(jwtUtil.isTokenExpired(token));
    }

    @Test
    void generateInfiniteToken_shouldHaveAccessType() {
        String token = jwtUtil.generateInfiniteToken("typecheckuser");

        assertEquals("ACCESS", jwtUtil.getTokenType(token));
    }

    @Test
    void validateToken_withInfiniteToken_shouldReturnFalse() {
        String token = jwtUtil.generateInfiniteToken("validinfinitetoken");
        // Flipped by WIKI4AI-197 for the reason above: no 'exp' claim, so not a usable
        // access token. isTokenExpired() still reports it as not expired — that method
        // answers a different question and is unchanged.
        assertFalse(jwtUtil.validateToken(token));
        assertFalse(jwtUtil.isTokenExpired(token));
    }

    /**
     * WIKI4AI-197 (REV-2 P0 #6): what a token must satisfy to authenticate a request.
     * <p>
     * Before this method existed the filter asked only "does the signature check out",
     * twice. That accepted a 7-day refresh token as an access credential, accepted a
     * token with no {@code type} at all, and accepted a token with no {@code exp} —
     * which is to say it accepted a credential that can never be retired. Each case
     * below is one of those holes, minted with a real jjwt builder so the claim really
     * is absent rather than mocked to be.
     */
    @Nested
    class ParseAccessTokenRules {

        /** Same secret the outer fixture uses, so tokens minted here verify there. */
        private static final String SECRET = "ThisIsASecretKeyForTestingPurposesOnly1234567890AB";

        private final javax.crypto.SecretKey key =
                io.jsonwebtoken.security.Keys.hmacShaKeyFor(SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        private final JwtUtil util = new JwtUtil(SECRET, 86_400_000L, 604_800_000L);

        /** Mint exactly the claims the test names; anything not set is really absent. */
        private String mint(String subject, String type, java.util.Date expiry) {
            io.jsonwebtoken.JwtBuilder builder = Jwts.builder()
                    .issuedAt(new java.util.Date())
                    .signWith(key);
            if (subject != null) {
                builder.subject(subject);
            }
            if (type != null) {
                builder.claim("type", type);
            }
            if (expiry != null) {
                builder.expiration(expiry);
            }
            return builder.compact();
        }

        private java.util.Date inFuture() {
            return new java.util.Date(System.currentTimeMillis() + 60_000L);
        }

        @Test
        void acceptsWellFormedAccessTokenAndHandsBackItsClaims() {
            String token = mint("alice", "ACCESS", inFuture());

            var claims = util.parseAccessToken(token);

            assertTrue(claims.isPresent());
            assertEquals("alice", claims.get().getSubject(),
                    "the filter reads the account out of these claims, so they must carry it");
            assertTrue(util.validateToken(token));
        }

        @Test
        void rejectsWrongSignature() {
            // The classic forgery: keep someone else's header and payload, put a
            // signature from a DIFFERENT key underneath. The claims still say ADMIN,
            // the token still has three segments and a plausible-looking signature, and
            // only the signature check separates it from a real credential.
            JwtUtil otherKey = new JwtUtil("ADifferentButStillLongEnoughSecret-32b", 1000L, 2000L);
            String foreign = otherKey.generateToken("victim");
            assertTrue(util.parseAccessToken(foreign).isEmpty(),
                    "a token signed with someone else's key is not a credential here");

            String[] parts = mint("admin", "ACCESS", inFuture()).split("\\.");
            assertEquals(3, parts.length, "a compact JWT is header.payload.signature");
            String spliced = parts[0] + "." + parts[1] + "." + foreign.split("\\.")[2];
            assertTrue(util.parseAccessToken(spliced).isEmpty(),
                    "the payload is only trusted because of the signature underneath it");

            // A signature made of valid base64url that simply is not this MAC.
            String wrongButWellFormed = parts[0] + "." + parts[1] + "."
                    + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
            assertTrue(util.parseAccessToken(wrongButWellFormed).isEmpty());
        }

        @Test
        void rejectsRefreshToken() {
            String refresh = util.generateRefreshToken("alice");
            assertEquals("REFRESH", util.getTokenType(refresh));
            assertTrue(util.parseAccessToken(refresh).isEmpty(),
                    "a refresh token is exchanged at /auth/refresh, it does not authenticate a call");
        }

        @Test
        void rejectsMissingTypeClaim() {
            // "Absent means allowed" is how a credential minted for another purpose
            // slips in, so an absent type is a rejection, not a default.
            assertTrue(util.parseAccessToken(mint("alice", null, inFuture())).isEmpty());
        }

        @Test
        void rejectsTypeThatIsNotExactlyAccess() {
            assertTrue(util.parseAccessToken(mint("alice", "access", inFuture())).isEmpty(),
                    "the match is exact; 'access' is not ACCESS");
            assertTrue(util.parseAccessToken(mint("alice", "REFRESH", inFuture())).isEmpty());
            assertTrue(util.parseAccessToken(mint("alice", "", inFuture())).isEmpty());
            assertTrue(util.parseAccessToken(mint("alice", "ACCESS,REFRESH", inFuture())).isEmpty(),
                    "one token may not claim two purposes");
        }

        @Test
        void rejectsMissingExpiry() {
            // The generateInfiniteToken shape: signed, typed ACCESS, and permanent.
            assertTrue(util.parseAccessToken(mint("alice", "ACCESS", null)).isEmpty(),
                    "an access token with no exp can never be retired");
            assertTrue(util.parseAccessToken(util.generateInfiniteToken("alice")).isEmpty());
        }

        @Test
        void rejectsExpiredExpiry() {
            java.util.Date past = new java.util.Date(System.currentTimeMillis() - 1_000L);
            assertTrue(util.parseAccessToken(mint("alice", "ACCESS", past)).isEmpty());
        }

        @Test
        void rejectsMissingOrBlankSubject() {
            assertTrue(util.parseAccessToken(mint(null, "ACCESS", inFuture())).isEmpty(),
                    "an authentication with no principal is not an authentication");
            assertTrue(util.parseAccessToken(mint("", "ACCESS", inFuture())).isEmpty());
            assertTrue(util.parseAccessToken(mint("   ", "ACCESS", inFuture())).isEmpty());
        }

        @Test
        void rejectsNullEmptyAndGarbageWithoutThrowing() {
            // The filter's only legal answer is a 401, so this method must never throw:
            // an exception escaping a filter becomes a 500 with library text in it.
            assertDoesNotThrow(() -> util.parseAccessToken(null));
            assertDoesNotThrow(() -> util.parseAccessToken(""));
            assertDoesNotThrow(() -> util.parseAccessToken("   "));
            assertDoesNotThrow(() -> util.parseAccessToken("not-a-jwt"));
            assertDoesNotThrow(() -> util.parseAccessToken("eyJhbGciOiJIUzI1NiJ9.invalid"));
            assertTrue(util.parseAccessToken(null).isEmpty());
            assertTrue(util.parseAccessToken("not-a-jwt").isEmpty());
        }

        @Test
        void accessAndRefreshTokensDifferOnlyByTypeAndLifetime() {
            // Guards the vocabulary the parser depends on: if a generator ever stopped
            // writing 'type', the parser would refuse every token it mints.
            assertEquals(JwtUtil.ACCESS_TYPE, util.getTokenType(util.generateToken("alice")));
            assertEquals(JwtUtil.REFRESH_TYPE, util.getTokenType(util.generateRefreshToken("alice")));
            assertTrue(util.parseAccessToken(util.generateToken("alice")).isPresent());
        }
    }

    /**
     * WIKI4AI-197 (REV-2 P0 #1): the signing secret must never be a value an attacker
     * can know. Before this commit {@code application.yml} defaulted {@code jwt.secret}
     * to the literal placeholder, and because that placeholder is 36 bytes — over
     * jjwt's 32-byte HMAC minimum — the library accepted it without a word. Every token
     * in such a deployment is forgeable, so the only safe place to discover the mistake
     * is the constructor, before the first request is served.
     */
    @Nested
    class ConstructorRejectsUnusableSecret {

        /** 32 ASCII bytes: the shortest secret that is allowed. */
        private static final String MIN_LENGTH_SECRET = "12345678901234567890123456789012";

        @Test
        void acceptsSecretOfExactlyTheMinimumLength() {
            assertEquals(32, MIN_LENGTH_SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            assertDoesNotThrow(() -> new JwtUtil(MIN_LENGTH_SECRET, 1000L, 2000L));
        }

        @Test
        void rejectsNullSecret() {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> new JwtUtil(null, 1000L, 2000L));
            assertTrue(e.getMessage().contains("jwt.secret"),
                    "the message must name the property to fix, was: " + e.getMessage());
        }

        @Test
        void rejectsBlankSecret() {
            // The shape an unset JWT_SECRET now actually arrives as: application.yml
            // declares ${JWT_SECRET:}, so an unset variable is the empty string.
            assertThrows(IllegalStateException.class, () -> new JwtUtil("", 1000L, 2000L));
            assertThrows(IllegalStateException.class, () -> new JwtUtil("   ", 1000L, 2000L));
        }

        @Test
        void rejectsThePubliclyKnownPlaceholderEvenThoughItIsLongEnough() {
            // The trap this test exists for: the placeholder passes the length check,
            // so a length-only guard would leave the hole wide open.
            assertTrue(JwtUtil.KNOWN_PLACEHOLDER_SECRET.length() >= JwtUtil.MIN_SECRET_BYTES,
                    "the placeholder is only dangerous because it is long enough to pass");

            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> new JwtUtil(JwtUtil.KNOWN_PLACEHOLDER_SECRET, 1000L, 2000L));
            assertTrue(e.getMessage().contains("placeholder"),
                    "the message must say why a long value was still refused, was: " + e.getMessage());
        }

        @Test
        void rejectsSecretShorterThanTheMinimum() {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> new JwtUtil("too-short", 1000L, 2000L));
            assertTrue(e.getMessage().contains("too short"),
                    "the message must say what is wrong, was: " + e.getMessage());
        }

        @Test
        void failureMessageNeverContainsTheSecretItself() {
            // A secret printed in a startup stack trace lands in the log, which is the
            // same leak this commit is closing. The length is fine to show; the value
            // is not.
            String secret = "supersensitive-secret-value-32bytes-ok!!";
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> new JwtUtil("x", 1000L, 2000L));
            assertFalse(e.getMessage().contains(secret));

            IllegalStateException placeholder = assertThrows(IllegalStateException.class,
                    () -> new JwtUtil(JwtUtil.KNOWN_PLACEHOLDER_SECRET, 1000L, 2000L));
            assertFalse(placeholder.getMessage().contains(JwtUtil.KNOWN_PLACEHOLDER_SECRET),
                    "even a known placeholder should not be echoed back as if it were a secret");
        }

        @Test
        void multibyteSecretIsMeasuredInBytesNotCharacters() {
            // The limit is the key size jjwt receives, so it is a byte count. Two
            // strings of nearly the same length must therefore land on opposite sides
            // of the check, and they only do if the measurement is UTF-8 bytes.
            String ascii31 = "1234567890123456789012345678901";   // 31 chars, 31 bytes
            assertEquals(31, ascii31.length());
            assertEquals(31, ascii31.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            assertThrows(IllegalStateException.class, () -> new JwtUtil(ascii31, 1000L, 2000L));

            // 'ě' (U+011D) encodes as two UTF-8 bytes, so this is 31 characters but 32
            // bytes: one character shorter than the string above, and accepted.
            String mixed32Bytes = "123456789012345678901234567890ě";
            assertEquals(31, mixed32Bytes.length());
            assertEquals(32, mixed32Bytes.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            assertDoesNotThrow(() -> new JwtUtil(mixed32Bytes, 1000L, 2000L));
        }
    }
}
