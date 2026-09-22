package com.wiki4ai.exception;

/**
 * Thrown when {@code POST /api/v1/auth/setup} is called after the instance has
 * already been initialized, i.e. the users table is no longer empty (WIKI4AI-69).
 * <p>
 * Mapped to HTTP 403 Forbidden by {@code GlobalExceptionHandler}. The message is
 * intentionally generic — it must not reveal details about existing accounts.
 */
public class SetupAlreadyCompletedException extends RuntimeException {

    public SetupAlreadyCompletedException(String message) {
        super(message);
    }
}
