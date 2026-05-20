package com.wiki4ai.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * JWT authentication filter that extends OncePerRequestFilter.
 * Extracts the Authorization: Bearer &lt;token&gt; header, validates the token using JwtUtil,
 * and sets the Authentication in SecurityContextHolder if valid.
 * Handles invalid tokens gracefully by returning 401 Unauthorized.
 * Only loaded when security.enabled=true.
 */
@Component
@ConditionalOnProperty(name = "security.enabled", havingValue = "true", matchIfMissing = true)
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;

    public JwtAuthenticationFilter(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String authorizationHeader = request.getHeader("Authorization");

        // If no Authorization header or not a Bearer token, skip JWT validation
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        // Extract the token from the header
        String token = authorizationHeader.substring(7).trim(); // Remove "Bearer " prefix (7 chars) and trim whitespace

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
}
