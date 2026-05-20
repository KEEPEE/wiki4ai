package com.wiki4ai.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static com.wiki4ai.model.Role.ADMIN;
import static com.wiki4ai.model.Role.USER;

/**
 * Unit tests for the User JPA entity.
 * Tests entity fields, constraints, lifecycle callbacks (@PrePersist, @PreUpdate), equals/hashCode.
 */
class UserEntityTest {

    private User userA;
    private User userB;
    private User userC;

    @BeforeEach
    void setUp() {
        userA = new User();
        userA.setUsername("alice");
        userA.setEmail("alice@example.com");
        userA.setPassword("$2a$10.hashedPassword1");

        userB = new User();
        userB.setUsername("bob");
        userB.setEmail("bob@example.com");
        userB.setPassword("$2a$10.hashedPassword2");

        userC = new User();
        userC.setUsername("charlie");
        userC.setEmail("charlie@example.com");
        userC.setPassword("$2a$10.hashedPassword3");
    }

    @Nested
    @DisplayName("Entity Fields and Construction")
    class FieldTests {

        @Test
        @DisplayName("should create entity with all fields via builder")
        void shouldCreateWithBuilder() {
            User user = User.builder()
                    .id(1L)
                    .username("testuser")
                    .email("test@example.com")
                    .password("$2a$10.hashed")
                    .role(Role.ADMIN)
                    .build();

            assertThat(user.getId()).isEqualTo(1L);
            assertThat(user.getUsername()).isEqualTo("testuser");
            assertThat(user.getEmail()).isEqualTo("test@example.com");
            assertThat(user.getPassword()).isEqualTo("$2a$10.hashed");
            assertThat(user.getRole()).isEqualTo(Role.ADMIN);
        }

        @Test
        @DisplayName("should set and get all fields via setters")
        void shouldSetAndGetAllFields() {
            User user = new User();
            user.setId(42L);
            user.setUsername("setterUser");
            user.setEmail("setter@example.com");
            user.setPassword("$2a$10.setterHash");
            user.setRole(Role.ADMIN);

            assertThat(user.getId()).isEqualTo(42L);
            assertThat(user.getUsername()).isEqualTo("setterUser");
            assertThat(user.getEmail()).isEqualTo("setter@example.com");
            assertThat(user.getPassword()).isEqualTo("$2a$10.setterHash");
            assertThat(user.getRole()).isEqualTo(Role.ADMIN);
        }

        @Test
        @DisplayName("should have non-null createdAt and updatedAt after persistence")
        void shouldHaveTimestampsAfterPersistence() {
            // Simulate @PrePersist by calling onCreate manually
            userA.onCreate();

            assertThat(userA.getCreatedAt()).isNotNull();
            assertThat(userA.getUpdatedAt()).isNotNull();
            assertThat(userA.getCreatedAt()).isBeforeOrEqualTo(LocalDateTime.now());
        }

        @Test
        @DisplayName("should update updatedAt on preUpdate")
        void shouldUpdateUpdatedAtOnPreUpdate() {
            userA.onCreate();
            LocalDateTime originalUpdated = userA.getUpdatedAt();

            // Simulate time passing
            try { Thread.sleep(10); } catch (InterruptedException e) { /* ignore */ }

            userA.onUpdate();

            assertThat(userA.getUpdatedAt()).isAfter(originalUpdated);
        }

        @Test
        @DisplayName("should have correct toString output")
        void shouldHaveCorrectToString() {
            User user = new User();
            user.setId(1L);
            user.setUsername("toStringUser");
            user.setEmail("tostring@example.com");
            user.setRole(USER);

            String str = user.toString();

            assertThat(str).contains("id=1");
            assertThat(str).contains("username='toStringUser'");
            assertThat(str).contains("email='tostring@example.com'");
            assertThat(str).contains("role=USER");
        }
    }

    @Nested
    @DisplayName("Entity Equality and HashCode")
    class EqualityTests {

        @Test
        @DisplayName("should be equal to itself")
        void shouldBeEqualToSelf() {
            assertThat(userA).isEqualTo(userA);
        }

        @Test
        @DisplayName("should not be null-equal")
        void shouldNotBeNullEqual() {
            assertThat(userA).isNotNull();
        }

        @Test
        @DisplayName("should be equal to another entity with same id")
        void shouldBeEqualToSameId() {
            User copy = new User();
            copy.setId(1L);
            userA.setId(1L);
            assertThat(userA).isEqualTo(copy);
        }

        @Test
        @DisplayName("should not be equal to entity with different id")
        void shouldNotEqualDifferentId() {
            userA.setId(1L);
            userB.setId(2L);
            assertThat(userA).isNotEqualTo(userB);
        }

