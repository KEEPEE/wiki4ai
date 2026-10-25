package com.wiki4ai.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The TTL reaper of WIKI4AI-157: a reservation whose {@code expires_at} has passed stops
 * holding capacity on its own, without its holder having to release it.
 * <p>
 * The shape is {@code WorkItemLeaseReaper}'s, deliberately. Two layers, one implementation:
 * <ul>
 *   <li>{@link #releaseExpired()} is the operation. A test calls it and knows exactly what
 *       ran, when, and how many rows it moved.</li>
 *   <li>{@link #sweepExpiredReservations()} is a trigger that calls it and nothing else, so
 *       the tested code and the production code are the same code.</li>
 *   <li>the trigger is switched off in the test profile by
 *       {@code wiki4ai.resources.scheduling-enabled=false}, which keeps
 *       {@code @EnableScheduling} out of the test contexts (see
 *       {@link com.wiki4ai.config.ReservationSchedulingConfig}). No background thread can
 *       move a row between two assertions in a suite that shares one named in-memory
 *       database; the operation itself stays fully available and fully tested.</li>
 * </ul>
 *
 * <h2>Why the transaction lives in the service</h2>
 * This class holds no {@code @Transactional}. Its statements are bulk JPQL updates, which
 * need a transaction, and a self-call inside one bean does not pass through the proxy that
 * would provide it. Delegating to {@link ResourceService} — a different bean, always
 * proxied — guarantees the transaction.
 *
 * <h2>What it releases</h2>
 * Every unreleased reservation whose expiry is in the past, regardless of the state of the
 * work item it was held for — the analogue of the lease reaper releasing {@code claimed}
 * <b>and</b> {@code running}. A held slot is a held slot; whether the work moved on is not
 * the reaper's question.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationReaper {

    private final ResourceService resourceService;

    /**
     * Run one sweep. Deterministic entry point — this is what a test calls.
     *
     * @return how many reservations the sweep released
     */
    public int releaseExpired() {
        return resourceService.releaseExpiredReservations();
    }

    /**
     * The scheduled trigger. Default period 60 s, configurable with
     * {@code wiki4ai.resources.reaper-interval-ms}.
     * <p>
     * A failed sweep must not take the scheduler down with it: an unreachable database on
     * one tick has to look like one missed tick, not like a dead reaper. The exception is
     * logged and swallowed because the next tick retries and the statements are idempotent.
     */
    @Scheduled(fixedDelayString = "${wiki4ai.resources.reaper-interval-ms:60000}")
    public void sweepExpiredReservations() {
        try {
            int released = releaseExpired();
            if (released > 0) {
                log.info("Reservation TTL sweep released {} reservation(s)", released);
            }
        } catch (RuntimeException e) {
            log.error("Reservation TTL sweep failed; it will run again on the next tick: {}",
                    e.getMessage(), e);
        }
    }
}
