package com.wiki4ai.exception;

import java.util.List;
import java.util.Map;

/**
 * WIKI4AI-197 (D1): a vault v2 request was understood but refused because the state
 * it assumed is not the state the database is in — a key already initialised, a row
 * that moved since it was read, a batch encrypted under someone else's key.
 *
 * <p>Mapped to 409 by GlobalExceptionHandler, with the offending entry ids in the
 * body. The ids matter: a batch of 120 rows is refused as a whole, and a client that
 * is not told which rows were at fault has to re-read the entire vault to find out.
 *
 * <p>409 rather than 400 or 403 because none of the three readings are wrong — the
 * request is well formed, and the caller can plausibly fix it by re-reading and
 * retrying, which is what a conflict says and what the client does with it.
 */
public class VaultV2ConflictException extends RuntimeException {

    private final List<Long> conflictingIds;
    private final Map<Long, String> reasons;

    public VaultV2ConflictException(String message, List<Long> conflictingIds,
                                    Map<Long, String> reasons) {
        super(message);
        this.conflictingIds = List.copyOf(conflictingIds);
        this.reasons = Map.copyOf(reasons);
    }

    public VaultV2ConflictException(String message) {
        this(message, List.of(), Map.of());
    }

    public List<Long> getConflictingIds() {
        return conflictingIds;
    }

    /**
     * Per-entry explanation, keyed by entry id. Empty for conflicts that are not
     * about particular entries, such as a key that is already initialised.
     */
    public Map<Long, String> getReasons() {
        return reasons;
    }
}
