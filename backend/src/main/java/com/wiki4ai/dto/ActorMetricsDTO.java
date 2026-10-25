package com.wiki4ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One actor's activity inside a time window, derived from {@code work_item_events}
 * alone (WIKI4AI-147).
 * <p>
 * Every number here is a sum over events. Nothing is read from {@code work_items},
 * from {@code actors}, or from any counter kept anywhere: an actor with no events
 * does not appear at all, because there is nothing to derive.
 * <p>
 * Durations are {@code double} seconds, not whole numbers. A cycle time of 44.98
 * seconds is a real measurement; rounding it to 45 would make a report look tidy and
 * be a lie about the resolution of the data, and the events carry microsecond
 * timestamps.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ActorMetricsDTO {

    private Long actorId;

    /** Every event attributed to this actor in the window. */
    private long eventCount;

    private long createdCount;
    private long claimedCount;

    /** Transitions this actor moved an item into {@code done}. */
    private long completedCount;

    /** Transitions this actor moved an item into {@code blocked}. */
    private long blockedCount;

    /** Leases this actor was holding when the reaper took them back. */
    private long leaseExpiredCount;

    private long leaseExtendedCount;

    /**
     * Seconds this actor's items spent in each state, keyed by state name.
     * <p>
     * A period is attributed to the actor who moved the item INTO that state, which
     * is the only attribution an event can support: the event names the actor who
     * caused the transition, and after it nobody else is recorded anywhere.
     */
    @Builder.Default
    private Map<String, Double> statusSeconds = new LinkedHashMap<>();

    /** Sum of {@link #statusSeconds}: how much of the window this actor's items occupied. */
    private double totalStatusSeconds;

    /** How many {@code created -> done} cycles this actor completed in the window. */
    private long cycleCount;

    private double totalCycleSeconds;

    /**
     * {@code totalCycleSeconds / cycleCount}, or 0.0 when this actor completed
     * nothing. The average per completion is the number the story asks for, and it
     * is reported next to the count that produced it so a reader can see that a mean
     * over one sample is one sample.
     */
    private double averageCycleSeconds;
}
