package com.wiki4ai.config;

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

    public JwtUtil(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.expiration}") long expiration,
            @Value("${jwt.refreshExpiration}") long refreshExpiration) {
        this.secretKey = (SecretKey) Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expiration = expiration;
        this.refreshExpiration = refreshExpiration;
    }

    /**
     * Generate an access token for the given username.
     *
     * @param username the username to include in the token
     * @return the generated JWT access token string
     */
    public String generateToken(String username) {
        return generateToken(username, "ACCESS", new HashMap<>());
    }

    /**
     * Generate a token with additional claims for the given username.
     *
     * @param username    the username to include in the token
     * @param extraClaims additional claims to include
     * @return the generated JWT token string
     */
    public String generateToken(String username, Map<String, Object> extraClaims) {
        return generateToken(username, "ACCESS", extraClaims);
    }

    /**
     * Generate a token with type and additional claims for the given username.
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
                .claim("type", "REFRESH")
                .signWith(secretKey)
                .compact();
    }

    /**
     * Validate a JWT token.
     *
     * @param token the JWT token to validate
     * @return true if the token is valid, false otherwise
     */
    public boolean validateToken(String token) {
        try {
            Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
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
     *
     * @param token the JWT token
     * @return true if the token is expired, false otherwise
     */
    public boolean isTokenExpired(String token) {
        try {
            Date expiration = getExpirationDateFromToken(token);
            return expiration.before(new Date());
        } catch (Exception e) {
            // If we can't parse the token or it's expired, treat as expired
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