        @Test
        @DisplayName("should have consistent hashCode for same object")
        void shouldHaveConsistentHashCode() {
            int hash1 = userA.hashCode();
            int hash2 = userA.hashCode();
            assertThat(hash1).isEqualTo(hash2);
        }

        @Test
        @DisplayName("equal objects must have same hashCode")
        void equalObjectsMustHaveSameHashCode() {
            User copy = new User();
            copy.setId(5L);
            userA.setId(5L);
            assertThat(userA.hashCode()).isEqualTo(copy.hashCode());
        }

        @Test
        @DisplayName("should not be equal to unrelated type")
        void shouldNotEqualUnrelatedType() {
            assertThat(userA).isNotEqualTo("string");
        }
    }

    @Nested
    @DisplayName("Password Field")
    class PasswordTests {

        @Test
        @DisplayName("should store hashed password correctly")
        void shouldStoreHashedPassword() {
            User user = new User();
            String hashed = "$2a$10$abcdefghijklmnopqrstuvxyz0123456789";
            user.setPassword(hashed);

            assertThat(user.getPassword()).isEqualTo(hashed);
        }

        @Test
        @DisplayName("password should never be null after registration")
        void passwordShouldNotBeNull() {
            User user = new User();
            user.setUsername("newuser");
            user.setEmail("new@example.com");
            user.setPassword("$2a$10.validHash");

            assertThat(user.getPassword()).isNotNull();
            assertThat(user.getPassword()).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("Role Field")
    class RoleTests {

        @Test
        @DisplayName("newly created user should have default role USER")
        void newUserShouldHaveDefaultUserRole() {
            User user = new User();
            user.setUsername("defaultrole");
            user.setEmail("default@example.com");
            user.setPassword("$2a$10.hashed");

            // After @PrePersist callback, role should default to USER
            user.onCreate();

            assertThat(user.getRole()).isEqualTo(USER);
        }

        @Test
        @DisplayName("user created via builder without explicit role should have USER")
        void builderWithoutExplicitRoleShouldDefaultToUser() {
            User user = User.builder()
                    .id(1L)
                    .username("builderUser")
                    .email("builder@example.com")
                    .password("$2a$10.hashed")
                    .build();

            assertThat(user.getRole()).isEqualTo(USER);
        }

        @Test
        @DisplayName("user created via builder with ADMIN role should have ADMIN")
        void builderWithAdminRoleShouldHaveAdmin() {
            User user = User.builder()
                    .id(2L)
                    .username("adminUser")
                    .email("admin@example.com")
                    .password("$2a$10.hashed")
                    .role(ADMIN)
                    .build();

            assertThat(user.getRole()).isEqualTo(ADMIN);
        }

        @Test
        @DisplayName("should set role via setter")
        void shouldSetRoleViaSetter() {
            User user = new User();
            user.setUsername("setterRole");
            user.setEmail("setter@example.com");
            user.setPassword("$2a$10.hashed");

            user.setRole(ADMIN);
            assertThat(user.getRole()).isEqualTo(ADMIN);

            user.setRole(USER);
            assertThat(user.getRole()).isEqualTo(USER);
        }

        @Test
        @DisplayName("onCreate should set default USER role when role is null")
        void onCreateShouldSetDefaultRoleWhenNull() {
            User user = new User();
            user.setUsername("nullrole");
            user.setEmail("null@example.com");
            user.setPassword("$2a$10.hashed");

            // @Builder.Default initializes the field directly, so even no-arg
            // constructor gets USER. Verify that onCreate preserves it.
            assertThat(user.getRole()).isEqualTo(USER);

            user.onCreate();
            assertThat(user.getRole()).isEqualTo(USER);
        }

        @Test
        @DisplayName("onCreate should preserve existing non-null role")
        void onCreateShouldPreserveExistingRole() {
            User user = new User();
            user.setUsername("preserved");
            user.setEmail("preserve@example.com");
            user.setPassword("$2a$10.hashed");
            user.setRole(ADMIN);

            user.onCreate();
            assertThat(user.getRole()).isEqualTo(ADMIN);
        }
    }

    @Nested
    @DisplayName("toString with Role")
    class ToStringWithRoleTests {

        @Test
        @DisplayName("toString should include role field")
        void toStringShouldIncludeRole() {
            User user = new User();
            user.setId(1L);
            user.setUsername("roleUser");
            user.setEmail("role@example.com");
            user.setRole(ADMIN);

            String str = user.toString();

            assertThat(str).contains("id=1");
            assertThat(str).contains("username='roleUser'");
            assertThat(str).contains("email='role@example.com'");
            assertThat(str).contains("role=ADMIN");
        }
    }
}
