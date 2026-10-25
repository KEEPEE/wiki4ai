package com.wiki4ai.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The lease reaper of WIKI4AI-142: work items whose lease expired go back to the
 * pool on their own, without an agent having to release them.
 *
 * <h2>Two layers, one implementation</h2>
 * {@link #releaseExpired()} is the operation. {@link #sweepExpiredLeases()} is a
 * trigger that calls it and nothing else. That split is what makes the first
 * {@code @Scheduled} task of this project safe to have in a test suite:
 * <ul>
 *   <li>a test calls {@code releaseExpired()} and knows exactly what ran, when it
 *       ran, and how many rows it moved;</li>
 *   <li>the scheduler calls the same method, so the tested code and the production
 *       code are not two variants of the same idea;</li>
 *   <li>the trigger itself is switched off in the test profile by
 *       {@code wiki4ai.work-items.scheduling-enabled=false}, which removes
 *       {@code @EnableScheduling} from the test contexts (see
 *       {@link com.wiki4ai.config.SchedulingConfig}). No background thread can
 *       move a row between two assertions — the flakiness that a live scheduler in
 *       a shared-database suite would cause. The operation stays fully available
 *       and fully tested; only the timer is off.</li>
 * </ul>
 *
 * <h2>Why the transaction lives in the service</h2>
 * The reaper holds no {@code @Transactional} of its own. Both of its statements are
 * bulk JPQL updates, which need a transaction, and a self-call inside one bean
 * does not go through the proxy that would provide it. Delegating to
 * {@link WorkItemService} — a different bean, always proxied — guarantees the
 * transaction regardless of whether this component itself ends up proxied.
 *
 * <h2>Atomicity</h2>
 * Both delegated statements are single conditional UPDATEs whose WHERE clause is
 * the candidate test. A row stops matching it the moment it is written, so two
 * backend instances sweeping at the same time cannot both release the same item:
 * one updates 1 row, the other 0. There is no read-modify-write anywhere in this
 * class.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkItemLeaseReaper {

    private final WorkItemService workItemService;

    /**
     * Run one sweep. Deterministic entry point — this is what a test calls.
     *
     * @return how many rows the sweep changed in total (expired leases released
     *         plus stale claims cleaned)
     */
    public int releaseExpired() {
        int released = workItemService.releaseExpiredLeases();
        int cleaned = workItemService.clearStaleClaims();
        return released + cleaned;
    }

    /**
     * The scheduled trigger. Default period 60 s, configurable with
     * {@code wiki4ai.work-items.lease-sweep-millis}.
     * <p>
     * A sweep failure must not take the scheduler down with it: an unreachable
     * database on one tick has to look like one missed tick, not like a dead
     * reaper. The exception is logged and swallowed for that reason — the next
     * tick retries, and the statements are idempotent.
     */
    @Scheduled(fixedDelayString = "${wiki4ai.work-items.lease-sweep-millis:60000}")
    public void sweepExpiredLeases() {
        try {
            int changed = releaseExpired();
            if (changed > 0) {
                log.info("Work item lease sweep released {} work item(s)", changed);
            }
        } catch (RuntimeException e) {
            log.error("Work item lease sweep failed; it will run again on the next tick: {}",
                    e.getMessage(), e);
        }
    }
}
