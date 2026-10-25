package com.wiki4ai.controller;

import com.wiki4ai.exception.BadRequestException;
import com.wiki4ai.exception.CommentContractException;
import com.wiki4ai.exception.ContentEditException;
import com.wiki4ai.exception.DocumentVersionConflictException;
import com.wiki4ai.exception.RateLimitExceededException;
import com.wiki4ai.exception.RegistrationDisabledException;
import com.wiki4ai.exception.SetupAlreadyCompletedException;
import com.wiki4ai.model.Document;
import com.wiki4ai.repository.DocumentRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.OptimisticLockException;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.StaleObjectStateException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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
     * WIKI4AI-72: used to resolve the document's current version after a JPA
     * optimistic-locking failure, so the 409 body can carry it. Injected with
     * {@code required = false} because @WebMvcTest slices that load this advice
     * do not create repository beans; when absent the 409 body simply omits
     * currentVersion (the client still re-reads and retries).
     */
    @Autowired(required = false)
    private DocumentRepository documentRepository;

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
     * WIKI4AI-70: handle closed self-registration. Returns 403 Forbidden with a
     * generic message — no details about instance state or configuration.
     */
    @ExceptionHandler(RegistrationDisabledException.class)
    public ResponseEntity<Map<String, Object>> handleRegistrationDisabled(
            RegistrationDisabledException ex) {

        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("status", HttpStatus.FORBIDDEN.value());
        body.put("error", "Forbidden");
        body.put("message", ex.getMessage());

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    /**
     * WIKI4AI-69: handle first-run setup called after the instance is already
     * initialized. Returns 403 Forbidden with a generic message — no details
     * about existing accounts are leaked.
     */
    @ExceptionHandler(SetupAlreadyCompletedException.class)
    public ResponseEntity<Map<String, Object>> handleSetupAlreadyCompleted(
            SetupAlreadyCompletedException ex) {

        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("status", HttpStatus.FORBIDDEN.value());
        body.put("error", "Forbidden");
        body.put("message", ex.getMessage());

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    /**
     * WIKI4AI-70: handle unsupported HTTP methods on mapped endpoints (e.g.
     * GET /api/v1/auth/register, which only accepts POST). Returns 405 Method
     * Not Allowed instead of falling through to the generic 500 handler. The
     * Allow header lists the methods the endpoint actually supports.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex) {

        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("status", HttpStatus.METHOD_NOT_ALLOWED.value());
        body.put("error", "Method Not Allowed");
        if (ex.getSupportedMethods() != null) {
            body.put("allow", String.join(", ", ex.getSupportedMethods()));
        }

        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).header("Allow",
                ex.getSupportedMethods() != null ? String.join(", ", ex.getSupportedMethods()) : "")
                .body(body);
    }

    /**
     * WIKI4AI-96: handle malformed request parameter values (e.g. a date query
     * parameter that is not ISO-formatted). Returns 400 Bad Request instead of
     * falling through to the generic 500 handler.
     */
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ex) {

        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("status", HttpStatus.BAD_REQUEST.value());
        body.put("error", "Bad Request");
        body.put("message", "Invalid value for parameter '" + ex.getName() + "'");

        return ResponseEntity.badRequest().body(body);
    }

    /**
     * Handle content-edit failures (find not found, ambiguous find without
     * replaceAll). Returns 400 Bad Request with the edit index and the number
     * of occurrences so clients can recover without guessing.
     */
    @ExceptionHandler(ContentEditException.class)
    public ResponseEntity<Map<String, Object>> handleContentEditException(
            ContentEditException ex) {

        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("status", HttpStatus.BAD_REQUEST.value());
        body.put("error", "Bad Request");
        body.put("message", ex.getMessage());
        body.put("editIndex", ex.getEditIndex());
        body.put("occurrences", ex.getOccurrences());

        return ResponseEntity.badRequest().body(body);
    }

    /**
     * WIKI4AI-72: handle an explicit expectedVersion mismatch (the caller told us
     * which version it based its change on, and that version is stale). Returns
     * 409 Conflict with both versions so the client can re-read and retry.
     */
    @ExceptionHandler(DocumentVersionConflictException.class)
    public ResponseEntity<Map<String, Object>> handleDocumentVersionConflict(
            DocumentVersionConflictException ex) {

        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("status", HttpStatus.CONFLICT.value());
        body.put("error", "Document version conflict");
        body.put("message", ex.getMessage());
        body.put("currentVersion", ex.getCurrentVersion());
        body.put("expectedVersion", ex.getExpectedVersion());

        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /**
     * WIKI4AI-72: handle JPA optimistic-locking failures — a concurrent writer
     * committed while this transaction held a stale state (the @Version safety
     * net for clients that do not send expectedVersion). Returns the same 409
     * shape as an explicit conflict, without expectedVersion: the client should
     * re-read the document and retry its change. currentVersion is resolved from
     * the database when the affected document can be identified.
     */
    @ExceptionHandler({OptimisticLockException.class, ObjectOptimisticLockingFailureException.class})
    public ResponseEntity<Map<String, Object>> handleOptimisticLockingFailure(
            RuntimeException ex) {

        Long currentVersion = resolveCurrentDocumentVersion(ex);

        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("status", HttpStatus.CONFLICT.value());
        body.put("error", "Document version conflict");
        body.put("message", "Document was modified by another writer since your last read. "
                + "Re-read the document and retry your change.");
        if (currentVersion != null) {
            body.put("currentVersion", currentVersion);
        }

        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /**
     * WIKI4AI-72: best-effort resolution of the document's current version after a
     * JPA optimistic-locking failure. Walks the cause chain for an exception that
     * carries the affected entity id (ObjectOptimisticLockingFailureException or
     * Hibernate StaleObjectStateException), then reads the fresh version from the
     * database. Returns null when the document cannot be identified — the 409 body
     * still instructs the client to re-read and retry, so a missing currentVersion
     * never hides the conflict.
     */
    private Long resolveCurrentDocumentVersion(Throwable ex) {
        if (documentRepository == null) {
            return null;
        }
        Throwable t = ex;
        while (t != null) {
            Long id = null;
            String entityName = null;
            if (t instanceof ObjectOptimisticLockingFailureException oolfe) {
                id = asLong(oolfe.getIdentifier());
                entityName = oolfe.getPersistentClassName();
            } else if (t instanceof StaleObjectStateException soe) {
                id = asLong(soe.getIdentifier());
                entityName = soe.getEntityName();
            }
            if (id != null && (entityName == null || entityName.endsWith("Document"))) {
                try {
                    return documentRepository.findById(id)
                            .map(Document::getVersion)
                            .orElse(null);
                } catch (Exception lookupError) {
                    log.debug("Could not resolve current version for conflicted document {}: {}",
                            id, lookupError.getMessage());
                    return null;
                }
            }
            t = t.getCause();
        }
        return null;
    }

    private static Long asLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(value.toString());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * Handle service-layer bad request exceptions (e.g., no field provided in an
     * update payload, blank title). Returns 400 Bad Request with the message.
     */
    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(
            BadRequestException ex) {

        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("status", HttpStatus.BAD_REQUEST.value());
        body.put("error", "Bad Request");
        body.put("message", ex.getMessage());

        return ResponseEntity.badRequest().body(body);
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
        body.put("message", "File size exceeds the maximum allowed upload size (10MB)");

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
     * WIKI4AI-139: handle a per-token rate limit breach. Returns 429 Too Many
     * Requests with a {@code Retry-After} header so a well-behaved client knows how
     * long to back off instead of retrying immediately.
     * <p>
     * This is an addition, not a re-mapping: every handler above keeps its own
     * status. It also guarantees the throttle can never escape as a 500 — a rate
     * limit is a normal outcome of normal traffic, not a server fault.
     */
    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> handleRateLimitExceeded(
            RateLimitExceededException ex) {

        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("status", HttpStatus.TOO_MANY_REQUESTS.value());
        body.put("error", "Too Many Requests");
        body.put("message", ex.getMessage());
        body.put("retryAfterSeconds", ex.getRetryAfterSeconds());

        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(ex.getRetryAfterSeconds()))
                .body(body);
    }

    /**
     * WIKI4AI-149: handle a work item comment whose payload breaks the contract of
     * its kind. Returns 422 Unprocessable Entity with the kind and every field that
     * broke it.
     * <p>
     * <b>This is an addition, not a re-mapping.</b> Every handler above keeps its own
     * status and its own body — in particular {@code IllegalArgumentException → 409}
     * and {@code MethodArgumentNotValidException → 400} are untouched, which is what
     * keeps the work_items tests green. Nothing here widens an existing mapping
     * either: {@link CommentContractException} extends {@link RuntimeException}
     * directly, so no exception that used to reach one of the existing handlers can
     * now reach this one. 422 is reachable from exactly one class.
     * <p>
     * Why 422 and not 400: 400 is the answer to a malformed request — a required
     * field of the request is missing ({@code body}), or the request names something
     * the API does not have ({@code kind}). 422 is the answer to a request that was
     * fully understood, where the payload simply does not satisfy the contract its
     * kind has to satisfy. The two need different fixes, so they need different
     * statuses; {@code WorkItemCommentService} is where the line between them is
     * drawn.
     */
    @ExceptionHandler(CommentContractException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public ResponseEntity<Map<String, Object>> handleCommentContract(
            CommentContractException ex) {

        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("status", HttpStatus.UNPROCESSABLE_ENTITY.value());
        body.put("error", "Unprocessable Entity");
        body.put("message", ex.getMessage());
        body.put("kind", ex.getKind());
        body.put("violations", ex.getViolations());

        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }

    /**
     * WIKI4AI-171: handle a request whose path no controller maps.
     * <p>
     * Spring 6.1 answers an unmapped path through the default resource handler: the
     * {@code /**} registration matches, finds no resource, and throws
     * {@link NoResourceFoundException} — the message the client used to see was
     * {@code "No static resource api/v1/auth/whoami."} carried in a <b>500</b>, because
     * this advice had no handler for that class and the exception fell through to the
     * catch-all below. A URL that does not exist is not a server fault: the answer is
     * 404, and 500 stays what it always was — the answer to a real internal error.
     * <p>
     * <b>This is an addition, not a re-mapping.</b> Every handler above keeps its own
     * exception class, its own status and its own body. The handler is routed by the
     * concrete class {@code NoResourceFoundException} and by nothing else, so no
     * exception that used to reach an existing handler can reach this one:
     * {@code NoResourceFoundException} extends {@link jakarta.servlet.ServletException},
     * which is not a superclass of anything the other handlers answer. Specificity, not
     * order, decides the outcome — {@link Exception} is the least specific handler
     * Spring can pick, so it stays the last resort.
     * <p>
     * The alternative fix — {@code spring.web.resources.add-mappings=false}, which stops
     * the resource handler from being registered at all — was measured and rejected:
     * this backend serves springdoc's swagger-ui through Spring's resource handling, and
     * turning the mappings off removes it. {@code HttpStatusFallbackTest} asserts
     * {@code /swagger-ui.html}, {@code /v3/api-docs} and {@code /actuator/health} still
     * answer, which is what proves the mappings were left on.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResourceFound(
            NoResourceFoundException ex) {

        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("status", HttpStatus.NOT_FOUND.value());
        body.put("error", "Not Found");
        body.put("message", "No endpoint " + ex.getHttpMethod() + " " + ex.getResourcePath());
        body.put("path", ex.getResourcePath());

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
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
