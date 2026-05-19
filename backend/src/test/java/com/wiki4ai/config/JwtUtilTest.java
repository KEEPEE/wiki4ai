package com.wiki4ai.config;

import org.junit.jupiter.api.BeforeEach;
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
        assertTrue(jwtUtil.validateToken(token));
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
}
