package com.wiki4ai.exception;

/**
 * WIKI4AI-72: thrown when a document update is rejected because the caller's
 * {@code expectedVersion} does not match the document's current version — i.e.
 * another writer modified the document since the caller last read it.
 * Mapped to HTTP 409 Conflict by GlobalExceptionHandler with both versions in
 * the body so the client can re-read and retry.
 */
public class DocumentVersionConflictException extends RuntimeException {

    private final long currentVersion;
    private final long expectedVersion;

    public DocumentVersionConflictException(long currentVersion, long expectedVersion) {
        super("Document was modified since version " + expectedVersion
                + " (current version: " + currentVersion + "). Re-read the document and retry your change.");
        this.currentVersion = currentVersion;
        this.expectedVersion = expectedVersion;
    }

    public long getCurrentVersion() {
        return currentVersion;
    }

    public long getExpectedVersion() {
        return expectedVersion;
    }
}
