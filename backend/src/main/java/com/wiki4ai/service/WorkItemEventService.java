package com.wiki4ai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wiki4ai.dto.WorkItemEventDTO;
import com.wiki4ai.model.WorkItemEvent;
import com.wiki4ai.repository.WorkItemEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The single write door of the append-only audit trail (WIKI4AI-145) and the
 * reader behind the timeline endpoint (WIKI4AI-146).
 *
 * <h2>The event vocabulary</h2>
 * Every value the {@code event} column can hold, and what each one means:
 * <table border="1">
 *   <caption>work_item_events.event</caption>
 *   <tr><th>event</th><th>caused by</th><th>from_status</th><th>to_status</th><th>actor_id</th></tr>
 *   <tr><td>{@code created}</td><td>POST /work-items</td><td>null</td><td>initial state</td><td>creating actor</td></tr>
 *   <tr><td>{@code status_changed}</td><td>a legal transition through PUT /work-items/{id}</td><td>state before</td><td>state after</td><td>acting actor</td></tr>
 *   <tr><td>{@code claimed}</td><td>the atomic claim (POST /{id}/claim) winning its race</td><td>{@code ready}</td><td>{@code claimed}</td><td>claimant</td></tr>
 *   <tr><td>{@code claim_released}</td><td>a transition that dropped a live claim triple (to {@code ready}, {@code blocked} or {@code cancelled})</td><td>state before</td><td>state after</td><td>acting actor</td></tr>
 *   <tr><td>{@code lease_extended}</td><td>POST /{id}/lease by the holder</td><td>current state</td><td>current state</td><td>holder</td></tr>
 *   <tr><td>{@code lease_expired}</td><td>the lease reaper releasing a lease whose TTL passed</td><td>{@code claimed} or {@code running}</td><td>{@code ready}</td><td>the actor that held the lease</td></tr>
 *   <tr><td>{@code stale_claim_cleared}</td><td>the heal sweep removing a claim triple from a claim-free state</td><td>current state</td><td>current state</td><td>the actor whose claim was cleared</td></tr>
 *   <tr><td>{@code deleted}</td><td>DELETE /work-items/{id}</td><td>state at deletion</td><td>null</td><td>acting actor</td></tr>
 * </table>
 *
 * <h2>Automatic events and their actor</h2>
 * {@code lease_expired} and {@code stale_claim_cleared} are produced by a timer, not
 * by a request, so there is no credential to attribute them to. They carry the id of
 * the actor the change happened <i>to</i> — the one that lost the lease or the stale
 * claim. That is what AC of WIKI4AI-146 ("timeline obsahuje aj lease_expired") and
 * AC of WIKI4AI-147 ("pocet lease_expired per actor") need, and it is the only
 * actor the sweep can honestly name. When the row carried no assignee at all, the
 * column stays null: null means "nobody held it", not "unknown".
 *
 * <h2>Written in the caller's transaction, never in one of its own</h2>
 * {@link #record} is {@link Propagation#MANDATORY}. It refuses to run outside a
 * transaction instead of starting one. That is the enforcement of AC of WIKI4AI-145
 * ("zapis v rovnakej transakcii ako zmena stavu"): an audit row that could commit on
 * its own is an audit row that can exist for a state change that was rolled back.
 * With MANDATORY the event and the change share one commit — or neither exists.
 *
 * <h2>What is deliberately not recorded</h2>
 * A rejected request records nothing, because recording happens after the operation
 * succeeded and a rejected operation never gets that far; the transaction that threw
 * is rolled back, so even a write made before the failure disappears with it. An
 * update that changes only the title, the body or the labels records nothing either:
 * this table is the timeline of an item's <i>state</i>, and a rename is not a state
 * change.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorkItemEventService {

    /** Hard ceiling of one timeline page, same shape as the documents endpoint. */
    public static final int MAX_PAGE_SIZE = 100;

    /** Default page size, same shape as the documents endpoint. */
    public static final int DEFAULT_PAGE_SIZE = 50;

    private final WorkItemEventRepository workItemEventRepository;
    private final ObjectMapper objectMapper;

    // ── Write: the only door in the application ───────────────────────────────

    /**
     * Append one event.
     * <p>
     * This is the only method in the application that writes to
     * {@code work_item_events}, and it only ever appends: it calls
     * {@code repository.save} on an entity that carries no id, and the repository
     * has no update or delete operation to reach for.
     *
     * @param workItemId the item the event belongs to
     * @param actorId    the actor to attribute it to, or null (automatic change, or
     *                   a caller that carries no actor)
     * @param event      one of the {@link WorkItemEvent} {@code EVENT_*} constants
     * @param fromStatus state before, or null
     * @param toStatus   state after, or null
     * @param payload    optional JSON detail as a string, or null
     * @return the stored event, with its id and its {@code created_at}
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public WorkItemEvent record(Long workItemId, Long actorId, String event,
                                String fromStatus, String toStatus, String payload) {
        if (workItemId == null) {
            throw new IllegalArgumentException("An event must belong to a work item");
        }
        WorkItemEvent saved = workItemEventRepository.save(WorkItemEvent.builder()
                .workItemId(workItemId)
                .actorId(actorId)
                .event(event)
                .fromStatus(fromStatus)
                .toStatus(toStatus)
                .payload(payload)
                .build());
        log.debug("work_item_events: {} item={} actor={} {} -> {}",
                event, workItemId, actorId, fromStatus, toStatus);
        return saved;
    }

    /**
     * Append one event with its payload given as fields instead of a string.
     * <p>
     * The map is serialised with the application's own {@link ObjectMapper}, so the
     * escaping is the escaping that JSON needs and no value can smuggle itself out
     * of the string it ends up in. A payload that cannot be serialised is stored as
     * null rather than failing the state change it describes: the change already
     * happened, and losing an optional detail must not roll back a lease or a
     * transition.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public WorkItemEvent record(Long workItemId, Long actorId, String event,
                                String fromStatus, String toStatus, Map<String, Object> payload) {
        return record(workItemId, actorId, event, fromStatus, toStatus, toJson(payload));
    }

    // ── Reads ─────────────────────────────────────────────────────────────────

    /**
     * The timeline of one work item, oldest first, paginated (AC of WIKI4AI-146).
     * <p>
     * The sort is {@code createdAt ASC, id ASC} and it is stated here rather than
     * left to the caller's {@code Pageable}: a timeline that is not fully ordered is
     * not a timeline. {@code id} breaks the ties a microsecond-resolution timestamp
     * inevitably produces.
     * <p>
     * Access is not checked here — {@code WorkItemService#requireEventTimelineAccess}
     * is the single source of this project's work item visibility rules and the
     * controller calls it before this method.
     *
     * @param workItemId the item whose timeline is read
     * @param page       0-indexed page number
     * @param size       page size, clamped to 1..{@value #MAX_PAGE_SIZE}
     */
    public Page<WorkItemEventDTO> listEvents(Long workItemId, int page, int size) {
        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int pageNumber = Math.max(page, 0);
        // No Sort in the Pageable on purpose: the repository method already carries
        // the ordering the timeline needs (createdAt ASC, id ASC) and putting it in
        // both places would only make the generated ORDER BY harder to read.
        Pageable pageable = PageRequest.of(pageNumber, pageSize);
        return workItemEventRepository
                .findByWorkItemIdOrderByCreatedAtAscIdAsc(workItemId, pageable)
                .map(this::convertToDTO);
    }

    /** Every event of one item, oldest first. Test and diagnostics helper. */
    public List<WorkItemEventDTO> listAllEvents(Long workItemId) {
        return workItemEventRepository.findByWorkItemIdOrderByCreatedAtAscIdAsc(workItemId).stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    /** Events of every item inside a time window, grouped by item, oldest first. */
    public List<WorkItemEvent> findEventsInWindow(LocalDateTime from, LocalDateTime to) {
        return workItemEventRepository
                .findByCreatedAtBetweenOrderByWorkItemIdAscCreatedAtAscIdAsc(from, to);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String toJson(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.warn("Could not serialise the payload of a work item event; storing it without one: {}",
                    e.getMessage());
            return null;
        }
    }

    private WorkItemEventDTO convertToDTO(WorkItemEvent event) {
        return WorkItemEventDTO.builder()
                .id(event.getId())
                .workItemId(event.getWorkItemId())
                .actorId(event.getActorId())
                .event(event.getEvent())
                .fromStatus(event.getFromStatus())
                .toStatus(event.getToStatus())
                .payload(event.getPayload())
                .createdAt(event.getCreatedAt())
                .build();
    }
}
