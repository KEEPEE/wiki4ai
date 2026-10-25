package com.wiki4ai.service;

import com.wiki4ai.dto.ActorMetricsDTO;
import com.wiki4ai.dto.ActorMetricsResponseDTO;
import com.wiki4ai.exception.BadRequestException;
import com.wiki4ai.model.Role;
import com.wiki4ai.model.User;
import com.wiki4ai.model.WorkItem;
import com.wiki4ai.model.WorkItemEvent;
import com.wiki4ai.repository.UserRepository;
import com.wiki4ai.repository.WorkItemEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Derives the actor metrics of WIKI4AI-147 from {@code work_item_events} and from
 * nothing else.
 *
 * <h2>The one rule of this class</h2>
 * It has no {@code WorkItemRepository}, and it must never get one. Every number it
 * reports is a distance or a count between rows of the audit trail:
 * <ul>
 *   <li><b>time in a state</b> is the gap between the event that moved an item into
 *   that state and the next event of the same item;</li>
 *   <li><b>cycle time</b> is the gap between the item's {@code created} event and a
 *   later event that moved it to {@code done};</li>
 *   <li><b>blocked</b>, <b>lease_expired</b>, <b>completed</b> are counts of events.</li>
 * </ul>
 * Reading the current state of a work item would give a different — and unfalsifiable
 * — answer: it would report what the row says now, not what the trail recorded. The
 * acceptance test for this story changes a {@code work_items} row behind the trail's
 * back and asserts the metrics do not move.
 * <p>
 * <b>The one exception, stated plainly (WIKI4AI-173):</b> a window narrowed to a
 * project has to know which items belong to that project, and only {@code work_items}
 * knows that. It is resolved as a list of ids handed to the event read as values —
 * {@code WorkItemService.workItemIdsOfProject} produces them, and
 * {@code WorkItemEventRepository} filters on them with a derived {@code IN} — so it
 * selects <i>which events are considered</i> and contributes no number. Every duration
 * and every count is still computed from the trail exactly as above, and this class
 * still holds no repository but the event one. The ids are resolved outside the query
 * because that repository is guarded — by two existing tests — against declaring
 * {@code @Query} at all: on the comment and event tables a hand-written query is where
 * a {@code LIKE} on the payload TEXT would appear.
 *
 * <h2>Who owns a period</h2>
 * A period in a state belongs to the actor named by the event that <i>entered</i>
 * that state. So the time an item spent {@code claimed} is charged to the actor that
 * claimed it, and when the reaper takes the item back, the {@code ready} period that
 * follows is charged to the same actor — whose id the {@code lease_expired} event
 * carries — because that is the actor whose lease was lost. An event with no actor
 * aggregates under a null key rather than disappearing.
 *
 * <h2>Open periods</h2>
 * The last state of an item has no closing event. Its period is closed at the end of
 * the requested window, or at the present moment when the window extends into the
 * future. An open period is therefore a measured duration, not an estimate — but it
 * is measured against the clock, and the response states the window it was measured
 * against.
 *
 * <h2>Backfilled events are counted, and they are visible</h2>
 * Migration V21 wrote a {@code created} event (and, where the item was no longer in
 * {@code backlog}, one {@code status_changed}) for every work item that existed
 * before the audit trail was introduced, stamped with the item's own
 * {@code created_at} / {@code updated_at} and marked {@code {"backfilled":true}}.
 * They are included in the metrics: their timestamps are the real ones, and without
 * them the acceptance criterion — non-zero numbers for the data already on dev —
 * could not be met at all, because those items have no other events. They are also
 * reported separately as {@code backfilledEventCount}, so a reader can see how much
 * of a number is recorded history and how much is reconstruction. No claim, lease or
 * expiry is backfilled, because nothing about them is known.
 *
 * <h2>Who may see what</h2>
 * Per-actor numbers are a performance record of named agents and named people, so
 * the default is the narrow one: an authenticated caller sees <b>their own</b>
 * actor's metrics, and only an ADMIN user sees everybody's. A caller whose
 * credential names no actor sees nothing and is told so (403) rather than being
 * handed an empty-but-successful answer that looks like "nobody did anything".
 * Widening this — project owners seeing their project's actors, for instance — is a
 * product decision this class deliberately does not make on its own.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ActorMetricsService {

    /**
     * Lower bound used when the caller gives no {@code from}. A fixed epoch rather
     * than {@link LocalDateTime#MIN}: the column is a TIMESTAMP without a time zone
     * and a bound the database cannot represent is a 500, not a "everything".
     */
    static final LocalDateTime DEFAULT_FROM = LocalDateTime.of(1970, 1, 1, 0, 0, 0);

    private static final String PAYLOAD_MARKER_BACKFILLED = "\"backfilled\":true";

    private final WorkItemEventRepository workItemEventRepository;
    private final UserRepository userRepository;
    private final RequestActorResolver requestActorResolver;

    /**
     * Not a source of numbers — the one collaborator that answers "is there such a
     * project, and may this caller see it" (WIKI4AI-173). It is {@code ProjectService}
     * and not a repository on purpose: the visibility rule (foreign private project =
     * 404) already lives there, and SPEC rule 7 says ownership rules are enforced in
     * the service that owns them, not re-derived by the caller.
     */
    private final ProjectService projectService;

    /**
     * Also not a source of numbers (WIKI4AI-173). It answers one question — "which work
     * items belong to this project" — so that the event read can filter on those ids as
     * values.
     * <p>
     * It is here rather than a {@code @Query} subquery inside
     * {@code WorkItemEventRepository} because that repository is forbidden by name from
     * declaring {@code @Query} at all: {@code ActorInboxIntegrationTest.Proofs} and
     * {@code ActorInboxCursorIntegrationTest.NoOffset} guard the rule that a hand-written
     * query on the comment and event tables is where a {@code LIKE} on the payload TEXT
     * would appear. The membership is therefore resolved elsewhere and passed in as an
     * {@code IN} list of ids, and the payload text stays unpatterned.
     * <p>
     * This contributes no metric. Every number this class produces is still computed
     * from {@code work_item_events} rows and from nothing else — narrowing decides which
     * events are considered, never what an event means.
     */
    private final WorkItemService workItemService;

    /**
     * Metrics of the window {@code [from, to]} for the actors this caller may see.
     *
     * @param from     inclusive lower bound, or null for "no lower bound"
     * @param to       inclusive upper bound, or null for "now"
     * @param username authenticated principal ("anonymous" resolves to nobody)
     */
    public ActorMetricsResponseDTO actorMetrics(LocalDateTime from, LocalDateTime to, String username) {
        return actorMetrics(from, to, null, null, username);
    }

    /**
     * Metrics of the window {@code [from, to]}, optionally narrowed to one project
     * (WIKI4AI-173).
     *
     * <h2>Why a project that does not exist is 404 and not 200 with zeros</h2>
     * Metrics are the only evidence an agent acts on. {@code 200} with empty actors
     * asserts "this project exists and nobody did anything in it"; {@code 404} asserts
     * "your question has no referent". A caller holding a wrong project id gets its
     * wrong assumption <i>confirmed</i> by the first answer, which is the silent
     * failure this layer exists to prevent. The check is therefore made before a
     * single event is read, through the same visibility rule every other endpoint of
     * this project uses: unknown project → 404, someone else's private project → 404
     * (existence not revealed), public project → readable.
     *
     * <h2>What narrowing does, and what it does not do</h2>
     * Narrowing changes <b>which events are considered</b>. It changes no rule about
     * who may see which actors: an ADMIN still sees every actor of the window, a
     * non-ADMIN still sees only their own, and a credential that names no actor is
     * still 403. Without {@code projectId} this method is the method WIKI4AI-147
     * shipped, unchanged.
     *
     * @param from        inclusive lower bound, or null for "no lower bound"
     * @param to          inclusive upper bound, or null for "now"
     * @param scope       requested scope: {@code all}, {@code self} or {@code project};
     *                    null or blank means "whatever this caller is entitled to",
     *                    which is the pre-WIKI4AI-173 behaviour
     * @param projectIdRaw the project to narrow to, as the caller spelled it; parsed
     *                     here so a typo is a 400 naming {@code projectId} rather than
     *                     a type-mismatch on a parameter the caller never sees typed
     * @param username    authenticated principal ("anonymous" resolves to nobody)
     * @throws BadRequestException       bad window, bad {@code projectId}, bad
     *                                   {@code scope}, or {@code scope=project} with no
     *                                   {@code projectId} (400)
     * @throws jakarta.persistence.EntityNotFoundException
     *                                   no such project, or a private project the
     *                                   caller may not see (404)
     * @throws AccessDeniedException     a caller with no actor and no ADMIN account (403)
     */
    public ActorMetricsResponseDTO actorMetrics(LocalDateTime from, LocalDateTime to, String scope,
                                                String projectIdRaw, String username) {
        LocalDateTime windowFrom = (from == null) ? DEFAULT_FROM : from;
        LocalDateTime windowTo = (to == null) ? LocalDateTime.now() : to;
        if (windowTo.isBefore(windowFrom)) {
            throw new BadRequestException(
                    "Parameter 'to' must not be before parameter 'from'");
        }

        Long projectId = parseProjectId(projectIdRaw);
        String requestedScope = normalizeScope(scope, projectId);

        User user = resolveUser(username);
        Long ownActorId = requestActorResolver.currentAttributionActorId(username);
        boolean seesAll = user != null && user.getRole() == Role.ADMIN;
        if (!seesAll && ownActorId == null) {
            throw new AccessDeniedException(
                    "Actor metrics are per-actor performance data. You may read your own actor's"
                            + " metrics; this credential names no actor, and reading other actors'"
                            + " metrics requires an ADMIN account.");
        }

        // WIKI4AI-173: answered before the trail is read, so an answer of "nothing
        // happened" can only ever mean "nothing happened".
        List<Long> projectItemIds = null;
        if (projectId != null) {
            projectService.getProjectById(projectId, username);
            projectItemIds = workItemService.workItemIdsOfProject(projectId);
        }

        // An empty id list is a real answer and is read as one: a project with no items
        // has no events, so the window is empty without asking the database — and an
        // empty IN list is exactly the case a database renders inconsistently, which is a
        // reason not to depend on it. Either way the response is 200 with empty actors,
        // never a 404: the project exists, nothing happened in it.
        List<WorkItemEvent> events;
        if (projectId == null) {
            events = workItemEventRepository
                    .findByCreatedAtBetweenOrderByWorkItemIdAscCreatedAtAscIdAsc(windowFrom, windowTo);
        } else if (projectItemIds.isEmpty()) {
            events = List.of();
        } else {
            events = workItemEventRepository
                    .findByWorkItemIdInAndCreatedAtBetweenOrderByWorkItemIdAscCreatedAtAscIdAsc(
                            projectItemIds, windowFrom, windowTo);
        }

        Map<Long, Accumulator> byActor = new LinkedHashMap<>();
        long backfilled = 0;

        // The query returned the events grouped by item and oldest first, so the
        // item boundaries can be found by walking the list once.
        List<WorkItemEvent> item = new ArrayList<>();
        Long currentItem = null;
        for (WorkItemEvent event : events) {
            if (currentItem != null && !currentItem.equals(event.getWorkItemId())) {
                accumulateItem(item, windowTo, byActor);
                item.clear();
            }
            currentItem = event.getWorkItemId();
            item.add(event);
            if (isBackfilled(event)) {
                backfilled++;
            }
        }
        if (!item.isEmpty()) {
            accumulateItem(item, windowTo, byActor);
        }

        List<ActorMetricsDTO> actors = new ArrayList<>();
        for (Map.Entry<Long, Accumulator> entry : byActor.entrySet()) {
            Long actorId = entry.getKey();
            if (!seesAll && !ownActorId.equals(actorId)) {
                continue;
            }
            actors.add(entry.getValue().toDTO(actorId));
        }

        // The scope a caller is ANSWERED with is what this caller is entitled to, not
        // what it asked for: `scope=all` from a non-ADMIN is still its own numbers,
        // exactly as before WIKI4AI-173. `project` is the one scope a request can add,
        // and only when a project was named.
        String effectiveScope = (projectId != null)
                ? ActorMetricsResponseDTO.SCOPE_PROJECT
                : (seesAll ? ActorMetricsResponseDTO.SCOPE_ALL : ActorMetricsResponseDTO.SCOPE_SELF);

        ActorMetricsResponseDTO response = ActorMetricsResponseDTO.builder()
                .from(windowFrom)
                .to(windowTo)
                .scope(effectiveScope)
                .projectId(projectId)
                .actors(actors)
                .summary(summarise(actors, backfilled))
                .build();
        log.debug("Actor metrics over [{}, {}]: scope={} projectId={} requested={} events={} actors={}",
                windowFrom, windowTo, effectiveScope, projectId, requestedScope,
                events.size(), actors.size());
        return response;
    }

    /**
     * Parse {@code projectId} (WIKI4AI-173).
     * <p>
     * Parsed by hand for the same reason {@code MetricsController.parseBoundary} parses
     * the window by hand: declared as a {@code Long} on the controller, a typo becomes a
     * converter failure on a parameter whose type the caller cannot see. Parsed here it
     * is a 400 that names {@code projectId} and says what it expected. Absent or blank
     * means no narrowing — the pre-WIKI4AI-173 shape of this read.
     */
    private Long parseProjectId(String projectIdRaw) {
        if (projectIdRaw == null || projectIdRaw.isBlank()) {
            return null;
        }
        String text = projectIdRaw.trim();
        try {
            long value = Long.parseLong(text);
            if (value < 1) {
                throw new NumberFormatException("not a positive id");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new BadRequestException("Parameter 'projectId' must be a numeric project id;"
                    + " got '" + projectIdRaw + "'");
        }
    }

    /**
     * Validate the requested {@code scope} (WIKI4AI-173).
     * <p>
     * An unknown value is 400 rather than ignored: a caller that asked for
     * {@code scope=projecet} asked for something this API does not have, and answering
     * it with a different scope is the same silent lie this story is about. Absent or
     * blank is not a request at all — it is the default, and the default is unchanged.
     *
     * <p>
     * {@code scope=project} without {@code projectId} is 400 for the same reason: the
     * request states a narrowing the request does not name. Silently answering it
     * instance-wide would report more than was asked for.
     *
     * @return the normalized requested scope, or null when none was requested
     */
    private String normalizeScope(String scope, Long projectId) {
        if (scope == null || scope.isBlank()) {
            return null;
        }
        String value = scope.trim().toLowerCase(java.util.Locale.ROOT);
        if (!ActorMetricsResponseDTO.SCOPE_ALL.equals(value)
                && !ActorMetricsResponseDTO.SCOPE_SELF.equals(value)
                && !ActorMetricsResponseDTO.SCOPE_PROJECT.equals(value)) {
            throw new BadRequestException("Parameter 'scope' must be one of 'all', 'self', 'project';"
                    + " got '" + scope + "'");
        }
        if (ActorMetricsResponseDTO.SCOPE_PROJECT.equals(value) && projectId == null) {
            throw new BadRequestException("Parameter 'scope' = 'project' requires parameter 'projectId':"
                    + " a project-narrowed window has to name the project it is narrowed to");
        }
        return value;
    }

    // ── Derivation ────────────────────────────────────────────────────────────

    /**
     * Turn one item's chronological event list into durations and counts.
     * <p>
     * Two cursors run over the same list: an <b>open period</b> (the state the item
     * is currently in, when it entered it and who is responsible for it), which is
     * closed by the next event of the item; and the item's <b>creation moment</b>,
     * which every later transition into {@code done} turns into a cycle.
     */
    private void accumulateItem(List<WorkItemEvent> timeline, LocalDateTime windowTo,
                                Map<Long, Accumulator> byActor) {
        LocalDateTime created = null;
        String openStatus = null;
        Long openActor = null;
        LocalDateTime openFrom = null;

        for (WorkItemEvent event : timeline) {
            LocalDateTime at = event.getCreatedAt();

            // Close the state the item was in before this event happened.
            if (openStatus != null && at != null && !at.isBefore(openFrom)) {
                byActor.computeIfAbsent(openActor, k -> new Accumulator())
                        .addStatus(openStatus, secondsBetween(openFrom, at));
            }

            countEvent(byActor, event);

            if (WorkItemEvent.EVENT_CREATED.equals(event.getEvent()) && created == null) {
                created = at;
            }

            // Open the state this event moved the item into, if it moved it at all.
            if (event.getToStatus() != null) {
                openStatus = event.getToStatus();
                openActor = event.getActorId();
                openFrom = at;
            } else {
                // 'deleted' has no successor state: nothing is open after it.
                openStatus = null;
            }
        }

        // The item's last state is still open. It is measured to the end of the
        // window, never beyond the present moment.
        if (openStatus != null) {
            LocalDateTime close = windowTo.isAfter(LocalDateTime.now()) ? LocalDateTime.now() : windowTo;
            if (!close.isBefore(openFrom)) {
                byActor.computeIfAbsent(openActor, k -> new Accumulator())
                        .addStatus(openStatus, secondsBetween(openFrom, close));
            }
        }

        // Cycle time: created -> done, attributed to whoever moved it to done.
        if (created != null) {
            for (WorkItemEvent event : timeline) {
                if (WorkItem.STATUS_DONE.equals(event.getToStatus())
                        && event.getCreatedAt() != null
                        && !event.getCreatedAt().isBefore(created)) {
                    byActor.computeIfAbsent(event.getActorId(), k -> new Accumulator())
                            .addCycle(secondsBetween(created, event.getCreatedAt()));
                }
            }
        }
    }

    private void countEvent(Map<Long, Accumulator> byActor, WorkItemEvent event) {
        Accumulator accumulator = byActor.computeIfAbsent(event.getActorId(), k -> new Accumulator());
        accumulator.eventCount++;
        String name = event.getEvent();
        if (WorkItemEvent.EVENT_CREATED.equals(name)) {
            accumulator.createdCount++;
        } else if (WorkItemEvent.EVENT_CLAIMED.equals(name)) {
            accumulator.claimedCount++;
        } else if (WorkItemEvent.EVENT_LEASE_EXTENDED.equals(name)) {
            accumulator.leaseExtendedCount++;
        } else if (WorkItemEvent.EVENT_LEASE_EXPIRED.equals(name)) {
            accumulator.leaseExpiredCount++;
        }
        if (WorkItem.STATUS_BLOCKED.equals(event.getToStatus())) {
            accumulator.blockedCount++;
        }
        if (WorkItem.STATUS_DONE.equals(event.getToStatus())) {
            accumulator.completedCount++;
        }
    }

    private static double secondsBetween(LocalDateTime from, LocalDateTime to) {
        double seconds = Duration.between(from, to).toNanos() / 1_000_000_000.0;
        return seconds < 0 ? 0.0 : seconds;
    }

    private static boolean isBackfilled(WorkItemEvent event) {
        return event.getPayload() != null && event.getPayload().contains(PAYLOAD_MARKER_BACKFILLED);
    }

    private ActorMetricsResponseDTO.Summary summarise(List<ActorMetricsDTO> actors, long backfilled) {
        ActorMetricsResponseDTO.Summary summary = ActorMetricsResponseDTO.Summary.builder().build();
        Map<String, Double> statusSeconds = new LinkedHashMap<>();
        for (ActorMetricsDTO actor : actors) {
            summary.setEventCount(summary.getEventCount() + actor.getEventCount());
            summary.setCreatedCount(summary.getCreatedCount() + actor.getCreatedCount());
            summary.setClaimedCount(summary.getClaimedCount() + actor.getClaimedCount());
            summary.setCompletedCount(summary.getCompletedCount() + actor.getCompletedCount());
            summary.setBlockedCount(summary.getBlockedCount() + actor.getBlockedCount());
            summary.setLeaseExpiredCount(summary.getLeaseExpiredCount() + actor.getLeaseExpiredCount());
            summary.setLeaseExtendedCount(summary.getLeaseExtendedCount() + actor.getLeaseExtendedCount());
            summary.setCycleCount(summary.getCycleCount() + actor.getCycleCount());
            summary.setTotalCycleSeconds(summary.getTotalCycleSeconds() + actor.getTotalCycleSeconds());
            summary.setTotalStatusSeconds(summary.getTotalStatusSeconds() + actor.getTotalStatusSeconds());
            for (Map.Entry<String, Double> entry : actor.getStatusSeconds().entrySet()) {
                statusSeconds.merge(entry.getKey(), entry.getValue(), Double::sum);
            }
        }
        summary.setStatusSeconds(statusSeconds);
        summary.setBackfilledEventCount(backfilled);
        summary.setActorCount(actors.size());
        summary.setAverageCycleSeconds(summary.getCycleCount() == 0
                ? 0.0
                : summary.getTotalCycleSeconds() / summary.getCycleCount());
        return summary;
    }

    private User resolveUser(String username) {
        if (username == null || username.isBlank()
                || "anonymous".equals(username) || "anonymousUser".equals(username)) {
            return null;
        }
        return userRepository.findByUsername(username).orElse(null);
    }

    /** Mutable running totals of one actor while the window is walked. */
    private static final class Accumulator {
        private long eventCount;
        private long createdCount;
        private long claimedCount;
        private long completedCount;
        private long blockedCount;
        private long leaseExpiredCount;
        private long leaseExtendedCount;
        private long cycleCount;
        private double totalCycleSeconds;
        private double totalStatusSeconds;
        private final Map<String, Double> statusSeconds = new LinkedHashMap<>();

        void addStatus(String status, double seconds) {
            statusSeconds.merge(status, seconds, Double::sum);
            totalStatusSeconds += seconds;
        }

        void addCycle(double seconds) {
            cycleCount++;
            totalCycleSeconds += seconds;
        }

        ActorMetricsDTO toDTO(Long actorId) {
            return ActorMetricsDTO.builder()
                    .actorId(actorId)
                    .eventCount(eventCount)
                    .createdCount(createdCount)
                    .claimedCount(claimedCount)
                    .completedCount(completedCount)
                    .blockedCount(blockedCount)
                    .leaseExpiredCount(leaseExpiredCount)
                    .leaseExtendedCount(leaseExtendedCount)
                    .statusSeconds(statusSeconds)
                    .totalStatusSeconds(totalStatusSeconds)
                    .cycleCount(cycleCount)
                    .totalCycleSeconds(totalCycleSeconds)
                    .averageCycleSeconds(cycleCount == 0 ? 0.0 : totalCycleSeconds / cycleCount)
                    .build();
        }
    }

    /** Kept so the set of states a metric can name is stated in one place. */
    static final Set<String> KNOWN_STATUSES = Set.of(
            WorkItem.STATUS_BACKLOG, WorkItem.STATUS_READY, WorkItem.STATUS_CLAIMED,
            WorkItem.STATUS_RUNNING, WorkItem.STATUS_REVIEW, WorkItem.STATUS_DONE,
            WorkItem.STATUS_BLOCKED, WorkItem.STATUS_CANCELLED);
}
