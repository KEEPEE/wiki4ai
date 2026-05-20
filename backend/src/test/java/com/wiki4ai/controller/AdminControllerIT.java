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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

    @Nested
    @DisplayName("POST /api/v1/admin/users - Create User Tests")
    class CreateUserTests {

        @Test
        @DisplayName("Admin should create a user with role USER - 201 Created")
        void adminShouldCreateUser() throws Exception {
            mockMvc.perform(post("/api/v1/admin/users")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                        "username": "newuser",
                                        "email": "newuser@example.com",
                                        "password": "securepass123",
                                        "role": "USER"
                                    }
                                    """))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").exists())
                    .andExpect(jsonPath("$.username").value("newuser"))
                    .andExpect(jsonPath("$.email").value("newuser@example.com"))
                    .andExpect(jsonPath("$.role").value("USER"))
                    .andExpect(jsonPath("$.createdAt").exists())
                    .andExpect(jsonPath("$.password").doesNotExist());
        }

        @Test
        @DisplayName("Admin should create another admin with role ADMIN - 201 Created")
        void adminShouldCreateAnotherAdmin() throws Exception {
            mockMvc.perform(post("/api/v1/admin/users")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                        "username": "newadmin",
                                        "email": "newadmin@example.com",
                                        "password": "adminpass123",
                                        "role": "ADMIN"
                                    }
                                    """))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").exists())
                    .andExpect(jsonPath("$.username").value("newadmin"))
                    .andExpect(jsonPath("$.email").value("newadmin@example.com"))
                    .andExpect(jsonPath("$.role").value("ADMIN"));
        }

        @Test
        @DisplayName("Regular user should get 403 Forbidden when trying to create a user")
        void regularUserShouldGetForbidden() throws Exception {
            mockMvc.perform(post("/api/v1/admin/users")
                            .header("Authorization", "Bearer " + userToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                        "username": "hacker",
                                        "email": "hacker@example.com",
                                        "password": "hackpass123",
                                        "role": "USER"
                                    }
                                    """))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").exists());
        }

        @Test
        @DisplayName("No token should get 401 Unauthorized")
        void noTokenShouldGetUnauthorized() throws Exception {
            mockMvc.perform(post("/api/v1/admin/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                        "username": "notoken",
                                        "email": "notoken@example.com",
                                        "password": "nopass12345",
                                        "role": "USER"
                                    }
                                    """))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value("Authentication required"));
        }

        @Test
        @DisplayName("Duplicate username should return 409 Conflict")
        void duplicateUsernameShouldReturnConflict() throws Exception {
            // "admin" already exists from setUp
            mockMvc.perform(post("/api/v1/admin/users")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                        "username": "admin",
                                        "email": "different@example.com",
                                        "password": "somepass123",
                                        "role": "USER"
                                    }
                                    """))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").exists());
        }

        @Test
        @DisplayName("Duplicate email should return 409 Conflict")
        void duplicateEmailShouldReturnConflict() throws Exception {
            // "admin@example.com" already exists from setUp
            mockMvc.perform(post("/api/v1/admin/users")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                        "username": "differentuser",
                                        "email": "admin@example.com",
                                        "password": "somepass123",
                                        "role": "USER"
                                    }
                                    """))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").exists());
        }

        @Test
        @DisplayName("Password should be stored as BCrypt hash")
        void passwordShouldBeHashed() throws Exception {
            mockMvc.perform(post("/api/v1/admin/users")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                        "username": "hashcheck",
                                        "email": "hashcheck@example.com",
                                        "password": "mysecretpassword",
                                        "role": "USER"
                                    }
                                    """))
                    .andExpect(status().isCreated());

            // Verify password is hashed in DB
            User createdUser = userRepository.findByUsername("hashcheck").orElseThrow();
            org.junit.jupiter.api.Assertions.assertTrue(
                    createdUser.getPassword().startsWith("$2a$"),
                    "Password should be BCrypt hashed");
            org.junit.jupiter.api.Assertions.assertNotEquals(
                    "mysecretpassword", createdUser.getPassword(),
                    "Password should not be stored in plain text");
        }

        @Test
        @DisplayName("Blank username should return 400 Bad Request")
        void blankUsernameShouldReturnBadRequest() throws Exception {
            mockMvc.perform(post("/api/v1/admin/users")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                        "username": "",
                                        "email": "blank@example.com",
                                        "password": "somepass123",
                                        "role": "USER"
                                    }
                                    """))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Short password should return 400 Bad Request")
        void shortPasswordShouldReturnBadRequest() throws Exception {
            mockMvc.perform(post("/api/v1/admin/users")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                        "username": "shortpw",
                                        "email": "short@example.com",
                                        "password": "short",
                                        "role": "USER"
                                    }
                                    """))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Missing role should return 400 Bad Request")
        void missingRoleShouldReturnBadRequest() throws Exception {
            mockMvc.perform(post("/api/v1/admin/users")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                        "username": "norole",
                                        "email": "norole@example.com",
                                        "password": "somepass123"
                                    }
                                    """))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("PUT /api/v1/admin/users/{id}/role - Change Role Tests")
    class ChangeRoleTests {

        @Test
        @DisplayName("Admin should change user role from USER to ADMIN - 200 OK")
        void adminShouldChangeUserRoleToAdmin() throws Exception {
            // regularuser has id=2 (created after admin in setUp)
            mockMvc.perform(put("/api/v1/admin/users/2/role")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"role": "ADMIN"}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(2))
                    .andExpect(jsonPath("$.username").value("regularuser"))
                    .andExpect(jsonPath("$.role").value("ADMIN"));

            // Verify in DB
            User updated = userRepository.findById(2L).orElseThrow();
            org.junit.jupiter.api.Assertions.assertEquals(Role.ADMIN, updated.getRole());
        }

        @Test
        @DisplayName("Admin should change user role from ADMIN to USER - 200 OK")
        void adminShouldChangeUserRoleToUser() throws Exception {
            // Create a second admin first
            mockMvc.perform(post("/api/v1/admin/users")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                        "username": "secondadmin",
                                        "email": "second@example.com",
                                        "password": "pass12345678",
                                        "role": "ADMIN"
                                    }
                                    """))
                    .andExpect(status().isCreated());

            Long secondAdminId = userRepository.findByUsername("secondadmin").orElseThrow().getId();

            // Now demote to USER
            mockMvc.perform(put("/api/v1/admin/users/" + secondAdminId + "/role")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"role": "USER"}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.role").value("USER"));

            // Verify in DB
            User updated = userRepository.findById(secondAdminId).orElseThrow();
            org.junit.jupiter.api.Assertions.assertEquals(Role.USER, updated.getRole());
        }

        @Test
        @DisplayName("Regular user should get 403 Forbidden when trying to change role")
        void regularUserShouldGetForbiddenOnChangeRole() throws Exception {
            mockMvc.perform(put("/api/v1/admin/users/2/role")
                            .header("Authorization", "Bearer " + userToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"role": "ADMIN"}
                                    """))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").exists());
        }

        @Test
        @DisplayName("No token should get 401 Unauthorized")
        void noTokenShouldGetUnauthorizedOnChangeRole() throws Exception {
            mockMvc.perform(put("/api/v1/admin/users/2/role")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"role": "ADMIN"}
                                    """))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value("Authentication required"));
        }

        @Test
        @DisplayName("Non-existent user id should return 404 Not Found")
        void nonExistentUserShouldReturnNotFound() throws Exception {
            mockMvc.perform(put("/api/v1/admin/users/99999/role")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"role": "ADMIN"}
                                    """))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").exists());
        }

        @Test
        @DisplayName("Missing role in request body should return 400 Bad Request")
        void missingRoleShouldReturnBadRequest() throws Exception {
            mockMvc.perform(put("/api/v1/admin/users/2/role")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("DELETE /api/v1/admin/users/{id} - Delete User Tests")
    class DeleteUserTests {

        @Test
        @DisplayName("Admin should delete a regular user - 204 No Content")
        void adminShouldDeleteRegularUser() throws Exception {
            Long userIdToDelete = userRepository.findByUsername("regularuser").orElseThrow().getId();

            mockMvc.perform(delete("/api/v1/admin/users/" + userIdToDelete)
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isNoContent());

            // Verify user is deleted from DB
            org.junit.jupiter.api.Assertions.assertFalse(
                    userRepository.findById(userIdToDelete).isPresent(),
                    "User should be deleted from database");
        }

        @Test
        @DisplayName("Admin should delete another admin user - 204 No Content")
        void adminShouldDeleteAnotherAdmin() throws Exception {
            // Create a second admin first
            mockMvc.perform(post("/api/v1/admin/users")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                        "username": "adminToDelete",
                                        "email": "todelete@example.com",
                                        "password": "pass12345678",
                                        "role": "ADMIN"
                                    }
                                    """))
                    .andExpect(status().isCreated());

            Long adminIdToDelete = userRepository.findByUsername("adminToDelete").orElseThrow().getId();

            mockMvc.perform(delete("/api/v1/admin/users/" + adminIdToDelete)
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isNoContent());

            // Verify deleted
            org.junit.jupiter.api.Assertions.assertFalse(
                    userRepository.findById(adminIdToDelete).isPresent(),
                    "Admin user should be deleted from database");
        }

        @Test
        @DisplayName("Regular user should get 403 Forbidden when trying to delete a user")
        void regularUserShouldGetForbiddenOnDelete() throws Exception {
            Long userId = userRepository.findByUsername("regularuser").orElseThrow().getId();

            mockMvc.perform(delete("/api/v1/admin/users/" + userId)
                            .header("Authorization", "Bearer " + userToken))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").exists());
        }

        @Test
        @DisplayName("No token should get 401 Unauthorized")
        void noTokenShouldGetUnauthorizedOnDelete() throws Exception {
            mockMvc.perform(delete("/api/v1/admin/users/2"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value("Authentication required"));
        }

        @Test
        @DisplayName("Admin cannot delete their own account - 400 Bad Request")
        void adminCannotDeleteSelf() throws Exception {
            Long adminId = userRepository.findByUsername("admin").orElseThrow().getId();

            mockMvc.perform(delete("/api/v1/admin/users/" + adminId)
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("You cannot delete your own account"));

            // Verify admin still exists
            org.junit.jupiter.api.Assertions.assertTrue(
                    userRepository.findById(adminId).isPresent(),
                    "Admin should still exist after self-delete attempt");
        }

        @Test
        @DisplayName("Non-existent user id should return 404 Not Found")
        void nonExistentUserShouldReturnNotFound() throws Exception {
            mockMvc.perform(delete("/api/v1/admin/users/99999")
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").exists());
        }

        @Test
        @DisplayName("After deletion, user should not appear in list")
        void deletedUserShouldNotAppearInList() throws Exception {
            Long userIdToDelete = userRepository.findByUsername("regularuser").orElseThrow().getId();

            // Delete the user
            mockMvc.perform(delete("/api/v1/admin/users/" + userIdToDelete)
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isNoContent());

            // Verify only 1 user remains (admin)
            mockMvc.perform(get("/api/v1/admin/users")
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.content[0].username").value("admin"));
        }
    }
}
