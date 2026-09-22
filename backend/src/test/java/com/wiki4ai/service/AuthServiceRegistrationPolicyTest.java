package com.wiki4ai.service;

import com.wiki4ai.config.JwtUtil;
import com.wiki4ai.config.RegistrationProperties;
import com.wiki4ai.dto.RegisterRequestDTO;
import com.wiki4ai.exception.RegistrationDisabledException;
import com.wiki4ai.model.Role;
import com.wiki4ai.repository.ApiTokenRepository;
import com.wiki4ai.repository.RefreshTokenRepository;
import com.wiki4ai.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WIKI4AI-70: unit tests for the self-registration policy in AuthService.
 * Covers all three states of {@code auth.registration.open} without a Spring
 * context (the opt-in end-to-end behaviour is covered by AuthRegistrationOptInTest).
 */
class AuthServiceRegistrationPolicyTest {

    private UserRepository userRepository;
    private AuthService authService;

    private RegisterRequestDTO request() {
        return RegisterRequestDTO.builder()
                .username("newuser")
                .email("newuser@example.com")
                .password("secret123")
                .build();
    }

    private AuthService serviceWith(RegistrationProperties props) {
        return new AuthService(userRepository, mock(RefreshTokenRepository.class),
                mock(ApiTokenRepository.class), mock(EntityManager.class),
                mock(TransactionTemplate.class), mock(JwtUtil.class), props);
    }

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
    }

    @Nested
    @DisplayName("Default policy (auth.registration.open unset)")
    class DefaultPolicyTests {

        @BeforeEach
        void setUp() {
            authService = serviceWith(new RegistrationProperties(null));
        }

        @Test
        @DisplayName("open while the users table is empty")
        void openWhenEmpty() {
            when(userRepository.count()).thenReturn(0L);
            assertThat(authService.isRegistrationOpen()).isTrue();
        }

        @Test
        @DisplayName("closed once at least one user exists")
        void closedAfterFirstUser() {
            when(userRepository.count()).thenReturn(1L);
            assertThat(authService.isRegistrationOpen()).isFalse();
        }

        @Test
        @DisplayName("registerUser succeeds on empty DB and creates a USER role account")
        void registerSucceedsWhenEmpty() {
            when(userRepository.count()).thenReturn(0L);
            when(userRepository.existsByUsername(any())).thenReturn(false);
            when(userRepository.existsByEmail(any())).thenReturn(false);
            when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var dto = authService.registerUser(request());

            assertThat(dto.getRole()).isEqualTo(Role.USER);
        }

        @Test
        @DisplayName("registerUser throws RegistrationDisabledException after first user (→ 403)")
        void registerRejectedWhenClosed() {
            when(userRepository.count()).thenReturn(1L);

            assertThatThrownBy(() -> authService.registerUser(request()))
                    .isInstanceOf(RegistrationDisabledException.class)
                    .hasMessage("Registration is disabled on this instance");

            verify(userRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("Opt-in policy (auth.registration.open=true)")
    class OptInTests {

        @BeforeEach
        void setUp() {
            authService = serviceWith(new RegistrationProperties(Boolean.TRUE));
        }

        @Test
        @DisplayName("open even when users already exist")
        void openRegardlessOfUsers() {
            when(userRepository.count()).thenReturn(42L);
            assertThat(authService.isRegistrationOpen()).isTrue();
        }

        @Test
        @DisplayName("registerUser succeeds even after first user exists")
        void registerSucceedsWhenInitialized() {
            when(userRepository.count()).thenReturn(5L);
            when(userRepository.existsByUsername(any())).thenReturn(false);
            when(userRepository.existsByEmail(any())).thenReturn(false);
            when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            assertThat(authService.registerUser(request()).getRole()).isEqualTo(Role.USER);
        }
    }

    @Nested
    @DisplayName("Opt-out policy (auth.registration.open=false)")
    class OptOutTests {

        @BeforeEach
        void setUp() {
            authService = serviceWith(new RegistrationProperties(Boolean.FALSE));
        }

        @Test
        @DisplayName("closed even on an empty users table")
        void closedEvenWhenEmpty() {
            when(userRepository.count()).thenReturn(0L);
            assertThat(authService.isRegistrationOpen()).isFalse();
        }

        @Test
        @DisplayName("registerUser rejected even before any account exists")
        void registerRejectedOnEmptyDb() {
            when(userRepository.count()).thenReturn(0L);

            assertThatThrownBy(() -> authService.registerUser(request()))
                    .isInstanceOf(RegistrationDisabledException.class);

            verify(userRepository, never()).save(any());
        }
    }
}
