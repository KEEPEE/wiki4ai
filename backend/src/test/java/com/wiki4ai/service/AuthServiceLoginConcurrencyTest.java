package com.wiki4ai.service;

import com.wiki4ai.dto.AuthResponseDTO;
import com.wiki4ai.dto.LoginRequestDTO;
import com.wiki4ai.model.Role;
import com.wiki4ai.model.User;
import com.wiki4ai.repository.RefreshTokenRepository;
import com.wiki4ai.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WIKI4AI-68 regression test: concurrent logins for the SAME user must all succeed.
 *
 * <p>Before the fix, {@code AuthService.generateAndPersistRefreshToken} ran DELETE + INSERT
 * without per-user serialization. Two concurrent logins for the same user both observed
 * "no existing refresh token", both inserted, and one violated the unique constraint on
 * {@code refresh_tokens.user_id} → {@code DataIntegrityViolationException} → intermittent
 * 500 on POST /api/v1/auth/login under concurrent load (reproduced: up to ~64% failures
 * with 6 parallel workers; see .evidence-WIKI4AI-68/load-test-before-68.txt).</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "security.enabled=true")
class AuthServiceLoginConcurrencyTest {

    private static final int THREADS = 8;
    private static final int LOGINS_PER_THREAD = 25;

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    private User testUser;
    private String rawPassword;

    @BeforeEach
    void setUp() {
        // Unique username per run so parallel/surefire reuse of the shared H2 context
        // never collides with other tests.
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        rawPassword = "pass-" + suffix;
        testUser = User.builder()
                .username("concuser-" + suffix)
                .email("concuser-" + suffix + "@example.com")
                .password(new BCryptPasswordEncoder().encode(rawPassword))
                .role(Role.USER)
                .build();
        testUser = userRepository.save(testUser);
    }

    @AfterEach
    void tearDown() {
        if (testUser != null && testUser.getId() != null) {
            try {
                // orphanRemoval on User.refreshToken cascades the token row away
                User existing = userRepository.findById(testUser.getId()).orElse(null);
                if (existing != null) {
                    userRepository.delete(existing);
                }
            } catch (Exception ignored) {
                // best-effort cleanup; H2 context is per-fork anyway
            }
        }
    }

    @Test
    @DisplayName("WIKI4AI-68: concurrent logins for the same user all succeed (no duplicate-key 500)")
    void concurrentLoginsForSameUserAllSucceed() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger failures = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        for (int t = 0; t < THREADS; t++) {
            futures.add(pool.submit(() -> {
                try {
                    startGate.await(); // maximize overlap of the DELETE+INSERT window
                    for (int i = 0; i < LOGINS_PER_THREAD; i++) {
                        AuthResponseDTO response = authService.loginUser(
                                LoginRequestDTO.builder()
                                        .username(testUser.getUsername())
                                        .password(rawPassword)
                                        .build());
                        if (response == null
                                || response.getAccessToken() == null
                                || response.getRefreshToken() == null) {
                            failures.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    // DataIntegrityViolationException or anything else = regression
                    failures.incrementAndGet();
                }
                return null;
            }));
        }

        startGate.countDown();
        for (Future<?> f : futures) {
            f.get(120, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(failures.get())
                .as("concurrent logins failed %d/%d times (refresh-token race regression)",
                        failures.get(), THREADS * LOGINS_PER_THREAD)
                .isZero();

        // A refresh token row exists for the user after the storm (last-writer-wins by
        // design; the unique constraint on user_id guarantees at most one row). The User
        // side of the OneToOne is eager, so this avoids touching the lazy proxy.
        User reloaded = userRepository.findById(testUser.getId()).orElseThrow();
        assertThat(reloaded.getRefreshToken())
                .as("refresh token should be persisted after concurrent logins")
                .isNotNull();
    }
}
