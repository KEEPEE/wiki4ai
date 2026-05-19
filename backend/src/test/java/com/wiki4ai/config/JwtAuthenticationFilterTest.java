package com.wiki4ai.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
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
}
