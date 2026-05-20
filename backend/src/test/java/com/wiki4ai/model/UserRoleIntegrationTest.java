package com.wiki4ai.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for User entity persistence with Role field.
 * Uses H2 in-memory database via @DataJpaTest.
 */
@DataJpaTest
@ActiveProfiles("test")
class UserRoleIntegrationTest {

    @Autowired
    private com.wiki4ai.repository.UserRepository userRepository;

    @Nested
    @DisplayName("Persist User with Default Role")
    class PersistDefaultRoleTests {

        @Test
        @DisplayName("should persist user with default USER role and load it back correctly")
        void shouldPersistAndLoadUserWithDefaultRole() {
            // given
            User user = User.builder()
                    .username("defaultRoleUser")
                    .email("default@role.com")
                    .password("$2a$10.hashedPassword")
                    .build();

            // when
            User saved = userRepository.save(user);

            // then
            assertThat(saved.getId()).isNotNull();
            assertThat(saved.getRole()).isEqualTo(Role.USER);

            // verify loaded from DB
            User loaded = userRepository.findById(saved.getId()).orElseThrow();
            assertThat(loaded.getRole()).isEqualTo(Role.USER);
        }

        @Test
        @DisplayName("should persist user with null role and load as USER (via @PrePersist)")
        void shouldPersistNullRoleAsUser() {
            // given - use no-arg constructor so role is null, then onCreate sets it
            User user = new User();
            user.setUsername("nullRoleUser");
            user.setEmail("null@role.com");
            user.setPassword("$2a$10.hashedPassword");

            // when
            User saved = userRepository.save(user);

            // then - @PrePersist should have set role to USER
            assertThat(saved.getRole()).isEqualTo(Role.USER);

            // verify loaded from DB
            User loaded = userRepository.findById(saved.getId()).orElseThrow();
            assertThat(loaded.getRole()).isEqualTo(Role.USER);
        }
    }

    @Nested
    @DisplayName("Persist User with ADMIN Role")
    class PersistAdminRoleTests {

        @Test
        @DisplayName("should persist user with ADMIN role and load it back correctly")
        void shouldPersistAndLoadUserWithAdminRole() {
            // given
            User user = User.builder()
                    .username("adminUser")
                    .email("admin@example.com")
                    .password("$2a$10.hashedPassword")
                    .role(Role.ADMIN)
                    .build();

            // when
            User saved = userRepository.save(user);

            // then
            assertThat(saved.getId()).isNotNull();
            assertThat(saved.getRole()).isEqualTo(Role.ADMIN);

            // verify loaded from DB
            User loaded = userRepository.findById(saved.getId()).orElseThrow();
            assertThat(loaded.getRole()).isEqualTo(Role.ADMIN);
        }
    }

    @Nested
    @DisplayName("Update User Role")
    class UpdateUserRoleTests {

        @Test
        @DisplayName("should update user role from USER to ADMIN and persist correctly")
        void shouldUpdateRoleFromUserToAdmin() {
            // given
            User user = User.builder()
                    .username("upgradeUser")
                    .email("upgrade@example.com")
                    .password("$2a$10.hashedPassword")
                    .role(Role.USER)
                    .build();
            userRepository.save(user);

            // when - load, change role, save again
            User loaded = userRepository.findById(user.getId()).orElseThrow();
            assertThat(loaded.getRole()).isEqualTo(Role.USER);

            loaded.setRole(Role.ADMIN);
            userRepository.save(loaded);

            // then
            User updated = userRepository.findById(user.getId()).orElseThrow();
            assertThat(updated.getRole()).isEqualTo(Role.ADMIN);
        }

        @Test
        @DisplayName("should update user role from ADMIN to USER and persist correctly")
        void shouldUpdateRoleFromAdminToUser() {
            // given
            User user = User.builder()
                    .username("downgradeUser")
                    .email("downgrade@example.com")
                    .password("$2a$10.hashedPassword")
                    .role(Role.ADMIN)
                    .build();
            userRepository.save(user);

            // when
            User loaded = userRepository.findById(user.getId()).orElseThrow();
            loaded.setRole(Role.USER);
            userRepository.save(loaded);

            // then
            User updated = userRepository.findById(user.getId()).orElseThrow();
            assertThat(updated.getRole()).isEqualTo(Role.USER);
        }
    }

    @Nested
    @DisplayName("Query Users by Role")
    class QueryByRoleTests {

        @Test
        @DisplayName("should be able to distinguish users by role after persistence")
        void shouldDistinguishUsersByRole() {
            // given
            User admin = User.builder()
                    .username("theAdmin")
                    .email("admin@test.com")
                    .password("$2a$10.hashed")
                    .role(Role.ADMIN)
                    .build();
            User regular = User.builder()
                    .username("theUser")
                    .email("user@test.com")
                    .password("$2a$10.hashed")
                    .role(Role.USER)
                    .build();

            userRepository.save(admin);
            userRepository.save(regular);

            // then
            User loadedAdmin = userRepository.findByUsername("theAdmin").orElseThrow();
            User loadedUser = userRepository.findByUsername("theUser").orElseThrow();

            assertThat(loadedAdmin.getRole()).isEqualTo(Role.ADMIN);
            assertThat(loadedUser.getRole()).isEqualTo(Role.USER);
        }
    }
}
