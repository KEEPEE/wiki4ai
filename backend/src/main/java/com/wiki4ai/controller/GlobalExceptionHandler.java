package com.wiki4ai.controller;

import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Global exception handler for REST controllers.
 * Provides consistent error responses across all API endpoints.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Handle validation errors from @Valid annotated request bodies.
     * Returns 400 Bad Request with field-level error details.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationExceptions(
            MethodArgumentNotValidException ex) {

        Map<String, String> errors = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        fieldError -> fieldError.getField(),
                        fieldError -> fieldError.getDefaultMessage() != null ? fieldError.getDefaultMessage() : "Invalid value",
                        (existing, replacement) -> existing
                ));

        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("status", HttpStatus.BAD_REQUEST.value());
        body.put("error", "Bad Request");
        body.put("errors", errors);

        return ResponseEntity.badRequest().body(body);
    }

    /**
     * Handle entity not found exceptions.
     * Returns 404 Not Found with a descriptive message.
     */
    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleEntityNotFound(
            EntityNotFoundException ex) {

        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("status", HttpStatus.NOT_FOUND.value());
        body.put("error", "Not Found");
        body.put("message", ex.getMessage());

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    /**
     * Handle access denied exceptions from RBAC permission checks.
     * Returns 403 Forbidden with a descriptive message about the missing permission.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(
            AccessDeniedException ex) {

        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("status", HttpStatus.FORBIDDEN.value());
        body.put("error", "Forbidden");
        body.put("message", ex.getMessage());

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    /**
     * Handle illegal argument exceptions (e.g., duplicate name, invalid file format).
     * Returns 403 Forbidden for ownership violations, 409 Conflict for business rule violations,
     * or 400 Bad Request for validation errors.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(
            IllegalArgumentException ex) {

        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", LocalDateTime.now().toString());

        String message = ex.getMessage() != null ? ex.getMessage() : "";

        // Ownership/access violations (e.g., vault entry belongs to another user) -> 403
        if (message.contains("does not belong")) {
            body.put("status", HttpStatus.FORBIDDEN.value());
            body.put("error", "Forbidden");
        } else if (message.contains("Invalid file format")) {
            // Validation errors -> 400
            body.put("status", HttpStatus.BAD_REQUEST.value());
            body.put("error", "Bad Request");
        } else {
            // Business rule violations -> 409
            body.put("status", HttpStatus.CONFLICT.value());
            body.put("error", "Conflict");
        }
        body.put("message", ex.getMessage());

        return ResponseEntity.status((Integer) body.get("status")).body(body);
    }

    /**
     * Handle max upload size exceeded exceptions.
     * Returns 413 Payload Too Large with a descriptive message.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxUploadSize(
            MaxUploadSizeExceededException ex) {

        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("status", HttpStatus.PAYLOAD_TOO_LARGE.value());
        body.put("error", "Payload Too Large");
        body.put("message", "File size exceeds the maximum allowed size of 5MB");

        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(body);
    }

    /**
     * Handle malformed multipart requests (e.g. missing/incorrect boundary because a
     * client sent a non-multipart Content-Type for a file upload). Returns 400 Bad
     * Request instead of a raw 500 - this happens during request argument binding,
     * before the controller method body runs, so it can't be caught there.
     */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<Map<String, Object>> handleMultipartException(
            MultipartException ex) {

        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("status", HttpStatus.BAD_REQUEST.value());
        body.put("error", "Bad Request");
        body.put("message", "Invalid file upload request: " + ex.getMessage());

        return ResponseEntity.badRequest().body(body);
    }

    /**
     * Catch-all handler for unhandled exceptions.
     * Returns 500 Internal Server Error.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneralException(
            Exception ex) {

        log.error("Unhandled exception occurred", ex);

        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("status", HttpStatus.INTERNAL_SERVER_ERROR.value());
        body.put("error", "Internal Server Error");
        body.put("message", ex.getMessage() != null ? ex.getMessage() : "An unexpected error occurred");

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
