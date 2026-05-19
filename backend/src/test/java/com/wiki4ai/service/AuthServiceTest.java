package com.wiki4ai.service;

import com.wiki4ai.model.User;
import com.wiki4ai.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AuthService.
 * Tests password hashing (BCrypt) and token generation logic without database interaction.
 */
class AuthServiceTest {

    private UserRepository userRepository;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        authService = new AuthService(userRepository);
    }

    @Nested
    @DisplayName("Password Hashing (BCrypt)")
    class PasswordHashingTests {

        @Test
        @DisplayName("should encode password with BCrypt")
        void shouldEncodePassword() {
            String rawPassword = "secret123";
            String encoded = authService.encodePassword(rawPassword);

            assertThat(encoded).isNotNull();
            assertThat(encoded).startsWith("$2a$");
            assertThat(encoded).isNotEqualTo(rawPassword); // Must not be plaintext
        }

        @Test
        @DisplayName("should verify matching password")
        void shouldVerifyMatchingPassword() {
            String rawPassword = "secret123";
            String encoded = authService.encodePassword(rawPassword);
            boolean matches = authService.verifyPassword(rawPassword, encoded);

            assertThat(matches).isTrue();
        }

        @Test
        @DisplayName("should reject wrong password")
        void shouldRejectWrongPassword() {
            String correctPassword = "secret123";
            String encoded = authService.encodePassword(correctPassword);
            boolean matches = authService.verifyPassword("wrongpassword", encoded);

            assertThat(matches).isFalse();
        }

        @Test
        @DisplayName("should produce different hashes for same password")
        void shouldProduceDifferentHashes() {
            String rawPassword = "secret123";
            String hash1 = authService.encodePassword(rawPassword);
            String hash2 = authService.encodePassword(rawPassword);

            assertThat(hash1).isNotEqualTo(hash2); // BCrypt salts are random
        }
    }

    @Nested
    @DisplayName("Token Generation Logic")
    class TokenGenerationTests {

        @Test
        @DisplayName("should generate access token with user id and username")
        void shouldGenerateAccessToken() {
            String token = authService.generateAccessToken(1L, "testuser");

            assertThat(token).isNotNull();
            assertThat(token).startsWith("eyJAI_access_token_1_testuser_");
        }

        @Test
        @DisplayName("should generate refresh token with user id")
        void shouldGenerateRefreshToken() {
            String token = authService.generateRefreshToken(42L);

            assertThat(token).isNotNull();
            assertThat(token).startsWith("refresh_42_");
        }

        @Test
        @DisplayName("access tokens should be unique per call")
        void accessTokensShouldBeUnique() {
            String token1 = authService.generateAccessToken(1L, "user1");
            String token2 = authService.generateAccessToken(1L, "user1");

            assertThat(token1).isNotEqualTo(token2); // UUID makes each unique
        }
    }

    @Nested
    @DisplayName("User Lookup")
    class UserLookupTests {

                @Test
        @DisplayName("should find user by username")
        void shouldFindUserByUsername() {
            User expected = User.builder().id(1L).username("alice").email("alice@example.com").password("$2a$10.hashed").build();
            when(userRepository.findByUsername("alice")).thenReturn(Optional.of(expected));

            Optional<User> found = authService.findByUsername("alice");

            assertThat(found).isPresent();
            assertThat(found.get().getUsername()).isEqualTo("alice");
        }

        @Test
        @DisplayName("should return empty when user not found")
        void shouldReturnEmptyWhenUserNotFound() {
            when(userRepository.findByUsername("nobody")).thenReturn(Optional.empty());

            Optional<User> found = authService.findByUsername("nobody");

            assertThat(found).isEmpty();
        }
    }
}
