package com.wiki4ai.service;

import com.wiki4ai.config.JwtUtil;
import com.wiki4ai.model.User;
import com.wiki4ai.repository.ApiTokenRepository;
import com.wiki4ai.repository.RefreshTokenRepository;
import com.wiki4ai.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AuthService.
 * Tests password hashing (BCrypt) and token generation logic without database interaction.
 */
class AuthServiceTest {

    private UserRepository userRepository;
    private RefreshTokenRepository refreshTokenRepository;
    private ApiTokenRepository apiTokenRepository;
    private EntityManager entityManager;
    private TransactionTemplate transactionTemplate;
    private JwtUtil jwtUtil;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        refreshTokenRepository = mock(RefreshTokenRepository.class);
        apiTokenRepository = mock(ApiTokenRepository.class);
        entityManager = mock(EntityManager.class);
        transactionTemplate = mock(TransactionTemplate.class);
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            var func = invocation.getArgument(0);
            // Simulate successful execution by returning a default refresh token
            return "mock-tx-refresh-token";
        });
        jwtUtil = mock(JwtUtil.class);
        when(jwtUtil.generateToken(anyString())).thenReturn("mock-access-token");
        when(jwtUtil.generateRefreshToken(anyString())).thenReturn("mock-refresh-token");
        when(jwtUtil.getRefreshExpirationSeconds()).thenReturn(86400L); // 24 hours default
        authService = new AuthService(userRepository, refreshTokenRepository, apiTokenRepository, entityManager, transactionTemplate, jwtUtil);
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
        @DisplayName("should generate access token via JwtUtil")
        void shouldGenerateAccessToken() {
            String token = authService.generateAccessToken(1L, "testuser");

            assertThat(token).isNotNull();
            verify(jwtUtil).generateToken("testuser");
        }

        @Test
        @DisplayName("should generate refresh token via JwtUtil")
        void shouldGenerateRefreshToken() {
            User user = User.builder().id(42L).username("refreshuser").email("ref@test.com").password("$2a$10.hashed").build();
            when(userRepository.findById(42L)).thenReturn(Optional.of(user));

            String token = authService.generateRefreshToken(42L);

            assertThat(token).isNotNull();
            verify(jwtUtil).generateRefreshToken("refreshuser");
        }

        @Test
        @DisplayName("access tokens should delegate to JwtUtil")
        void accessTokensShouldDelegateToJwtUtil() {
            String token1 = authService.generateAccessToken(1L, "user1");
            String token2 = authService.generateAccessToken(1L, "user1");

            // Both call jwtUtil.generateToken with same username, so both return mock tokens
            assertThat(token1).isEqualTo("mock-access-token");
            assertThat(token2).isEqualTo("mock-access-token");
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

    @Nested
    @DisplayName("Profile Lookup")
    class ProfileLookupTests {

        @Test
        @DisplayName("should return UserDTO when user exists")
        void shouldReturnUserDtoWhenUserExists() {
            User expected = User.builder().id(1L).username("alice").email("alice@example.com")
                    .password("$2a$10.hashed").role(com.wiki4ai.model.Role.USER).build();
            when(userRepository.findByUsername("alice")).thenReturn(Optional.of(expected));

            var profile = authService.getProfileByUsername("alice");

            assertThat(profile).isNotNull();
            assertThat(profile.getUsername()).isEqualTo("alice");
            assertThat(profile.getEmail()).isEqualTo("alice@example.com");
            assertThat(profile.getRole()).isEqualTo(com.wiki4ai.model.Role.USER);
        }

        @Test
        @DisplayName("should return null when user not found")
        void shouldReturnNullWhenUserNotFound() {
            when(userRepository.findByUsername("nobody")).thenReturn(Optional.empty());

            var profile = authService.getProfileByUsername("nobody");

            assertThat(profile).isNull();
        }
    }

    @Nested
    @DisplayName("Named API Token Generation")
    class NamedApiTokenTests {

        @Test
        @DisplayName("should generate infinite-expiry token when expiresAt is null")
        void shouldGenerateInfiniteTokenWhenExpiresAtIsNull() {
            // given
            User user = User.builder().id(1L).username("tokenuser").email("token@example.com").password("$2a$10.hashed").build();
            when(userRepository.findByUsername("tokenuser")).thenReturn(Optional.of(user));

            // Mock the infinite token method (not the regular generateToken)
            when(jwtUtil.generateInfiniteToken("tokenuser")).thenReturn("mock-infinite-token");

            // Override transactionTemplate to return a proper TokenResponseDTO
            com.wiki4ai.dto.TokenResponseDTO mockResponse = com.wiki4ai.dto.TokenResponseDTO.builder()
                    .accessToken("mock-infinite-token")
                    .name("Agent")
                    .expiresAt(null)
                    .createdAt(java.time.LocalDateTime.now())
                    .tokenId(1L)
                    .build();
            when(transactionTemplate.execute(any())).thenReturn(mockResponse);

            // when
            var response = authService.generateNamedApiToken("tokenuser", "Agent", null);

            // then — should call generateInfiniteToken, NOT generateToken with expiryDate
            assertThat(response).isNotNull();
            assertThat(response.getAccessToken()).isEqualTo("mock-infinite-token");
            assertThat(response.getName()).isEqualTo("Agent");
            assertThat(response.getExpiresAt()).isNull();
            verify(jwtUtil).generateInfiniteToken("tokenuser");
        }

        @Test
        @DisplayName("should generate token with explicit expiry when expiresAt is provided")
        void shouldGenerateExpiryTokenWhenExpiresAtProvided() {
            // given
            User user = User.builder().id(1L).username("expiryuser").email("expiry@example.com").password("$2a$10.hashed").build();
            when(userRepository.findByUsername("expiryuser")).thenReturn(Optional.of(user));

            java.time.LocalDateTime expiryDate = java.time.LocalDateTime.of(2027, 12, 31, 23, 59);
            String expectedToken = "mock-expiry-token";
            when(jwtUtil.generateToken(eq("expiryuser"), eq("ACCESS"), any(), any())).thenReturn(expectedToken);

            // Override transactionTemplate to return a proper TokenResponseDTO
            com.wiki4ai.dto.TokenResponseDTO mockResponse = com.wiki4ai.dto.TokenResponseDTO.builder()
                    .accessToken(expectedToken)
                    .name("ExpiryToken")
                    .expiresAt(expiryDate)
                    .createdAt(java.time.LocalDateTime.now())
                    .tokenId(2L)
                    .build();
            when(transactionTemplate.execute(any())).thenReturn(mockResponse);

            // when
            var response = authService.generateNamedApiToken("expiryuser", "ExpiryToken", expiryDate);

            // then — should call generateToken with the explicit expiry date
            assertThat(response).isNotNull();
            assertThat(response.getAccessToken()).isEqualTo(expectedToken);
            assertThat(response.getName()).isEqualTo("ExpiryToken");
            assertThat(response.getExpiresAt()).isEqualTo(expiryDate);
        }
    }
}
