package com.wiki4ai.service;

import com.wiki4ai.exception.RateLimitExceededException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link TokenRateLimiter} (WIKI4AI-139).
 * <p>
 * No Spring context: the limiter is a plain in-memory component and its contract is
 * arithmetic — a budget per key per window. Small limits and short windows are used
 * so the behaviour is reached immediately instead of after 30 real requests.
 * <p>
 * The contract that matters most for the story: the key is the token, so two tokens
 * of the same user have two budgets, and a rejected request is not charged to the
 * budget (a caller that backs off recovers as soon as its stamps leave the window).
 */
@DisplayName("TokenRateLimiter (WIKI4AI-139)")
class TokenRateLimiterTest {

    private static final int LIMIT = 3;
    private static final long WINDOW_MILLIS = 1_000L;

    private TokenRateLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new TokenRateLimiter(LIMIT, WINDOW_MILLIS);
    }

    @Test
    @DisplayName("Admits exactly the budget and rejects the request after it")
    void shouldAdmitBudgetThenReject() {
        for (int i = 1; i <= LIMIT; i++) {
            assertThat(limiter.tryAcquire("hash-a")).as("request %d", i).isTrue();
        }

        assertThat(limiter.tryAcquire("hash-a")).isFalse();
        assertThat(limiter.tryAcquire("hash-a")).isFalse();
    }

    @Test
    @DisplayName("Keys are independent: one token's full window says nothing about another's")
    void keysShouldHaveIndependentBudgets() {
        for (int i = 0; i < LIMIT; i++) {
            limiter.tryAcquire("hash-a");
        }
        assertThat(limiter.tryAcquire("hash-a")).isFalse();

        // Same user, different token → untouched budget.
        assertThat(limiter.tryAcquire("hash-b")).isTrue();
        assertThat(limiter.tryAcquire("hash-b")).isTrue();
        assertThat(limiter.tryAcquire("hash-b")).isTrue();
        assertThat(limiter.tryAcquire("hash-b")).isFalse();
    }

    @Test
    @DisplayName("enforce() throws RateLimitExceededException carrying the Retry-After advice")
    void enforceShouldThrowWithRetryAdvice() {
        for (int i = 0; i < LIMIT; i++) {
            limiter.enforce("hash-a");
        }

        assertThatThrownBy(() -> limiter.enforce("hash-a"))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessageContaining("limit exceeded")
                .satisfies(failure -> assertThat(((RateLimitExceededException) failure).getRetryAfterSeconds())
                        .isEqualTo(1L));
    }

    @Test
    @DisplayName("A rejected request is not charged — the caller recovers when its stamps age out")
    void rejectedRequestsShouldNotConsumeBudget() throws InterruptedException {
        long shortWindow = 200L;
        TokenRateLimiter fast = new TokenRateLimiter(2, shortWindow);

        assertThat(fast.tryAcquire("hash-a")).isTrue();
        assertThat(fast.tryAcquire("hash-a")).isTrue();
        assertThat(fast.tryAcquire("hash-a")).isFalse();   // rejected, not stored

        Thread.sleep(shortWindow + 60L);

        // Only the two admitted stamps ever entered the window, and both have aged out.
        assertThat(fast.tryAcquire("hash-a")).isTrue();
        assertThat(fast.tryAcquire("hash-a")).isTrue();
        assertThat(fast.tryAcquire("hash-a")).isFalse();
    }

    @Test
    @DisplayName("The window slides: budget is restored once the stamps leave it")
    void windowShouldSlide() throws InterruptedException {
        long shortWindow = 200L;
        TokenRateLimiter fast = new TokenRateLimiter(1, shortWindow);

        assertThat(fast.tryAcquire("hash-a")).isTrue();
        assertThat(fast.tryAcquire("hash-a")).isFalse();

        Thread.sleep(shortWindow + 60L);

        assertThat(fast.tryAcquire("hash-a")).isTrue();
    }

    @Test
    @DisplayName("An unkeyed call is not throttled (unauthenticated traffic is a 401 problem, not a 429 one)")
    void nullOrBlankKeyShouldNotBeThrottled() {
        for (int i = 0; i < 10; i++) {
            assertThat(limiter.tryAcquire(null)).isTrue();
            assertThat(limiter.tryAcquire("  ")).isTrue();
        }
        assertThat(limiter.trackedKeys()).isZero();
    }

    @Test
    @DisplayName("Retry-After is at least one second and follows the configured window")
    void retryAfterShouldFollowWindow() {
        assertThat(new TokenRateLimiter(30, 1_000L).retryAfterSeconds()).isEqualTo(1L);
        assertThat(new TokenRateLimiter(30, 250L).retryAfterSeconds()).isEqualTo(1L);
        assertThat(new TokenRateLimiter(30, 2_000L).retryAfterSeconds()).isEqualTo(2L);
        assertThat(new TokenRateLimiter(30, 2_001L).retryAfterSeconds()).isEqualTo(3L);
    }

    @Test
    @DisplayName("A non-positive configuration falls back to the documented default")
    void nonPositiveConfigurationShouldFallBackToDefaults() {
        TokenRateLimiter broken = new TokenRateLimiter(0, -5L);

        assertThat(broken.getRequestsPerWindow())
                .isEqualTo(TokenRateLimiter.DEFAULT_REQUESTS_PER_WINDOW)
                .isEqualTo(30);
        assertThat(broken.getWindowMillis())
                .isEqualTo(TokenRateLimiter.DEFAULT_WINDOW_MILLIS)
                .isEqualTo(1_000L);
        assertThat(broken.retryAfterSeconds()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Idle keys are swept lazily — no scheduler, no leak")
    void idleKeysShouldBeSweptWithoutAScheduler() throws InterruptedException {
        long shortWindow = 150L;
        TokenRateLimiter fast = new TokenRateLimiter(5, shortWindow);

        for (int i = 0; i < 20; i++) {
            fast.tryAcquire("token-" + i);
        }
        assertThat(fast.trackedKeys()).isEqualTo(20);

        Thread.sleep(shortWindow + 60L);

        // The next write triggers the sweep; nothing else had to be scheduled.
        assertThat(fast.tryAcquire("fresh")).isTrue();
        assertThat(fast.trackedKeys()).isEqualTo(1);
    }

    @Test
    @DisplayName("reset() forgets every window")
    void resetShouldClearWindows() {
        for (int i = 0; i < LIMIT; i++) {
            limiter.tryAcquire("hash-a");
        }
        assertThat(limiter.tryAcquire("hash-a")).isFalse();

        limiter.reset();

        assertThat(limiter.trackedKeys()).isZero();
        assertThat(limiter.tryAcquire("hash-a")).isTrue();
    }

    /**
     * Concurrency is the part a hand-written limiter most often gets wrong: two
     * requests racing on the same key must not both slip past the limit.
     */
    @Nested
    @DisplayName("Concurrent requests on one token")
    class Concurrency {

        @Test
        @DisplayName("Exactly the budget is admitted across 60 racing threads")
        void shouldAdmitExactlyTheBudgetUnderContention() throws InterruptedException {
            int threads = 60;
            int limit = 10;
            TokenRateLimiter shared = new TokenRateLimiter(limit, 10_000L);

            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            AtomicInteger admitted = new AtomicInteger();

            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        if (shared.tryAcquire("same-token")) {
                            admitted.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            assertThat(done.await(15, TimeUnit.SECONDS)).isTrue();
            pool.shutdownNow();

            assertThat(admitted.get()).isEqualTo(limit);
        }

        @Test
        @DisplayName("Contention on one token does not consume another token's budget")
        void contentionShouldStayPerKey() throws InterruptedException {
            int threads = 40;
            TokenRateLimiter shared = new TokenRateLimiter(5, 10_000L);

            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch done = new CountDownLatch(threads);

            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        shared.tryAcquire("hot-token");
                    } finally {
                        done.countDown();
                    }
                });
            }

            assertThat(done.await(15, TimeUnit.SECONDS)).isTrue();
            pool.shutdownNow();

            // "cold-token" was never touched by the contention above.
            for (int i = 0; i < 5; i++) {
                assertThat(shared.tryAcquire("cold-token")).isTrue();
            }
            assertThat(shared.tryAcquire("cold-token")).isFalse();
        }
    }
}
