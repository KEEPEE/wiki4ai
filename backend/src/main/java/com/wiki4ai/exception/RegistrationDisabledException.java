package com.wiki4ai.exception;

/**
 * Thrown when a public self-registration request is rejected because registration
 * is disabled on this instance (WIKI4AI-70).
 * <p>
 * Mapped to HTTP 403 Forbidden by {@code GlobalExceptionHandler}. The message is
 * intentionally generic — it must not reveal how many users exist or which
 * configuration produced the decision.
 */
public class RegistrationDisabledException extends RuntimeException {

    public RegistrationDisabledException(String message) {
        super(message);
    }
}
