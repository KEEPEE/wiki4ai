package com.wiki4ai.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * JPA entity of one pending document embedding (WIKI4AI-197 commit 17, migration V27).
 *
 * <h2>What the row means</h2>
 * "This document's text has changed and its vector has not been written yet." The row is
 * written in the <b>same transaction as the document itself</b>, which is the whole point:
 * the intent to embed becomes part of the save, so it survives a failed sidecar call, a
 * crashed worker thread and a container restart. Without it, the request to embed existed
 * only in memory and any of those three events lost the embedding silently.
 *
 * <h2>Why it exists at all</h2>
 * Embedding used to happen inside the document's own database transaction: save the row,
 * call the sidecar over HTTP, commit. The HTTP call held the transaction — a pooled
 * connection and the row lock — for as long as the sidecar took, and the read timeout was
 * 120 seconds. The work now runs after the commit, and this table is what makes "after the
 * commit" safe rather than merely fast: an in-memory retry dies with the process, a row
 * here does not.
 *
 * <h2>Lifecycle</h2>
 * {@code PENDING} → {@code DONE} when the vector was written, or → {@code FAILED} once
 * {@code attempts} reaches the configured maximum. A row is never deleted: the drainer
 * reads only {@code PENDING} rows whose {@code nextAttemptAt} has arrived, so terminal
 * rows cost nothing beyond their size, and keeping them is what makes "why has this
 * document no vector" answerable after the fact.
 *
 * <h2>document_id is a plain id, not an association</h2>
 * Same convention as every foreign key of {@code WorkItem} and {@code Reservation}. There
 * is also no database FK here on purpose: "the document is gone" is a normal state for a
 * queue row (the document was deleted before its embed ran) and the worker handles it by
 * closing the row, so an FK would only add a cascade to every document delete. See the
 * comment in {@code V27__add_embedding_queue.sql}.
 *
 * <h2>Time columns</h2>
 * {@link LocalDateTime} throughout, {@code TIMESTAMP} without a time zone in V27
 * (SPEC rule 11b). No {@code Instant}.
 */
@Entity
@Table(name = "embedding_queue_entries", indexes = {
        @Index(name = "idx_embedding_queue_due", columnList = "next_attempt_at, id"),
        @Index(name = "idx_embedding_queue_document", columnList = "document_id")
})
@Getter
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
@Check(name = "chk_embedding_queue_status", constraints = "status IN ('PENDING', 'DONE', 'FAILED')")
@Check(name = "chk_embedding_queue_attempts", constraints = "attempts >= 0")
public class EmbeddingQueueEntry {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The document whose vector is missing. No FK — see the class javadoc. */
    @Column(name = "document_id", nullable = false)
    private Long documentId;

    @Column(nullable = false, length = 16)
    @Builder.Default
    private String status = STATUS_PENDING;

    /** Attempts made so far; the row turns FAILED once this reaches the maximum. */
    @Column(nullable = false)
    @Builder.Default
    private int attempts = 0;

    /** Last failure, truncated to the column width by {@link #recordFailure}. */
    @Column(name = "last_error", length = 512)
    private String lastError;

    /** Earliest moment the next attempt may run; the backoff between attempts. */
    @Column(name = "next_attempt_at", nullable = false)
    @Builder.Default
    private LocalDateTime nextAttemptAt = LocalDateTime.now();

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (nextAttemptAt == null) {
            nextAttemptAt = now;
        }
        if (status == null) {
            status = STATUS_PENDING;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public boolean isPending() {
        return STATUS_PENDING.equals(status);
    }

    /** Close the row: the vector was written, or there is nothing left to embed. */
    public void markDone() {
        this.status = STATUS_DONE;
        this.lastError = null;
    }

    /**
     * Record a failed attempt and schedule the next one.
     *
     * @param error      the failure to keep for diagnosis; truncated to the column width
     * @param nextAttempt when the next attempt may run
     * @param maxAttempts the attempt count beyond which the row stops being retried
     */
    public void recordFailure(String error, LocalDateTime nextAttempt, int maxAttempts) {
        this.attempts = this.attempts + 1;
        this.lastError = truncate(error);
        this.nextAttemptAt = nextAttempt != null ? nextAttempt : LocalDateTime.now();
        if (this.attempts >= maxAttempts) {
            this.status = STATUS_FAILED;
        }
    }

    /** Keep the message inside the column; the full stack trace goes to the log. */
    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= 512 ? error : error.substring(0, 512);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EmbeddingQueueEntry other)) return false;
        return id != null && Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
