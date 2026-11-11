package com.wiki4ai.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The timer behind the embedding queue (WIKI4AI-197 commit 17).
 *
 * <p>Same shape as {@link ReservationReaper} and {@link WorkItemLeaseReaper}: a thin
 * component whose only job is to call one method on a schedule, so the operation itself
 * stays directly callable by a test and the trigger can be asserted without waiting for
 * a clock.</p>
 *
 * <h2>Why the queue needs a timer at all</h2>
 * The after-commit listener is the fast path and it is not reliable on its own. A request
 * survives the commit but not the process, if the JVM dies between the two; the executor
 * can refuse a task during shutdown; an attempt can fail and schedule its next try into
 * the future. In every one of those cases the row is still PENDING and nothing else in the
 * application would ever look at it again. This timer is what turns "asynchronous" into
 * "durable": the work is a row until it is done, and someone keeps asking.
 *
 * <h2>Why it is inert in the tests</h2>
 * {@code @EnableScheduling} is switched off in the test profile
 * ({@code wiki4ai.work-items.scheduling-enabled=false}, see {@code SchedulingConfig}) for
 * the reason spelled out there: the suite shares one in-memory database and a background
 * thread writing to it moves rows between other tests' assertions. The operation is not
 * disabled — {@code EmbeddingQueueServiceTest} calls {@code drainDueEntries()} directly,
 * on the test thread — only the timer is.
 */
@Component
@RequiredArgsConstructor
public class EmbeddingQueueDrainer {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingQueueDrainer.class);

    private final EmbeddingQueueService embeddingQueueService;

    /**
     * Drain the queued embeddings that are due. Default period 30 s, configurable with
     * {@code wiki4ai.embedding.drain-interval-ms}.
     *
     * <p>Thirty seconds is deliberately shorter than the retry backoff (30 s base,
     * doubling): the timer is not the backoff, it is only how often the queue is looked
     * at. A row that is not due yet is skipped by the query, so a short period costs one
     * indexed read and nothing else.</p>
     *
     * <p>{@code fixedDelay} rather than {@code fixedRate}: a drain that outlives its
     * period — a slow sidecar working through a batch — must not stack a second drain on
     * top of it. And a failed tick has to look like one missed tick, not a dead drainer,
     * so the exception is logged and swallowed; the next tick tries again and every row it
     * touches is idempotent.</p>
     */
    @Scheduled(fixedDelayString = "${wiki4ai.embedding.drain-interval-ms:30000}")
    public void drainDueEntries() {
        try {
            int embedded = embeddingQueueService.drainDueEntries();
            if (embedded > 0) {
                log.info("Embedding queue drained {} document(s)", embedded);
            }
        } catch (RuntimeException e) {
            log.error("Embedding queue drain failed; it will run again on the next tick: {}",
                    e.getMessage(), e);
        }
    }
}
