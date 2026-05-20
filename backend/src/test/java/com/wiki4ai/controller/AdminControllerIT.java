package com.wiki4ai.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wiki4ai.config.JwtUtil;
import com.wiki4ai.model.Role;
import com.wiki4ai.model.User;
import com.wiki4ai.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for AdminController GET /admin/users endpoint.
 * Tests admin access, regular user 403, unauthenticated 401, and search filtering.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "security.enabled=true")
class AdminControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtUtil jwtUtil;

    private String adminToken;
    private String userToken;

    @BeforeEach
    void setUp() {
        // Clean up before each test
        List<User> all = userRepository.findAll();
        for (User u : all) {
            try {
                userRepository.delete(u);
            } catch (Exception ignored) {
            }
        }

        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

        // Create admin user
        User admin = User.builder()
                .username("admin")
                .email("admin@example.com")
                .password(encoder.encode("admin123"))
                .role(Role.ADMIN)
                .build();
        userRepository.save(admin);

        // Create regular user
        User regularUser = User.builder()
                .username("regularuser")
                .email("regular@example.com")
                .password(encoder.encode("user123"))
                .role(Role.USER)
                .build();
        userRepository.save(regularUser);

        // Generate JWT tokens for testing
        adminToken = jwtUtil.generateToken("admin");
        userToken = jwtUtil.generateToken("regularuser");
    }

    @Nested
    @DisplayName("GET /api/v1/admin/users - Admin Access Tests")
    class AdminAccessTests {

        @Test
        @DisplayName("Admin should get list of all users - 200 OK")
        void adminShouldGetAllUsers() throws Exception {
            mockMvc.perform(get("/api/v1/admin/users")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isArray())
                    .andExpect(jsonPath("$.content.length()").value(2))
                    .andExpect(jsonPath("$.totalElements").value(2));
        }

        @Test
        @DisplayName("Admin should get user details without password")
        void adminShouldGetUserDetailsWithoutPassword() throws Exception {
            mockMvc.perform(get("/api/v1/admin/users")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].id").exists())
                    .andExpect(jsonPath("$.content[0].username").exists())
                    .andExpect(jsonPath("$.content[0].email").exists())
                    .andExpect(jsonPath("$.content[0].role").exists())
                    .andExpect(jsonPath("$.content[0].createdAt").exists())
                    .andExpect(jsonPath("$.content[0].password").doesNotExist());
        }

        @Test
        @DisplayName("Admin should see correct roles for users")
        void adminShouldSeeCorrectRoles() throws Exception {
            mockMvc.perform(get("/api/v1/admin/users")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[?(@.username=='admin')].role").value("ADMIN"))
                    .andExpect(jsonPath("$.content[?(@.username=='regularuser')].role").value("USER"));
        }

        @Test
        @DisplayName("Admin should get paginated results")
        void adminShouldGetPaginatedResults() throws Exception {
            mockMvc.perform(get("/api/v1/admin/users")
                            .header("Authorization", "Bearer " + adminToken)
                            .param("page", "0")
                            .param("size", "1")
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.totalElements").value(2))
                    .andExpect(jsonPath("$.totalPages").value(2))
                    .andExpect(jsonPath("$.number").value(0));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/admin/users - Authorization Tests")
    class AuthorizationTests {

        @Test
        @DisplayName("Regular user should get 403 Forbidden")
        void regularUserShouldGetForbidden() throws Exception {
            mockMvc.perform(get("/api/v1/admin/users")
                            .header("Authorization", "Bearer " + userToken)
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").exists());
        }

        @Test
        @DisplayName("No token should get 401 Unauthorized")
        void noTokenShouldGetUnauthorized() throws Exception {
            mockMvc.perform(get("/api/v1/admin/users")
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value("Authentication required"));
        }

        @Test
        @DisplayName("Invalid token should get 401 Unauthorized")
        void invalidTokenShouldGetUnauthorized() throws Exception {
            mockMvc.perform(get("/api/v1/admin/users")
                            .header("Authorization", "Bearer invalid.token.here")
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("GET /api/v1/admin/users - Search Tests")
    class SearchTests {

        @Test
        @DisplayName("Search by username should filter results")
        void searchByUsernameShouldFilter() throws Exception {
            mockMvc.perform(get("/api/v1/admin/users")
                            .header("Authorization", "Bearer " + adminToken)
                            .param("search", "admin")
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.content[0].username").value("admin"));
        }

        @Test
        @DisplayName("Search by email should filter results")
        void searchByEmailShouldFilter() throws Exception {
            mockMvc.perform(get("/api/v1/admin/users")
                            .header("Authorization", "Bearer " + adminToken)
                            .param("search", "regular@example.com")
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.content[0].email").value("regular@example.com"));
        }

        @Test
        @DisplayName("Search with no matches should return empty page")
        void searchWithNoMatchesShouldReturnEmpty() throws Exception {
            mockMvc.perform(get("/api/v1/admin/users")
                            .header("Authorization", "Bearer " + adminToken)
                            .param("search", "nonexistent")
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isArray())
                    .andExpect(jsonPath("$.content.length()").value(0))
                    .andExpect(jsonPath("$.totalElements").value(0));
        }

        @Test
        @DisplayName("Search should be case-insensitive")
        void searchShouldBeCaseInsensitive() throws Exception {
            mockMvc.perform(get("/api/v1/admin/users")
                            .header("Authorization", "Bearer " + adminToken)
                            .param("search", "ADMIN")
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.content[0].username").value("admin"));
        }

        @Test
        @DisplayName("Empty search should return all users")
        void emptySearchShouldReturnAll() throws Exception {
            mockMvc.perform(get("/api/v1/admin/users")
                            .header("Authorization", "Bearer " + adminToken)
                            .param("search", "")
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(2))
                    .andExpect(jsonPath("$.totalElements").value(2));
        }

        @Test
        @DisplayName("Search with partial username match")
        void searchWithPartialUsernameMatch() throws Exception {
            mockMvc.perform(get("/api/v1/admin/users")
                            .header("Authorization", "Bearer " + adminToken)
                            .param("search", "regul")
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.content[0].username").value("regularuser"));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/admin/users - Pagination Tests")
    class PaginationTests {

        @Test
        @DisplayName("Default page size should be 20")
        void defaultPageSizeShouldBe20() throws Exception {
            mockMvc.perform(get("/api/v1/admin/users")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.size").value(20));
        }

        @Test
        @DisplayName("Custom page size should work")
        void customPageSizeShouldWork() throws Exception {
            mockMvc.perform(get("/api/v1/admin/users")
                            .header("Authorization", "Bearer " + adminToken)
                            .param("size", "5")
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.size").value(5));
        }

        @Test
        @DisplayName("Results should be sorted by id ascending by default")
        void resultsShouldBeSortedByIdAsc() throws Exception {
            String response = mockMvc.perform(get("/api/v1/admin/users")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            // Parse and verify ascending order
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(response);
            JsonNode content = root.get("content");
            if (content.size() >= 2) {
                int firstId = content.get(0).get("id").asInt();
                int secondId = content.get(1).get("id").asInt();
                org.junit.jupiter.api.Assertions.assertTrue(
                        firstId < secondId,
                        "Expected ascending order: " + firstId + " < " + secondId);
            }
        }

        @Test
        @DisplayName("Custom sort should work")
        void customSortShouldWork() throws Exception {
            mockMvc.perform(get("/api/v1/admin/users")
                            .header("Authorization", "Bearer " + adminToken)
                            .param("sort", "username,desc")
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].username").value("regularuser"))
                    .andExpect(jsonPath("$.content[1].username").value("admin"));
        }
    }
}
