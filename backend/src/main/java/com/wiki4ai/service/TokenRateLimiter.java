package com.wiki4ai.service;

import com.wiki4ai.exception.RateLimitExceededException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sliding-window rate limiter keyed by API token (WIKI4AI-139).
 * <p>
 * <b>Per token, not per IP.</b> The key is the token's SHA-256 hash — the very value
 * {@code api_tokens.token_hash} is looked up by — so the budget follows the
 * credential. Two tokens of the same user have two independent budgets: a script
 * hammering one token cannot freeze the owner's other credentials, and a shared NAT
 * or proxy address never makes innocent callers collide.
 * <p>
 * <b>Own implementation, no dependency.</b> The repository has no bucket4j,
 * resilience4j or similar library and adding one is out of scope, so this is a plain
 * {@link ConcurrentHashMap} of per-key timestamp windows. Each window holds the
 * request timestamps that are still inside the window; a call is admitted while the
 * window has room.
 * <p>
 * <b>Lazy maintenance, no scheduler.</b> The project has no {@code @EnableScheduling}
 * and none is added here. Expired stamps are dropped when a key is written, and the
 * whole map is swept at most once per window from the same write path — so idle keys
 * disappear on the next traffic that touches the limiter, and an unused limiter holds
 * nothing.
 * <p>
 * Thread safety comes from {@link ConcurrentHashMap#compute(Object, java.util.function.BiFunction)}:
 * the read-trim-append of one key happens inside the map's per-key lock, so
 * concurrent requests for the same token cannot both slip past the limit.
 */
@Component
@Slf4j
public class TokenRateLimiter {

    /** Requests admitted per key per window when nothing is configured. */
    public static final int DEFAULT_REQUESTS_PER_WINDOW = 30;

    /** Width of the sliding window, in milliseconds. */
    public static final long DEFAULT_WINDOW_MILLIS = 1_000L;

    /**
     * Hard ceiling on tracked keys. A limiter keyed by token can only grow with the
     * number of tokens actually being used inside the current window; this is the
     * backstop that keeps a flood of distinct keys from turning the map into a memory
     * leak. Sweeping is what normally bounds the map — this only fires if traffic is
     * already abnormal.
     */
    private static final int MAX_TRACKED_KEYS = 100_000;

    /** token hash → request timestamps inside the current window. */
    private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<>();

    private final int requestsPerWindow;
    private final long windowMillis;

    /** Last moment the whole map was swept. 0 = never. */
    private final AtomicLong lastSweepMillis = new AtomicLong(0L);

    public TokenRateLimiter(
            @Value("${wiki4ai.rate-limit.requests-per-second:30}") int requestsPerSecond,
            @Value("${wiki4ai.rate-limit.window-millis:1000}") long windowMillis) {

        // A zero or negative limit would either block everything or disable the
        // feature by accident; both are configuration mistakes, so they fall back to
        // the documented default rather than being honoured.
        this.requestsPerWindow = requestsPerSecond > 0 ? requestsPerSecond : DEFAULT_REQUESTS_PER_WINDOW;
        this.windowMillis = windowMillis > 0 ? windowMillis : DEFAULT_WINDOW_MILLIS;

        if (requestsPerSecond <= 0 || windowMillis <= 0) {
            log.warn("Ignoring non-positive rate limit configuration ({} per {} ms); using {} per {} ms",
                    requestsPerSecond, windowMillis, this.requestsPerWindow, this.windowMillis);
        }
    }

    /**
     * Record one request for {@code key} and report whether it is admitted.
     * <p>
     * A rejected call still trims the window — it is not charged to the budget, so a
     * caller that backs off recovers as soon as its old stamps fall out of the window.
     *
     * @param key the token identity (its SHA-256 hash); null or blank is not throttled
     * @return true when the request is within the budget, false when it must be 429
     */
    public boolean tryAcquire(String key) {
        if (key == null || key.isBlank()) {
            // No identity to charge. Unauthenticated traffic is handled by the auth
            // layer (401), not by this limiter.
            return true;
        }

        long now = System.currentTimeMillis();
        sweepIfNeeded(now);

        boolean[] admitted = new boolean[1];

        windows.compute(key, (ignored, existing) -> {
            Deque<Long> stamps = existing != null ? existing : new ArrayDeque<>();
            trim(stamps, now);

            if (stamps.size() < requestsPerWindow) {
                stamps.addLast(now);
                admitted[0] = true;
            }
            return stamps;
        });

        return admitted[0];
    }

    /**
     * Same as {@link #tryAcquire(String)} but throws instead of returning false.
     *
     * @throws RateLimitExceededException when the window for this key is full
     */
    public void enforce(String key) {
        if (!tryAcquire(key)) {
            throw new RateLimitExceededException(
                    "API token request limit exceeded. Try again in "
                            + retryAfterSeconds() + " second(s).",
                    retryAfterSeconds());
        }
    }

    /**
     * Whole seconds to advertise in {@code Retry-After}: the width of one window,
     * rounded up, never zero (a 0-second Retry-After is not a usable hint).
     */
    public long retryAfterSeconds() {
        return Math.max(1L, (windowMillis + 999L) / 1000L);
    }

    /** Requests admitted per key per window. */
    public int getRequestsPerWindow() {
        return requestsPerWindow;
    }

    /** Width of the sliding window in milliseconds. */
    public long getWindowMillis() {
        return windowMillis;
    }

    /**
     * How many keys currently hold a window — for tests and observability. A healthy
     * limiter tracks only the tokens that were used inside the current window.
     */
    public int trackedKeys() {
        return windows.size();
    }

    /**
     * Forget every window. Test hook: keeps rate-limit tests independent of each
     * other without changing the limiter's behaviour in production.
     */
    public void reset() {
        windows.clear();
        lastSweepMillis.set(0L);
    }

    /** Drop stamps that are no longer inside the window. */
    private void trim(Deque<Long> stamps, long now) {
        long cutoff = now - windowMillis;
        while (!stamps.isEmpty() && stamps.peekFirst() <= cutoff) {
            stamps.pollFirst();
        }
    }

    /**
     * Lazy maintenance (the replacement for a scheduler): at most once per window,
     * drop every key whose newest stamp has already left the window. Keys that are no
     * longer used therefore cost nothing, and no background thread is needed.
     */
    private void sweepIfNeeded(long now) {
        long last = lastSweepMillis.get();
        if (now - last < windowMillis) {
            return;
        }
        if (!lastSweepMillis.compareAndSet(last, now)) {
            return;
        }

        long cutoff = now - windowMillis;
        windows.entrySet().removeIf(entry -> {
            Deque<Long> stamps = entry.getValue();
            return stamps.isEmpty() || stamps.peekLast() <= cutoff;
        });

        // Backstop for abnormal traffic: an unbounded number of distinct keys must
        // never become a memory problem. Dropping all windows only costs fairness to
        // callers that are already being throttled.
        if (windows.size() > MAX_TRACKED_KEYS) {
            log.warn("Rate limiter tracked {} keys; clearing all windows", windows.size());
            windows.clear();
        }
    }
}
