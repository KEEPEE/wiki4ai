package com.wiki4ai.exception;

/**
 * Raised when a caller exceeds the per-token request budget (WIKI4AI-139).
 * <p>
 * Mapped to HTTP 429 Too Many Requests by {@code GlobalExceptionHandler}, with a
 * {@code Retry-After} header carrying {@link #getRetryAfterSeconds()}. It is a
 * throttle signal, not an authentication failure: the token itself is valid, this
 * particular request simply arrived too fast.
 */
public class RateLimitExceededException extends RuntimeException {

    /** Default advice: the sliding window is one second wide. */
    public static final long DEFAULT_RETRY_AFTER_SECONDS = 1L;

    private final long retryAfterSeconds;

    public RateLimitExceededException(String message) {
        this(message, DEFAULT_RETRY_AFTER_SECONDS);
    }

    public RateLimitExceededException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /**
     * How long the caller should wait before retrying, in whole seconds. Written
     * into the {@code Retry-After} response header.
     */
    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
