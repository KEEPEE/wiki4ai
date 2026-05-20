package com.wiki4ai.config;

import com.wiki4ai.model.Role;
import com.wiki4ai.model.User;
import com.wiki4ai.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for InitialAdminBootstrap.
 * <p>
 * Tests the bootstrap logic in isolation using a mocked UserRepository,
 * verifying correct behaviour for all scenarios without needing Spring context.
 */
@ExtendWith(MockitoExtension.class)
class InitialAdminBootstrapTest {

    @Mock
    private UserRepository userRepository;

    private InitialAdminProperties properties;
    private InitialAdminBootstrap bootstrap;

    @BeforeEach
    void setUp() {
        properties = new InitialAdminProperties("admin", "change-me-now");
        bootstrap = new InitialAdminBootstrap(userRepository, properties);
    }

    @Nested
    @DisplayName("Empty Database - Bootstrap Creates Admin")
    class EmptyDatabaseTests {

        @Test
        @DisplayName("Should create admin user when database is empty")
        void shouldCreateAdminWhenEmpty() {
            when(userRepository.count()).thenReturn(0L);

            bootstrap.run();

            verify(userRepository).save(argThat(user ->
                    "admin".equals(user.getUsername()) &&
                    "admin@localhost".equals(user.getEmail()) &&
                    Role.ADMIN == user.getRole()
            ));
        }

        @Test
        @DisplayName("Admin password should be BCrypt hashed")
        void adminPasswordShouldBeHashed() {
            when(userRepository.count()).thenReturn(0L);

            bootstrap.run();

            verify(userRepository).save(argThat(user ->
                    user.getPassword().startsWith("$2a$") &&
                    !"change-me-now".equals(user.getPassword())
            ));
        }

        @Test
        @DisplayName("Admin password should verify against raw password")
        void adminPasswordShouldVerify() {
            when(userRepository.count()).thenReturn(0L);

            ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
            when(userRepository.save(userCaptor.capture())).thenAnswer(invocation -> invocation.getArgument(0));

            bootstrap.run();

            User capturedUser = userCaptor.getValue();
            assertThat(capturedUser).isNotNull();
            BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
            assertThat(encoder.matches("change-me-now", capturedUser.getPassword())).isTrue();
        }

        @Test
        @DisplayName("Admin should have ADMIN role")
        void adminShouldHaveAdminRole() {
            when(userRepository.count()).thenReturn(0L);

            bootstrap.run();

            verify(userRepository).save(argThat(user -> Role.ADMIN == user.getRole()));
        }
    }

    @Nested
    @DisplayName("Users Already Exist - Bootstrap Skipped")
    class UsersExistTests {

        @Test
        @DisplayName("Should NOT create admin when users already exist")
        void shouldNotCreateAdminWhenUsersExist() {
            when(userRepository.count()).thenReturn(5L);

            bootstrap.run();

            verify(userRepository, never()).save(any(User.class));
        }

        @Test
        @DisplayName("Should NOT create admin when exactly one user exists")
        void shouldNotCreateAdminWhenOneUserExists() {
            when(userRepository.count()).thenReturn(1L);

            bootstrap.run();

            verify(userRepository, never()).save(any(User.class));
        }
    }

    @Nested
    @DisplayName("Missing Configuration - Bootstrap Skipped")
    class MissingConfigTests {

        @Test
        @DisplayName("Should skip when username is null")
        void shouldSkipWhenUsernameIsNull() {
            properties = new InitialAdminProperties(null, "some-password");
            bootstrap = new InitialAdminBootstrap(userRepository, properties);
            when(userRepository.count()).thenReturn(0L);

            bootstrap.run();

            verify(userRepository, never()).save(any(User.class));
        }

        @Test
        @DisplayName("Should skip when username is blank")
        void shouldSkipWhenUsernameIsBlank() {
            properties = new InitialAdminProperties("   ", "some-password");
            bootstrap = new InitialAdminBootstrap(userRepository, properties);
            when(userRepository.count()).thenReturn(0L);

            bootstrap.run();

            verify(userRepository, never()).save(any(User.class));
        }

        @Test
        @DisplayName("Should skip when password is null")
        void shouldSkipWhenPasswordIsNull() {
            properties = new InitialAdminProperties("admin", null);
            bootstrap = new InitialAdminBootstrap(userRepository, properties);
            when(userRepository.count()).thenReturn(0L);

            bootstrap.run();

            verify(userRepository, never()).save(any(User.class));
        }

        @Test
        @DisplayName("Should skip when password is blank")
        void shouldSkipWhenPasswordIsBlank() {
            properties = new InitialAdminProperties("admin", "  ");
            bootstrap = new InitialAdminBootstrap(userRepository, properties);
            when(userRepository.count()).thenReturn(0L);

            bootstrap.run();

            verify(userRepository, never()).save(any(User.class));
        }
    }

    @Nested
    @DisplayName("Custom Credentials")
    class CustomCredentialsTests {

        @Test
        @DisplayName("Should use custom username and password from properties")
        void shouldUseCustomCredentials() {
            properties = new InitialAdminProperties("superadmin", "s3cret!");
            bootstrap = new InitialAdminBootstrap(userRepository, properties);
            when(userRepository.count()).thenReturn(0L);

            bootstrap.run();

            verify(userRepository).save(argThat(user ->
                    "superadmin".equals(user.getUsername()) &&
                    "superadmin@localhost".equals(user.getEmail())
            ));
        }
    }
}
