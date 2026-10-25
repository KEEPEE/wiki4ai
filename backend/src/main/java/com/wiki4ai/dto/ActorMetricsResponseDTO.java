package com.wiki4ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Response of {@code GET /api/v1/metrics/actors} (WIKI4AI-147).
 * <p>
 * The window is echoed back because a metric without its window is not a metric: a
 * caller who asked for October and got a number has to be able to see that the number
 * is for October, and that an empty result means "nothing happened in October" rather
 * than "the endpoint is broken".
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ActorMetricsResponseDTO {

    /** Every actor in the window — the scope an ADMIN user is answered with. */
    public static final String SCOPE_ALL = "all";

    /** Only the calling actor — the scope everybody else is answered with. */
    public static final String SCOPE_SELF = "self";

    /**
     * Only the events whose work item belongs to one project — the scope a caller who
     * named {@code projectId} is answered with (WIKI4AI-173).
     */
    public static final String SCOPE_PROJECT = "project";

    private LocalDateTime from;
    private LocalDateTime to;

    /** {@link #SCOPE_ALL}, {@link #SCOPE_SELF} or {@link #SCOPE_PROJECT}: whose numbers these are. */
    private String scope;

    /**
     * Set when the window was narrowed to one project — the caller named
     * {@code projectId} and the answer is the metrics of that project alone
     * (WIKI4AI-173). Null otherwise: the endpoint is instance-wide by default.
     * <p>
     * A project that does not exist, or that the caller may not see, never reaches
     * this field: it is answered 404 before any number is computed, so "no metrics in
     * this project" and "there is no such project" stay two different answers.
     */
    private Long projectId;

    @Builder.Default
    private List<ActorMetricsDTO> actors = new ArrayList<>();

    private Summary summary;

    /**
     * The same totals across every actor the caller is allowed to see.
     * <p>
     * {@link #averageCycleSeconds} here is the average per completion over the whole
     * visible set, which is a different number from the per-actor average and is
     * reported as its own field rather than derived by the reader.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Summary {

        private long eventCount;
        private long createdCount;
        private long claimedCount;
        private long completedCount;
        private long blockedCount;
        private long leaseExpiredCount;
        private long leaseExtendedCount;
        private long cycleCount;
        private double totalCycleSeconds;
        private double averageCycleSeconds;
        private double totalStatusSeconds;

        @Builder.Default
        private Map<String, Double> statusSeconds = new LinkedHashMap<>();

        /**
         * How many of the events counted here were written by the V21 backfill rather
         * than observed live.
         * <p>
         * Reported separately because it is the one number a reader has to be able to
         * discount. A backfilled event is a real state change that really happened —
         * the item really is in {@code done} and really was not in {@code backlog} —
         * but its timestamp is {@code work_items.updated_at}, which is the last
         * modification of the row, not the moment the state was reached. Counting it
         * silently would present reconstructed history as observed history.
         */
        private long backfilledEventCount;

        /** How many distinct actors appear in the window at all. */
        private long actorCount;
    }
}
