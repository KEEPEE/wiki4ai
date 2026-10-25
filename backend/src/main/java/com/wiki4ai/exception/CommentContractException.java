package com.wiki4ai.exception;

import java.util.List;

/**
 * Raised when a work item comment breaks the payload contract of its {@code kind}
 * (WIKI4AI-149). Mapped to HTTP 422 Unprocessable Entity by
 * {@code GlobalExceptionHandler}.
 * <p>
 * 422 and not 400, and the difference is the whole point of this class: 400 says
 * "your request is malformed — a required field is missing or a value cannot even be
 * read" (a missing {@code body}, an unknown {@code kind}, a body that is not JSON).
 * 422 says "the request is well-formed and I understood every part of it; the
 * {@code payload} simply does not satisfy the contract this kind of comment has to
 * satisfy". A client that gets 422 knows exactly which field of a payload it has to
 * fix, and a client that gets 400 knows its request shape was wrong. Collapsing the
 * two would make the contract unfixable by inspection.
 * <p>
 * It extends {@link RuntimeException} directly and not {@link BadRequestException}
 * or {@link IllegalArgumentException}, so it cannot be swallowed by the existing
 * 400 and 409 mappings of {@code GlobalExceptionHandler}: the new 422 handler is the
 * only one that can reach it.
 */
public class CommentContractException extends RuntimeException {

    /** The kind whose contract was broken. */
    private final String kind;

    /**
     * Human-readable description of every field that broke the contract, e.g.
     * {@code "'where' is required"}, {@code "'artifacts' must be an array"}.
     * Written into the 422 response body so the caller can fix the payload without
     * guessing which field was at fault.
     */
    private final List<String> violations;

    public CommentContractException(String kind, List<String> violations) {
        super("Comment of kind '" + kind + "' breaks its contract: "
                + String.join("; ", violations));
        this.kind = kind;
        this.violations = List.copyOf(violations);
    }

    public String getKind() {
        return kind;
    }

    public List<String> getViolations() {
        return violations;
    }
}
