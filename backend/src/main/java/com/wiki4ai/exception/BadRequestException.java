package com.wiki4ai.exception;

/**
 * Exception for invalid client requests detected in the service layer
 * (e.g., missing or blank required fields). Mapped to HTTP 400 Bad Request
 * by GlobalExceptionHandler.
 */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
