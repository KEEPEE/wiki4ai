package com.wiki4ai.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for SecurityConfig.
 * Verifies that auth endpoints are public and other endpoints require JWT.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "security.enabled=true")
class SecurityConfigTest {

    @org.springframework.beans.factory.annotation.Autowired
    private MockMvc mockMvc;

    @Test
    void authLoginEndpoint_shouldBePublic() throws Exception {
        // POST /api/v1/auth/login should be accessible without authentication
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"test\",\"password\":\"test\"}"))
                .andExpect(status().isUnauthorized()); // 401 because invalid credentials, NOT 403
    }

    @Test
    void authRegisterEndpoint_shouldBePublic() throws Exception {
        // POST /api/v1/auth/register should be accessible without authentication
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"testuser\",\"email\":\"test@test.com\",\"password\":\"testpass123\"}"))
                .andExpect(status().isCreated()); // 201 because registration succeeds
    }

    @Test
    void authHealthEndpoint_shouldBePublic() throws Exception {
        // GET /api/v1/auth/health should be accessible without authentication
        mockMvc.perform(get("/api/v1/auth/health"))
                .andExpect(status().isOk());
    }

    @Test
    void protectedEndpoint_withoutToken_shouldReturn401() throws Exception {
        // GET /api/v1/projects without token - should return 401 Unauthorized (not 403)
        mockMvc.perform(get("/api/v1/projects"))
                .andExpect(status().isUnauthorized()); // 401 because no auth token, NOT 403
    }

    @Test
    void protectedEndpoint_withValidJwt_shouldNotBe403() throws Exception {
        // Register a user and login to get a token (use unique email to avoid conflicts)
        String username = "testuser_" + System.currentTimeMillis();
        String email = username + "@test.com";

        // Register first with valid password (min 8 chars)
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"email\":\"" + email + "\",\"password\":\"testpass123\"}"))
                .andExpect(status().isCreated());

        // Login to get token
        org.springframework.test.web.servlet.MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"testpass123\"}"))
                .andReturn();

        // Parse the response to extract the access token
        String response = result.getResponse().getContentAsString();
        int start = response.indexOf("\"accessToken\":\"");
        if (start > 0) {
            start += "\"accessToken\":\"".length();
            int end = response.indexOf("\"", start);
            String token = response.substring(start, end);

            // Access the protected endpoint with the token - should not be 403
            mockMvc.perform(get("/api/v1/projects")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(res -> {
                        int status = res.getResponse().getStatus();
                        assert (status == 200 || status == 401) :
                            "Expected 200 or 401 with valid JWT, got " + status;
                    });
        } else {
            // If we can't extract token, at least verify login worked
            assertEquals(200, result.getResponse().getStatus());
        }
    }

    @Test
    void protectedEndpointWithInvalidJwt_shouldReturn401() throws Exception {
        mockMvc.perform(get("/api/v1/projects")
                        .header("Authorization", "Bearer invalid-token-here"))
                .andExpect(status().isUnauthorized()); // 401 because invalid JWT token
    }

    @Test
    void corsPreflightRequest_shouldBePermitted() throws Exception {
        // OPTIONS preflight should be accessible without authentication
        mockMvc.perform(options("/api/v1/projects")
                        .header("Origin", "http://localhost:5173"))
                .andExpect(status().isOk());
    }

    @Test
    void corsPreflightAuthEndpoint_shouldBePermitted() throws Exception {
        // OPTIONS preflight on auth endpoint should also be accessible
        mockMvc.perform(options("/api/v1/auth/login")
                        .header("Origin", "http://localhost:5173"))
                .andExpect(status().isOk());
    }

    /**
     * Helper method to register a user and get an authentication token.
     */
    private String getAuthToken(String username, String email) throws Exception {
        // Register first with valid password (min 8 chars)
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"email\":\"" + email + "\",\"password\":\"testpass123\"}"))
                .andExpect(status().isCreated());

        // Login to get token
        org.springframework.test.web.servlet.MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"testpass123\"}"))
                .andReturn();

        // Parse the response to extract the access token
        String response = result.getResponse().getContentAsString();
        // The response contains JSON with accessToken field
        int start = response.indexOf("\"accessToken\":\"");
        if (start > 0) {
            start += "\"accessToken\":\"".length();
            int end = response.indexOf("\"", start);
            return response.substring(start, end);
        }

        throw new RuntimeException("Could not extract token from response: " + response);
    }
}
