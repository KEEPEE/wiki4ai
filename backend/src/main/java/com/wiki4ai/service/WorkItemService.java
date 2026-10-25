package com.wiki4ai.service;

import com.wiki4ai.dto.WorkItemClaimDTO;
import com.wiki4ai.dto.WorkItemDTO;
import com.wiki4ai.dto.WorkItemRequestDTO;
import com.wiki4ai.exception.BadRequestException;
import com.wiki4ai.model.Project;
import com.wiki4ai.model.ProjectActor;
import com.wiki4ai.model.Role;
import com.wiki4ai.model.User;
import com.wiki4ai.model.WorkItem;
import com.wiki4ai.model.WorkItemComment;
import com.wiki4ai.model.WorkItemEvent;
import com.wiki4ai.repository.ProjectRepository;
import com.wiki4ai.repository.UserRepository;
import com.wiki4ai.repository.WorkItemCommentRepository;
import com.wiki4ai.repository.WorkItemRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Service layer for work items (WIKI4AI-140) and for the atomic claim
 * (WIKI4AI-141).
 *
 * <h2>Ownership (SPEC rule 7 — enforced here, never in SecurityConfig)</h2>
 * A work item inherits its visibility from its project:
 * <ul>
 *   <li>someone else's <b>private</b> project → 404 Not Found, for reads and for
 *       writes alike, so the existence of that project is not revealed;</li>
 *   <li>a <b>public</b> project is readable by anyone, but writing to it requires
 *       the right to write: the project's owner, an ADMIN user, or a caller whose
 *       actor holds at least the {@code worker} role in that project
 *       ({@link ProjectActorService#hasAtLeast}, the helper that story exposed for
 *       exactly these coordination stories). Anyone else → 403 Forbidden.</li>
 * </ul>
 *
 * <h2>State machine</h2>
 * {@code backlog → ready → claimed → running → review → done}, plus
 * {@code blocked} and {@code cancelled}. The transition into {@code claimed} is
 * not available here: it is produced only by {@link #claimWorkItem}, whose
 * conditional UPDATE is what makes two racing claims mutually exclusive. An
 * illegal transition is an {@link IllegalArgumentException}, which
 * {@code GlobalExceptionHandler} maps to 409 Conflict.
 *
 * <h2>'done' requires a result comment (WIKI4AI-150)</h2>
 * {@code review -> done} is refused with 409 — {@value #RESULT_COMMENT_REQUIRED} —
 * unless the item already carries a comment of kind {@code result}, and a create that
 * asks for {@code done} is refused for the same reason. The rule is enforced in
 * {@link #applyStatusTransition} and {@link #createWorkItem}, never in a controller,
 * and a refused transition leaves no event. See
 * {@link #requireResultCommentForDone} for the whole decision, including whose comment
 * counts.
 *
 * <h2>Attribution</h2>
 * {@code createdByActorId} / {@code updatedByActorId} come from
 * {@link RequestActorResolver}, i.e. from {@code Authentication#getDetails()} —
 * never from the body, a query parameter or a header. {@link WorkItemRequestDTO}
 * has no field an actor id could be smuggled through.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorkItemService {

    /**
     * How long a claim lease is granted: 45 minutes (AC of WIKI4AI-142 — "Lease
     * TTL default 45 min"). The previous value was 15, which is shorter than a
     * normal agent task and made the reaper steal live work.
     * <p>
     * This constant is the fallback and the documented default. The value actually
     * used is {@link #leaseMinutes}, bound to {@code wiki4ai.work-items.lease-minutes}
     * (SPEC rule: configurable, not hardcoded).
     */
    public static final int DEFAULT_LEASE_MINUTES = 45;

    /**
     * Lease duration in minutes, read from {@code wiki4ai.work-items.lease-minutes}
     * with {@value #DEFAULT_LEASE_MINUTES} as the default.
     * <p>
     * Field injection with an initializer on purpose. Spring overwrites the field
     * after construction, so the property wins in the application; a service built
     * by hand (the Mockito unit tests of this class) keeps the initializer and
     * never sees a zero lease. Constructor injection of this value would change
     * the constructor signature the existing unit tests build against.
     */
    @Value("${wiki4ai.work-items.lease-minutes:45}")
    private int leaseMinutes = DEFAULT_LEASE_MINUTES;

    /** Allowed priority values (SPEC leaves priority free-form; this pins it down). */
    private static final Set<String> PRIORITIES = Set.of("low", "medium", "high", "urgent");

    /** Every state the machine knows. */
    private static final Set<String> STATUSES = Set.of(
            WorkItem.STATUS_BACKLOG, WorkItem.STATUS_READY, WorkItem.STATUS_CLAIMED,
            WorkItem.STATUS_RUNNING, WorkItem.STATUS_REVIEW, WorkItem.STATUS_DONE,
            WorkItem.STATUS_BLOCKED, WorkItem.STATUS_CANCELLED);

    /**
     * The only two states a claim may live in (WIKI4AI-170). Everything else is
     * either not yet claimed, or work that is parked/finished and must not look
     * claim-held to the next agent reading the list.
     */
    private static final Set<String> CLAIM_HOLDING_STATUSES = Set.of(
            WorkItem.STATUS_CLAIMED, WorkItem.STATUS_RUNNING);

    /**
     * States that never hold a claim: entering any of them clears the whole claim
     * triple (assignee, token, lease). This is the fix of WIKI4AI-170 — before it,
     * only a transition <i>out of</i> {@code claimed} released the claim, so
     * {@code running -> blocked} and {@code blocked -> ready} kept a live lease on
     * an item that was back in the pool.
     */
    private static final Set<String> CLAIM_FREE_STATUSES = Set.of(
            WorkItem.STATUS_READY, WorkItem.STATUS_BLOCKED, WorkItem.STATUS_CANCELLED);

    /**
     * States that end the work: the lease and the claim token are dropped (nobody
     * may act on the item any more), but the assignee stays as the record of who
     * performed it. {@code review -> done} keeping the assignee is the behaviour
     * the existing lifecycle test asserts, and it is useful information.
     */
    private static final Set<String> LEASE_ENDING_STATUSES = Set.of(
            WorkItem.STATUS_REVIEW, WorkItem.STATUS_DONE);

    /**
     * Legal transitions. {@code claimed} is absent as a TARGET on purpose — the
     * only way into it is {@link #claimWorkItem}. Terminal: {@code done},
     * {@code cancelled}.
     */
    private static final Map<String, Set<String>> ALLOWED_TRANSITIONS = Map.of(
            WorkItem.STATUS_BACKLOG, Set.of(WorkItem.STATUS_READY, WorkItem.STATUS_BLOCKED, WorkItem.STATUS_CANCELLED),
            WorkItem.STATUS_READY, Set.of(WorkItem.STATUS_BLOCKED, WorkItem.STATUS_CANCELLED),
            WorkItem.STATUS_CLAIMED, Set.of(WorkItem.STATUS_READY, WorkItem.STATUS_RUNNING,
                    WorkItem.STATUS_BLOCKED, WorkItem.STATUS_CANCELLED),
            WorkItem.STATUS_RUNNING, Set.of(WorkItem.STATUS_REVIEW, WorkItem.STATUS_BLOCKED, WorkItem.STATUS_CANCELLED),
            WorkItem.STATUS_REVIEW, Set.of(WorkItem.STATUS_DONE, WorkItem.STATUS_RUNNING,
                    WorkItem.STATUS_BLOCKED, WorkItem.STATUS_CANCELLED),
            WorkItem.STATUS_BLOCKED, Set.of(WorkItem.STATUS_BACKLOG, WorkItem.STATUS_READY, WorkItem.STATUS_CANCELLED),
            WorkItem.STATUS_DONE, Set.of(),
            WorkItem.STATUS_CANCELLED, Set.of());

    /** Random source for claim tokens. java.base — no new dependency. */
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Machine-readable code of the WIKI4AI-150 invariant. It is the first token of
     * every message that invariant produces, so a client can branch on it instead of
     * pattern-matching prose: the AC asks for a message that says <b>what is missing
     * and how to add it</b>, and a stable code is the part a machine can read while
     * the rest of the sentence stays readable for a human.
     */
    public static final String RESULT_COMMENT_REQUIRED = "RESULT_COMMENT_REQUIRED";

    private final WorkItemRepository workItemRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final ProjectActorService projectActorService;
    private final RequestActorResolver requestActorResolver;

    /**
     * The lookup the WIKI4AI-150 invariant runs: "does this item already have a
     * comment of kind {@code result}?" ({@code existsByWorkItemIdAndKind}, backed by
     * the {@code idx_wic_item_kind} index of V22).
     * <p>
     * The <b>repository</b> is injected here rather than {@code WorkItemCommentService}
     * on purpose: {@code WorkItemCommentService} already depends on {@code WorkItemService}
     * (it calls {@code requireCommentAccess}), so injecting the service would close a
     * constructor cycle and need {@code @Lazy} or an {@code ObjectProvider} to start at
     * all. A repository is a leaf — no cycle, no lazy proxy, and the invariant cannot be
     * skipped by constructing this service some other way: the field is {@code final} and
     * constructor-injected, so there is no build of {@code WorkItemService} that has no
     * way to check the rule.
     */
    private final WorkItemCommentRepository workItemCommentRepository;

    /**
     * The append-only audit trail of WIKI4AI-145.
     * <p>
     * Field injection with {@code required = false}, exactly like
     * {@code GlobalExceptionHandler}'s {@code DocumentRepository}: the pure unit
     * tests of this class build the service by hand with Mockito and mock only the
     * collaborators a state machine needs. A service assembled that way has no
     * audit trail, and {@link #recordEvent} says so by recording nothing rather than
     * throwing a NullPointerException inside somebody else's test. In the
     * application the bean is always there — it is a {@code @Service} — so the
     * branch that skips recording is unreachable in production, and the timeline is
     * asserted against a real database in {@code WorkItemEventIntegrationTest}.
     */
    @Autowired(required = false)
    private WorkItemEventService workItemEventService;

    // ── Reads ─────────────────────────────────────────────────────────────────

    /**
     * The ids of every work item of one project (WIKI4AI-173).
     * <p>
     * This exists because {@code work_item_events} has no {@code project_id} column: an
     * event belongs to a work item, and the work item belongs to a project. A caller that
     * wants the trail of one project therefore has to resolve that indirection, and the
     * only place that owns the {@code work_items} table is this service. The ids are
     * handed back as values so the caller can filter by them as a whole-value
     * {@code IN} — which is what keeps {@code WorkItemEventRepository} free of
     * {@code @Query}, the rule WIKI4AI-154 guards.
     * <p>
     * No visibility check happens here: this is an internal read used by
     * {@code ActorMetricsService}, which has already answered the visibility question
     * through {@code ProjectService} before asking. A caller that has not done that must
     * not call this.
     */
    public List<Long> workItemIdsOfProject(Long projectId) {
        return workItemRepository.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .map(WorkItem::getId)
                .toList();
    }

    /**
     * List work items filtered by project and state (the pre-WIKI4AI-144 shape of
     * this API). Kept as the entry point for callers that use no new filter; it
     * delegates to the full filter form with {@code label} and {@code assignee}
     * unset, so it is the same rules and the same queries, not a second
     * implementation.
     */
    public List<WorkItemDTO> listWorkItems(Long projectId, String status, String username) {
        return listWorkItems(projectId, status, null, null, username);
    }

    /**
     * List work items (AC of WIKI4AI-144: {@code ?status=&label=&assignee=}).
     * <p>
     * Filters are combined with AND: a parameter that is absent or blank is not a
     * filter, a parameter that is present must match. An unknown {@code status} is
     * 400 — a typo must not silently return an empty list.
     * <p>
     * With {@code projectId} the project itself is visibility-checked (404 for a
     * foreign private project). Without it, every item is fetched and the ones
     * living in a project the caller may not see are dropped — a private project
     * never appears in anybody else's listing.
     * <p>
     * {@code status} and {@code assignee} are pushed into the SQL (indexed
     * columns, see {@code idx_work_items_project_status} and
     * {@code idx_work_items_assignee} in V20). {@code label} is matched in Java,
     * on purpose: {@code labels} is a comma-encoded TEXT column and the only
     * honest way to match one label without matching a substring of another is to
     * decode the column into tokens and compare whole tokens. See {@link #hasLabel}.
     *
     * @param projectId optional project filter
     * @param status    optional state filter; an unknown value is 400
     * @param label     optional label filter; matched against whole labels only
     * @param assignee  optional actor id filter (the actor holding the item)
     * @param username  authenticated principal ("anonymous" resolves to nobody)
     */
    public List<WorkItemDTO> listWorkItems(Long projectId, String status, String label,
                                           Long assignee, String username) {
        User user = resolveUser(username);
        String statusFilter = (status == null || status.isBlank()) ? null : normalizeStatus(status);
        String labelFilter = (label == null || label.isBlank()) ? null : label.trim();

        List<WorkItem> items;
        if (projectId != null) {
            Project project = requireVisibleProject(projectId, user);
            items = queryItems(project.getId(), statusFilter, assignee);
            // The project is already visibility-checked; no extra filtering needed.
            return labelMatches(items, labelFilter).stream()
                    .map(this::convertToDTO)
                    .collect(Collectors.toList());
        }

        items = queryItems(null, statusFilter, assignee);
        return filterVisible(labelMatches(items, labelFilter), user).stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    /** Pick the query that carries exactly the filters that were supplied. */
    private List<WorkItem> queryItems(Long projectId, String status, Long assignee) {
        if (projectId != null) {
            if (status != null) {
                return (assignee != null)
                        ? workItemRepository.findByProjectIdAndStatusAndAssigneeActorIdOrderByCreatedAtDesc(
                                projectId, status, assignee)
                        : workItemRepository.findByProjectIdAndStatusOrderByCreatedAtDesc(projectId, status);
            }
            return (assignee != null)
                    ? workItemRepository.findByProjectIdAndAssigneeActorIdOrderByCreatedAtDesc(projectId, assignee)
                    : workItemRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
        }
        if (status != null) {
            return (assignee != null)
                    ? workItemRepository.findByStatusAndAssigneeActorIdOrderByCreatedAtDesc(status, assignee)
                    : workItemRepository.findByStatusOrderByCreatedAtDesc(status);
        }
        return (assignee != null)
                ? workItemRepository.findByAssigneeActorIdOrderByCreatedAtDesc(assignee)
                : workItemRepository.findAllByOrderByCreatedAtDesc();
    }

    /**
     * Keep the items carrying {@code label} as one of their labels. A null
     * {@code labelFilter} keeps everything (no filter was asked for).
     */
    private List<WorkItem> labelMatches(List<WorkItem> items, String labelFilter) {
        if (labelFilter == null) {
            return items;
        }
        return items.stream()
                .filter(item -> hasLabel(item, labelFilter))
                .collect(Collectors.toList());
    }

    /**
     * Whole-token label match (AC of WIKI4AI-144: {@code ?label=lab} must NOT match
     * an item labelled {@code label}).
     * <p>
     * {@code labels} is TEXT encoded as {@code "a,b,c"} (see {@link #encodeLabels}),
     * so a {@code LIKE '%lab%'} would match {@code label}, {@code backlog} and
     * anything else containing those three letters. The column is decoded into its
     * tokens and compared token by token, which cannot match a substring. The
     * comparison is case-insensitive and whitespace-insensitive because the stored
     * value is already trimmed and deduplicated by {@link #encodeLabels}; matching
     * whole tokens is what prevents the substring bug, not the case rule.
     */
    private boolean hasLabel(WorkItem item, String labelFilter) {
        if (item.getLabels() == null || item.getLabels().isBlank()) {
            return false;
        }
        String wanted = labelFilter.toLowerCase(Locale.ROOT);
        for (String label : item.getLabels().split(",")) {
            if (label.trim().toLowerCase(Locale.ROOT).equals(wanted)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get one work item. A work item inside someone else's private project is
     * reported as 404 — its existence is not revealed.
     */
    public WorkItemDTO getWorkItem(Long id, String username) {
        User user = resolveUser(username);
        WorkItem item = requireItem(id);
        requireVisibleProject(item.getProjectId(), user);
        return convertToDTO(item);
    }

    // ── Writes ────────────────────────────────────────────────────────────────

    /**
     * Create a work item. The project must exist and be visible to the caller
     * (404), and the caller must have write access to it (403).
     * <p>
     * Attribution is assigned here and nowhere else: {@code createdByUserId} from
     * the authenticated user, {@code createdByActorId} from the verified
     * credential. A state of {@code claimed} cannot be created (400) — it is the
     * product of a claim, not of a create.
     */
    @Transactional
    public WorkItemDTO createWorkItem(WorkItemRequestDTO dto, String username) {
        User user = requireUser(username);
        if (dto == null) {
            throw new BadRequestException("Request body is required");
        }
        Long actorId = requestActorResolver.currentAttributionActorId(username);
        if (dto.getProjectId() == null) {
            throw new BadRequestException("Parameter 'projectId' is required");
        }
        Project project = requireVisibleProject(dto.getProjectId(), user);
        requireProjectWrite(project, user, actorId, "create work items in");

        String title = requireTitle(dto.getTitle());
        String status = normalizeCreateStatus(dto.getStatus());
        // WIKI4AI-150: the create door into 'done' is closed too. The invariant is a
        // statement about a state — "an item in 'done' has a result comment" — not only
        // about the transition that produced it, so a create that landed straight in
        // 'done' would satisfy the state while breaking the rule, and the invariant
        // would be bypassable by choosing the other door. It is also unsatisfiable by
        // construction: a comment needs an item id, and this item has none yet. The
        // legal route is stated in the message.
        if (WorkItem.STATUS_DONE.equals(status)) {
            throw new IllegalArgumentException(resultCommentRequiredOnCreate());
        }
        String priority = normalizePriority(dto.getPriority());
        String externalRef = normalizeExternalRef(dto.getExternalRef());
        validateParent(dto.getParentId(), project.getId(), null);
        requireExternalRefFree(externalRef, null);

        WorkItem item = WorkItem.builder()
                .projectId(project.getId())
                .title(title)
                .body(dto.getBody())
                .status(status)
                .priority(priority)
                .labels(encodeLabels(dto.getLabels()))
                .parentId(dto.getParentId())
                .externalRef(externalRef)
                .createdByUserId(user.getId())
                .createdByActorId(actorId)
                .build();

        WorkItem saved = saveAndTranslateConflicts(item);
        // WIKI4AI-145: the trail starts at the item's first state. There is no
        // 'created' without a state to be created in, and no state without the
        // create that produced it — so this pair is written in one transaction and
        // either both exist or neither does.
        recordEvent(saved.getId(), actorId, WorkItemEvent.EVENT_CREATED, null, saved.getStatus(), null);
        return convertToDTO(saved);
    }

    /**
     * Update a work item (PATCH semantics: a null field is not changed).
     * <p>
     * Ownership: a foreign private project is 404, a foreign public project 403.
     * State: the requested state must be a legal transition from the current one,
     * otherwise 409. A request that asks for {@code claimed} is always rejected
     * with 409 — {@code claimed} is reachable only through
     * {@code POST /api/v1/work-items/{id}/claim}. Leaving {@code claimed} for any
     * state other than {@code running} releases the claim (assignee, token and
     * lease are cleared) so a released item cannot keep a stale lease.
     */
    @Transactional
    public WorkItemDTO updateWorkItem(Long id, WorkItemRequestDTO dto, String username) {
        User user = requireUser(username);
        WorkItem item = requireItem(id);
        Project project = requireVisibleProject(item.getProjectId(), user);
        Long actorId = requestActorResolver.currentAttributionActorId(username);
        requireProjectWrite(project, user, actorId, "update");

        // Null until a status was actually requested: "no transition happened" and
        // "the transition left the state as it was" must not be confused.
        String statusBefore = null;
        boolean claimHeldBefore = false;

        if (dto != null) {
            if (dto.getTitle() != null) {
                item.setTitle(requireTitle(dto.getTitle()));
            }
            if (dto.getBody() != null) {
                item.setBody(dto.getBody());
            }
            if (dto.getPriority() != null && !dto.getPriority().isBlank()) {
                item.setPriority(normalizePriority(dto.getPriority()));
            }
            // null = keep, empty list = clear
            if (dto.getLabels() != null) {
                item.setLabels(encodeLabels(dto.getLabels()));
            }
            if (dto.getExternalRef() != null) {
                String externalRef = normalizeExternalRef(dto.getExternalRef());
                requireExternalRefFree(externalRef, item.getId());
                item.setExternalRef(externalRef);
            }
            if (dto.getParentId() != null) {
                validateParent(dto.getParentId(), item.getProjectId(), item.getId());
                item.setParentId(dto.getParentId());
            }
            if (dto.getStatus() != null && !dto.getStatus().isBlank()) {
                // Snapshot taken before the transition, because the transition is
                // what erases both the state and the claim triple it carried. The
                // audit needs the "from", and after the fact it is gone.
                statusBefore = item.getStatus();
                claimHeldBefore = holdsClaim(item);
                applyStatusTransition(item, dto.getStatus());
            }
        }

        item.setUpdatedByActorId(actorId);
        WorkItem saved = saveAndTranslateConflicts(item);

        // WIKI4AI-145: one event per thing this request actually changed. A request
        // that only renames the item records nothing — this table is the timeline of
        // a state, not a changelog of text. A rejected transition never reaches this
        // line: applyStatusTransition threw first and the transaction is rolled back,
        // so a 409 leaves no trace in the audit trail.
        if (statusBefore != null && !statusBefore.equals(saved.getStatus())) {
            recordEvent(saved.getId(), actorId, WorkItemEvent.EVENT_STATUS_CHANGED,
                    statusBefore, saved.getStatus(), null);
            if (claimHeldBefore && !holdsClaim(saved)) {
                // The claim triple did not survive the move. It is a second fact of
                // the same transition — the item changed state AND stopped being
                // held — and both belong in the trail, because "who released it" and
                // "why it is back in the pool" are two questions an agent asks.
                recordEvent(saved.getId(), actorId, WorkItemEvent.EVENT_CLAIM_RELEASED,
                        statusBefore, saved.getStatus(), null);
            }
        }
        return convertToDTO(saved);
    }

    /**
     * Delete a work item. Ownership rules are the same as for an update:
     * foreign private project → 404, foreign public project → 403.
     */
    @Transactional
    public void deleteWorkItem(Long id, String username) {
        User user = requireUser(username);
        WorkItem item = requireItem(id);
        Project project = requireVisibleProject(item.getProjectId(), user);
        Long actorId = requestActorResolver.currentAttributionActorId(username);
        requireProjectWrite(project, user, actorId, "delete");

        // WIKI4AI-145: the event is written BEFORE the row disappears, in the same
        // transaction, because after the DELETE the item and its state no longer
        // exist to be described. It carries no 'to_status': deletion has no successor
        // state.
        //
        // What happens to that event afterwards depends on the database, and the
        // difference is worth stating rather than discovering: V21 declares
        // work_item_id ... REFERENCES work_items(id) ON DELETE CASCADE (SPEC's DDL),
        // so on PostgreSQL the cascade takes the whole timeline of a deleted item
        // with it, event included. The audit of a deleted item therefore does not
        // outlive the item. Making it survive means changing that FK, which is a
        // schema decision this story does not make. On the Hibernate-created test
        // schema the columns carry no foreign key at all, so the event is observable
        // there — which is why the test that asserts it says what it actually proves.
        recordEvent(item.getId(), actorId, WorkItemEvent.EVENT_DELETED, item.getStatus(), null,
                Map.of("title", item.getTitle()));
        workItemRepository.delete(item);
    }

    // ── Claim (WIKI4AI-141) ───────────────────────────────────────────────────

    /**
     * Atomically claim a ready work item for the actor of the current credential.
     * <p>
     * The whole race is decided by one statement —
     * {@code UPDATE ... WHERE id = :id AND status = 'ready'} — so exactly one of
     * several concurrent claimants updates a row. Everyone else updates 0 rows and
     * gets 409 Conflict; that is also why an item that is not {@code ready}
     * (already claimed, running, cancelled, ...) cannot be claimed at all.
     * <p>
     * The caller must carry an actor. A credential that names no actor — a legacy
     * JWT whose user has no {@code kind='human'} actor — gets 403 Forbidden, not
     * 500: the request is well-formed, what it lacks is the identity required to
     * hold a lease. (Decision outside the SPEC; 400 would claim the payload is
     * malformed, which it is not.)
     *
     * @return the claim triple: assignee, token and lease expiry
     */
    @Transactional
    public WorkItemClaimDTO claimWorkItem(Long id, String username) {
        Long actorId = requestActorResolver.currentAttributionActorId(username);
        if (actorId == null) {
            throw new AccessDeniedException(
                    "Claiming a work item requires an actor identity; this credential names no actor");
        }
        User user = resolveUser(username);
        WorkItem item = requireItem(id);
        Project project = requireVisibleProject(item.getProjectId(), user);
        requireProjectWrite(project, user, actorId, "claim work items of");

        LocalDateTime lease = LocalDateTime.now().plusMinutes(leaseMinutes);
        String token = newClaimToken();

        int claimed = workItemRepository.claim(id, actorId, lease, token);
        if (claimed == 0) {
            // Not 'ready' — already claimed by somebody (possibly a race we lost),
            // in progress, blocked, done or cancelled. GlobalExceptionHandler → 409.
            throw new IllegalArgumentException(
                    "Work item " + id + " could not be claimed: it is not in status 'ready' "
                            + "(it was already claimed, is being worked on, or is finished/cancelled).");
        }

        // clearAutomatically emptied the persistence context: the instance loaded
        // above is detached and stale, so the claim result is read back from the row.
        WorkItem claimedItem = workItemRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Work item not found with id: " + id));
        // The claim is a write like any other, so it is attributed like any other.
        claimedItem.setUpdatedByActorId(actorId);
        WorkItem saved = workItemRepository.save(claimedItem);

        // WIKI4AI-145: recorded only on the branch where the race was won. The
        // 409 branch threw above, so a claim that failed to win leaves no event —
        // the trail records claims that happened, not attempts.
        recordEvent(saved.getId(), actorId, WorkItemEvent.EVENT_CLAIMED,
                WorkItem.STATUS_READY, saved.getStatus(), null);
        return convertToClaimDTO(saved);
    }

    // ── Lease: extension and reaping (WIKI4AI-142) ────────────────────────────

    /**
     * Extend the lease of a work item the caller is holding (AC of WIKI4AI-142:
     * "predlzanie lease funguje").
     * <p>
     * Who may extend: the holder, and only the holder.
     * <ul>
     *   <li>the item is not in a claim-holding state, or has no claim token →
     *       <b>409</b>: there is no lease to extend;</li>
     *   <li>the caller's actor (from the verified credential, never from a body
     *       field) is not the item's {@code assigneeActorId} → <b>403</b>;</li>
     *   <li>the caller sent a {@code claimToken} that is not the token of this
     *       lease → <b>403</b>. The token is optional — the assignee check alone
     *       already proves the holder — but when it is sent it must match, so a
     *       caller cannot extend a lease it does not possess.</li>
     * </ul>
     * A credential that names no actor is 403, exactly like a claim without one.
     * <p>
     * The write itself is one conditional UPDATE guarded by the id, the state, the
     * assignee and the token, so a lease that the reaper released in the meantime
     * cannot be extended under a stale claim: the statement matches 0 rows and the
     * result is 409.
     *
     * @param claimToken optional proof of the lease; when present it must match
     * @return the refreshed claim triple
     */
    @Transactional
    public WorkItemClaimDTO extendLease(Long id, String claimToken, String username) {
        Long actorId = requestActorResolver.currentAttributionActorId(username);
        if (actorId == null) {
            throw new AccessDeniedException(
                    "Extending a lease requires an actor identity; this credential names no actor");
        }
        User user = resolveUser(username);
        WorkItem item = requireItem(id);
        requireVisibleProject(item.getProjectId(), user);

        if (!CLAIM_HOLDING_STATUSES.contains(item.getStatus()) || item.getClaimToken() == null) {
            // GlobalExceptionHandler → 409: nothing is being held, so there is nothing to extend.
            throw new IllegalArgumentException("Work item " + id + " holds no lease to extend: it is in status '"
                    + item.getStatus() + "'. Only a 'claimed' or 'running' item has a lease, and only "
                    + "POST /api/v1/work-items/{id}/claim creates one.");
        }
        if (!Objects.equals(item.getAssigneeActorId(), actorId)) {
            throw new AccessDeniedException("Only the actor holding the lease (assigneeActorId="
                    + item.getAssigneeActorId() + ") may extend it; this credential acts as actor " + actorId);
        }
        if (claimToken != null && !claimToken.isBlank()
                && !tokensMatch(claimToken.trim(), item.getClaimToken())) {
            throw new AccessDeniedException("The claim token does not match the lease of work item " + id);
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime lease = now.plusMinutes(leaseMinutes);
        int extended = workItemRepository.extendLease(id, actorId, item.getClaimToken(), lease, now);
        if (extended == 0) {
            // The row moved under us: the reaper released it, or somebody released
            // it through an update. The claim we were extending no longer exists.
            throw new IllegalArgumentException("Work item " + id
                    + " could not be extended: its lease was released or replaced while this request ran. "
                    + "Re-read the item and claim it again if it is 'ready'.");
        }

        // clearAutomatically emptied the persistence context: read the fresh row back.
        WorkItem extendedItem = workItemRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Work item not found with id: " + id));

        // WIKI4AI-145: from and to are the same state on purpose. A lease renewal
        // does not move the item anywhere; it extends how long it stays where it is.
        // The payload is what the renewal actually changed — the new expiry.
        recordEvent(extendedItem.getId(), actorId, WorkItemEvent.EVENT_LEASE_EXTENDED,
                extendedItem.getStatus(), extendedItem.getStatus(),
                Map.of("leaseExpiresAt", String.valueOf(extendedItem.getLeaseExpiresAt())));
        return convertToClaimDTO(extendedItem);
    }

    /**
     * Release every lease whose TTL has passed (AC of WIKI4AI-142: "po vyprsani
     * TTL je uloha opat ready") and record one {@code lease_expired} event for each
     * item it actually released (AC of WIKI4AI-146: "timeline obsahuje aj
     * lease_expired").
     *
     * <h2>How the sweep stays atomic while the audit stays per-row</h2>
     * The statement that writes is still a single conditional UPDATE whose WHERE
     * clause is the whole candidate test — {@code id = :id AND status IN
     * ('claimed','running') AND leaseExpiresAt < :now}. Two backends sweeping the
     * same database at the same tick therefore still cannot both release one item:
     * the row stops matching its own guard the moment it is written, and the loser
     * gets 0.
     * <p>
     * What changed is that the guard now carries an id, and the sweep runs it once
     * per candidate instead of once over a set. That is the only way to learn which
     * rows moved: JPQL has no {@code RETURNING} (SPEC rule 12, and H2 has no such
     * thing), so a bulk UPDATE can report a count but never a set — and an audit
     * trail is a set, not a count. The candidate SELECT is not a read-modify-write:
     * it produces a list of ids to <i>try</i>, and every write is still guarded by
     * the candidate test itself.
     * <p>
     * The consequence, and the reason the loop is written this way: an event is
     * appended only when {@code releaseExpiredLease} returned 1. A candidate whose
     * row moved under the sweep — its holder extended the lease a millisecond before
     * the tick, another instance released it, an update released it — returns 0 and
     * produces no event at all. An event for a row whose state did not change would
     * be a false fact in the one table that is not allowed to contain any.
     * <p>
     * Cost: one guarded UPDATE per expired lease instead of one statement for all of
     * them. A sweep normally finds zero candidates, and the number it can find is
     * bounded by leases that went unattended within one TTL; each statement is a
     * single-row update by primary key. Correctness of an audit trail is worth more
     * than one round trip.
     * <p>
     * {@code running} is reaped along with {@code claimed}. Justification: a lease
     * is a claim on the item, not a claim on a phase of the work. An agent that
     * moved the item to {@code running} and then died leaves the item looking
     * actively worked on forever; without reaping {@code running} the pool would
     * never get it back and the TTL would only cover the shortest phase of the
     * lifecycle.
     * <p>
     * This is the deterministic entry point of the reaper: {@code WorkItemLeaseReaper}
     * calls exactly this method from its {@code @Scheduled} wrapper, and a test can
     * call it directly and know what it did.
     *
     * @return the number of work items returned to {@code ready} — which is also the
     *         number of {@code lease_expired} events appended, by construction
     */
    @Transactional
    public int releaseExpiredLeases() {
        LocalDateTime now = LocalDateTime.now();
        List<WorkItem> candidates = workItemRepository.findExpiredLeaseCandidates(now);
        if (candidates.isEmpty()) {
            return 0;
        }
        // Snapshot before the first write: the update sets assigneeActorId to null,
        // and the event has to name the actor who held the lease. After the write
        // that information exists nowhere on the row.
        List<ReaperCandidate> snapshot = candidates.stream()
                .map(item -> new ReaperCandidate(item.getId(), item.getStatus(),
                        item.getAssigneeActorId(), item.getLeaseExpiresAt()))
                .collect(Collectors.toList());

        int released = 0;
        for (ReaperCandidate candidate : snapshot) {
            if (workItemRepository.releaseExpiredLease(candidate.id(), now) == 0) {
                // The guard no longer held. Somebody else wrote this row first, or
                // its holder renewed the lease. Nothing changed here, so nothing is
                // recorded here.
                log.debug("Lease sweep skipped work item {}: it stopped qualifying before the write",
                        candidate.id());
                continue;
            }
            released++;
            recordEvent(candidate.id(), candidate.actorId(), WorkItemEvent.EVENT_LEASE_EXPIRED,
                    candidate.status(), WorkItem.STATUS_READY,
                    Map.of("expiredLeaseAt", String.valueOf(candidate.leaseExpiresAt())));
        }
        return released;
    }

    /**
     * Heal items that are in a claim-free state but still carry a claim triple —
     * the rows WIKI4AI-170 left behind in databases written before the fix.
     * <p>
     * Same shape as {@link #releaseExpiredLeases} and for the same reason: the write
     * is one guarded UPDATE per row, so the sweep knows exactly which rows it
     * cleaned and can append one {@code stale_claim_cleared} event for each of them.
     * Atomic and idempotent — the write removes the very condition that made the row
     * a candidate, so a second sweep finds nothing.
     * <p>
     * The event names the actor whose claim was cleared, which is the only actor a
     * heal can honestly name: it has no caller. When the row carried no assignee at
     * all, {@code actorId} is null and the event says so rather than inventing one.
     *
     * @return the number of rows cleaned — also the number of events appended
     */
    @Transactional
    public int clearStaleClaims() {
        List<WorkItem> candidates = workItemRepository.findStaleClaimCandidates();
        if (candidates.isEmpty()) {
            return 0;
        }
        List<ReaperCandidate> snapshot = candidates.stream()
                .map(item -> new ReaperCandidate(item.getId(), item.getStatus(),
                        item.getAssigneeActorId(), item.getLeaseExpiresAt()))
                .collect(Collectors.toList());

        int cleaned = 0;
        for (ReaperCandidate candidate : snapshot) {
            if (workItemRepository.clearStaleClaim(candidate.id()) == 0) {
                continue;
            }
            cleaned++;
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("clearedAssigneeActorId", candidate.actorId());
            payload.put("clearedLeaseExpiresAt", String.valueOf(candidate.leaseExpiresAt()));
            recordEvent(candidate.id(), candidate.actorId(), WorkItemEvent.EVENT_STALE_CLAIM_CLEARED,
                    candidate.status(), candidate.status(), payload);
        }
        return cleaned;
    }

    /**
     * What the sweep knew about a candidate before it wrote it. A private record
     * rather than a list of detached entities: the bulk update clears the
     * persistence context, and a detached entity read afterwards is a stale lie.
     */
    private record ReaperCandidate(Long id, String status, Long actorId, LocalDateTime leaseExpiresAt) {
    }

    /**
     * Constant-time comparison of two claim tokens. The token is a secret that
     * authorises work on an item, so it is compared the way secrets are compared;
     * {@link MessageDigest} is java.base, no new dependency.
     */
    private boolean tokensMatch(String provided, String stored) {
        if (provided == null || stored == null) {
            return false;
        }
        return MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8),
                stored.getBytes(StandardCharsets.UTF_8));
    }


    /**
     * Validate and apply a state change requested through a plain update.
     * Same state = no change (allowed). Anything else must be in the transition
     * table, or it is 409.
     */
    private void applyStatusTransition(WorkItem item, String requested) {
        String target = normalizeStatus(requested);
        String current = item.getStatus();

        if (WorkItem.STATUS_CLAIMED.equals(target) && !WorkItem.STATUS_CLAIMED.equals(current)) {
            // AC of WIKI4AI-141: 'claimed' is not reachable through an update.
            throw new IllegalArgumentException(
                    "Status 'claimed' can only be set by POST /api/v1/work-items/{id}/claim");
        }
        if (target.equals(current)) {
            return;
        }

        Set<String> allowed = ALLOWED_TRANSITIONS.getOrDefault(current, Set.of());
        if (!allowed.contains(target)) {
            throw new IllegalArgumentException("Invalid status transition '" + current + "' -> '" + target
                    + "'. Allowed from '" + current + "': "
                    + new TreeSet<>(allowed).stream().collect(Collectors.joining(", "))
                    + " (keeping '" + current + "' is allowed too).");
        }

        // WIKI4AI-150: the invariant of 'done'. Checked AFTER the transition table and
        // before anything is written, in this one method, which is the only place an
        // update can move an item to another state. Two consequences of that placement:
        //   * an illegal transition (backlog -> done) is still reported as an illegal
        //     transition, not as a missing comment — the state machine answers first;
        //   * a rejected 'done' never reaches item.setStatus, never reaches save() and
        //     therefore never reaches recordEvent: the transaction ends on the throw, so
        //     the audit trail of WIKI4AI-145 keeps no 'status_changed' for a transition
        //     that did not happen.
        // 'done' is reachable from exactly one state — 'review' — so this one edge is
        // the whole transition-side surface of the invariant. claim() can only produce
        // 'claimed', and createWorkItem is refused below.
        if (WorkItem.STATUS_DONE.equals(target)) {
            requireResultCommentForDone(item.getId());
        }

        item.setStatus(target);
        // WIKI4AI-170: the claim triple follows the state, not the history of the
        // item. 'ready', 'blocked' and 'cancelled' never hold a claim, whatever
        // state the item came from — so an item that is back in the pool cannot be
        // listed as if somebody still held it.
        if (CLAIM_FREE_STATUSES.contains(target)) {
            releaseClaim(item);
        } else if (LEASE_ENDING_STATUSES.contains(target)) {
            // 'review' and 'done' are not claim states either: the lease and the
            // token end here. The assignee stays as the record of who did the work.
            endLeaseKeepAssignee(item);
        }
    }

    // ── WIKI4AI-150: 'done' requires a 'result' comment ───────────────────────

    /**
     * The invariant of WIKI4AI-150: a work item may enter {@code done} only when its
     * result is written down as a comment of kind {@code result}.
     * <p>
     * Enforced here, in the service, on the one edge that can reach {@code done}
     * ({@code review -> done}) — not in a controller, so a caller that reaches the
     * service through any other bean hits the same rule, and not "somewhere", so there
     * is no path that skips it: {@code applyStatusTransition} is the only method an
     * update uses to change a state, {@code claimWorkItem} can only produce
     * {@code claimed}, and {@code createWorkItem} is refused separately.
     * <p>
     * <b>Whose comment counts: any actor's.</b> The invariant is about the existence
     * and readability of a result, not about who authored it. A reviewer who verified
     * the work and wrote the result comment, and the agent that performed it, are both
     * honest authors of "what was produced", and requiring the completing actor to be
     * the author would punish exactly the handover this coordination layer exists to
     * support: agent A does the work, agent B reviews and closes. The author is still
     * recorded on the comment ({@code author_actor_id}, from the credential), so the
     * information a stricter rule would protect is already there and already auditable.
     * <p>
     * A {@code result} comment that broke its payload contract (422) was never written,
     * so it cannot satisfy this check: {@code WorkItemCommentService.create} throws
     * before {@code repository.save}, the transaction rolls back, and the item stays
     * where it was — which is why a 422 comment is followed by a 409 {@code done}, not
     * by a {@code done}.
     *
     * @throws IllegalArgumentException mapped by {@code GlobalExceptionHandler} to
     *                                  <b>409 Conflict</b>. 422 is not used here: 422
     *                                  is the answer to a payload that breaks a contract
     *                                  ({@code CommentContractException}), and nothing
     *                                  about this request's payload is malformed — the
     *                                  state it asks for conflicts with what the item
     *                                  already contains.
     */
    private void requireResultCommentForDone(Long id) {
        if (!workItemCommentRepository.existsByWorkItemIdAndKind(id, WorkItemComment.KIND_RESULT)) {
            throw new IllegalArgumentException(resultCommentRequiredMessage(id));
        }
    }

    /**
     * The 409 body of the invariant. Machine-readable first — {@value #RESULT_COMMENT_REQUIRED}
     * is the first token and is stable — then human-readable: what is missing, which
     * method on which endpoint adds it, and the payload fields that kind demands.
     */
    private String resultCommentRequiredMessage(Long id) {
        return RESULT_COMMENT_REQUIRED + ": work item " + id + " cannot enter status '"
                + WorkItem.STATUS_DONE + "' because it has no comment of kind 'result'. "
                + "Add one first: POST /api/v1/work-items/" + id + "/comments with body "
                + "{\"kind\":\"result\",\"body\":\"<what was produced>\",\"payload\":{\"where\":\"<where it landed>\","
                + "\"next\":\"<what happens next>\",\"artifacts\":[\"<artifact>\"]}} — 'where', 'next' and "
                + "'artifacts[]' are the payload contract of kind 'result' (WIKI4AI-149). "
                + "Invariant: an item is 'done' only when its result is written down.";
    }

    /**
     * The same invariant seen from the create door, where there is no id to name yet —
     * so the message states the route instead of the single call.
     */
    private String resultCommentRequiredOnCreate() {
        return RESULT_COMMENT_REQUIRED + ": a work item cannot be created in status '"
                + WorkItem.STATUS_DONE + "', because 'done' requires a comment of kind 'result' and a"
                + " comment can only be attached to a work item that already exists. Create it in"
                + " 'review' (or any earlier state), append the result comment with"
                + " POST /api/v1/work-items/{id}/comments — {\"kind\":\"result\",\"body\":\"…\","
                + "\"payload\":{\"where\":\"…\",\"next\":\"…\",\"artifacts\":[\"…\"]}} — and only then move it"
                + " to 'done' with PUT /api/v1/work-items/{id}. Invariant: an item is 'done' only when"
                + " its result is written down.";
    }

    /** Drop the claim triple from an item that is no longer held by anybody. */
    private void releaseClaim(WorkItem item) {
        item.setAssigneeActorId(null);
        item.setClaimToken(null);
        item.setLeaseExpiresAt(null);
    }

    /**
     * Whether this item carries any part of a claim triple. Used only to decide
     * whether a transition really released something: an item that was not held and
     * is still not held did not have a claim released, and an audit trail does not
     * record non-events.
     */
    private boolean holdsClaim(WorkItem item) {
        return item.getAssigneeActorId() != null
                || item.getClaimToken() != null
                || item.getLeaseExpiresAt() != null;
    }

    // ── Audit trail (WIKI4AI-145) ─────────────────────────────────────────────

    /**
     * Append one event to the trail of one work item.
     * <p>
     * The single place this class writes to {@code work_item_events}, and the only
     * thing it can do there is append: {@code WorkItemEventService.record} is
     * {@link org.springframework.transaction.annotation.Propagation#MANDATORY}, so
     * the event joins the transaction of the state change it describes instead of
     * starting one of its own — which is what makes "the audit row exists even though
     * the change was rolled back" impossible rather than merely discouraged.
     * <p>
     * The null branch is the hand-built service of the pure unit tests, which has no
     * audit trail to write to. See {@link #workItemEventService}.
     */
    private void recordEvent(Long workItemId, Long actorId, String event,
                             String fromStatus, String toStatus, Map<String, Object> payload) {
        if (workItemEventService == null) {
            return;
        }
        workItemEventService.record(workItemId, actorId, event, fromStatus, toStatus, payload);
    }

    /**
     * Who may read the timeline of a work item (AC of WIKI4AI-146).
     * <p>
     * This lives here rather than in the timeline service or the controller because
     * it is the same rule the items themselves use, and a rule stated twice in this
     * project is a rule that drifts. The rules are:
     * <ul>
     *   <li>an item in someone else's <b>private</b> project → <b>404</b>, exactly as
     *   {@link #getWorkItem}: its existence is not revealed;</li>
     *   <li>an item in a <b>public</b> project the caller has no part in →
     *   <b>403</b>. This is stricter than reading the item, which anybody may do,
     *   and the strictness is the point: a timeline is a list of named actors with
     *   timestamps — who claimed the work, who held it, for how long, who lost it.
     *   That is the same kind of per-actor activity data WIKI4AI-147 protects, so it
     *   gets the same treatment as writing to the project: the project's owner, an
     *   ADMIN user, or a caller whose actor holds at least {@code worker} there.</li>
     * </ul>
     * (Decision outside the SPEC, stated because it is a choice: SPEC rule 7's
     * shorthand "cudzí public = 403" is written for writes, and
     * {@code GET /work-items/{id}} answers 200 for a foreign public item. Applying
     * the shorthand literally to the timeline is the conservative reading, and for
     * actor-attributed history the conservative reading is the defensible one.)
     */
    public void requireEventTimelineAccess(Long id, String username) {
        User user = resolveUser(username);
        WorkItem item = requireItem(id);
        Project project = requireVisibleProject(item.getProjectId(), user);
        requireProjectWrite(project, user, requestActorResolver.currentAttributionActorId(username),
                "read the timeline of");
    }

    /**
     * Who may read and who may write the comments of a work item (AC of
     * WIKI4AI-148). Added here, next to {@link #requireEventTimelineAccess}, for the
     * same reason that one lives here: it is the same rule the items themselves use,
     * and a rule stated twice in this project is a rule that drifts.
     * <p>
     * The rules, identical to the timeline's on purpose — a comment thread is the
     * same kind of data as a timeline (actor-attributed history of an item), so it
     * gets the same protection:
     * <ul>
     *   <li>an item in someone else's <b>private</b> project → <b>404</b>: its
     *   existence is not revealed;</li>
     *   <li>an item in a <b>public</b> project the caller has no part in →
     *   <b>403</b>: reading a comment names an actor, and writing one is a write to
     *   the project. Allowed for the project's owner, an ADMIN user, or a caller whose
     *   actor holds at least {@code worker} there.</li>
     * </ul>
     * A missing item is 404 before any of this, so a POST to an item that does not
     * exist cannot be answered with anything else.
     *
     * @return the id of the project the item lives in. The caller that needed no
     *         project before WIKI4AI-174 still needs none — a caller that ignores this
     *         value is unaffected. It is returned because the project-scoped rule of
     *         WIKI4AI-174 ({@code handoff} may only name a member of this project) is
     *         checked by {@code WorkItemCommentService}, and re-reading the item to
     *         learn its project after this method already loaded it would be a second
     *         lookup of the same row and a second place that decides what "this item's
     *         project" means.
     */
    public Long requireCommentAccess(Long id, String username) {
        User user = resolveUser(username);
        WorkItem item = requireItem(id);
        Project project = requireVisibleProject(item.getProjectId(), user);
        requireProjectWrite(project, user, requestActorResolver.currentAttributionActorId(username),
                "comment on");
        return project.getId();
    }

    /**
     * End a lease without erasing who held it: used when work moves to
     * {@code review} or {@code done}, where nobody may act on the item any more
     * but "who did it" is still worth reading.
     */
    private void endLeaseKeepAssignee(WorkItem item) {
        item.setClaimToken(null);
        item.setLeaseExpiresAt(null);
    }

    // ── Validation helpers ────────────────────────────────────────────────────

    private String requireTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new BadRequestException("Work item title is required");
        }
        return title.trim();
    }

    /** Normalize a state value; an unknown value is 400, not a silent empty result. */
    private String normalizeStatus(String status) {
        String value = status.trim().toLowerCase(Locale.ROOT);
        if (!STATUSES.contains(value)) {
            throw new BadRequestException("Unknown status '" + status + "'. Allowed values: "
                    + new TreeSet<>(STATUSES).stream().collect(Collectors.joining(", ")));
        }
        return value;
    }

    /**
     * State accepted on create. 'claimed' is rejected with 400: it is not a state
     * a create can produce, only a claim can.
     */
    private String normalizeCreateStatus(String status) {
        if (status == null || status.isBlank()) {
            return WorkItem.DEFAULT_STATUS;
        }
        String value = normalizeStatus(status);
        if (WorkItem.STATUS_CLAIMED.equals(value)) {
            throw new BadRequestException(
                    "Status 'claimed' cannot be set on create; claim the item with POST /api/v1/work-items/{id}/claim");
        }
        return value;
    }

    private String normalizePriority(String priority) {
        if (priority == null || priority.isBlank()) {
            return WorkItem.DEFAULT_PRIORITY;
        }
        String value = priority.trim().toLowerCase(Locale.ROOT);
        if (!PRIORITIES.contains(value)) {
            throw new BadRequestException("Unknown priority '" + priority + "'. Allowed values: "
                    + new TreeSet<>(PRIORITIES).stream().collect(Collectors.joining(", ")));
        }
        return value;
    }

    /**
     * Normalize an external reference. Null means "no external reference" and is
     * legal; a value that is present but blank is a client error (400), because it
     * would occupy the UNIQUE column with something meaningless.
     */
    private String normalizeExternalRef(String externalRef) {
        if (externalRef == null) {
            return null;
        }
        if (externalRef.isBlank()) {
            throw new BadRequestException("External reference must not be blank");
        }
        return externalRef.trim();
    }

    /** Duplicate external_ref is 409 (the DB constraint is UNIQUE, V20). */
    private void requireExternalRefFree(String externalRef, Long selfId) {
        if (externalRef == null) {
            return;
        }
        boolean taken = (selfId == null)
                ? workItemRepository.existsByExternalRef(externalRef)
                : workItemRepository.existsByExternalRefAndIdNot(externalRef, selfId);
        if (taken) {
            throw new IllegalArgumentException(
                    "A work item with external reference '" + externalRef + "' already exists");
        }
    }

    /**
     * A parent must exist, must live in the same project, must not be the item
     * itself, and must not be one of the item's own descendants (which would
     * close a cycle in the parent chain).
     */
    private void validateParent(Long parentId, Long projectId, Long selfId) {
        if (parentId == null) {
            return;
        }
        if (parentId.equals(selfId)) {
            throw new BadRequestException("A work item cannot be its own parent");
        }
        WorkItem parent = workItemRepository.findById(parentId)
                .orElseThrow(() -> new EntityNotFoundException("Parent work item not found with id: " + parentId));
        if (!Objects.equals(parent.getProjectId(), projectId)) {
            throw new BadRequestException("Parent work item must belong to the same project");
        }
        // Walk up from the new parent; meeting the item again would be a cycle.
        Set<Long> visited = new LinkedHashSet<>();
        WorkItem cursor = parent;
        while (cursor != null && cursor.getParentId() != null && visited.add(cursor.getId())) {
            if (selfId != null && selfId.equals(cursor.getParentId())) {
                throw new BadRequestException(
                        "Cannot set parent " + parentId + ": it would create a cycle in the work item hierarchy");
            }
            Long next = cursor.getParentId();
            cursor = workItemRepository.findById(next).orElse(null);
        }
    }

    /**
     * Encode labels to the TEXT column: trimmed, non-blank, deduplicated, joined
     * with commas. An empty (or blank-only) list stores NULL, so "no labels" has
     * exactly one representation in the database.
     */
    private String encodeLabels(List<String> labels) {
        if (labels == null) {
            return null;
        }
        List<String> cleaned = labels.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .collect(Collectors.toList());
        return cleaned.isEmpty() ? null : String.join(",", cleaned);
    }

    /** Decode the TEXT column back to a list; null reads as "no labels". */
    private List<String> decodeLabels(String labels) {
        if (labels == null || labels.isBlank()) {
            return List.of();
        }
        return List.of(labels.split(",")).stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    /** 64 hex characters — exactly the width of the claim_token column (V20). */
    private String newClaimToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /**
     * Save, turning a late unique-constraint violation (a concurrent create with
     * the same external_ref) into the same 409 the pre-check produces.
     */
    private WorkItem saveAndTranslateConflicts(WorkItem item) {
        try {
            return workItemRepository.save(item);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException(
                    "Work item violates a uniqueness constraint (duplicate external reference): "
                            + e.getMessage());
        }
    }

    // ── Access and lookup helpers (same shape as ProjectActorService) ─────────

    private User resolveUser(String username) {
        if (username == null || username.isBlank()
                || "anonymous".equals(username) || "anonymousUser".equals(username)) {
            return null;
        }
        return userRepository.findByUsername(username).orElse(null);
    }

    /** Write operations need a concrete caller: no user → 403. */
    private User requireUser(String username) {
        User user = resolveUser(username);
        if (user == null) {
            throw new AccessDeniedException("Authentication required");
        }
        return user;
    }

    private WorkItem requireItem(Long id) {
        if (id == null) {
            throw new EntityNotFoundException("Work item not found with id: null");
        }
        return workItemRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Work item not found with id: " + id));
    }

    private Project requireProject(Long projectId) {
        if (projectId == null) {
            throw new EntityNotFoundException("Project not found with id: null");
        }
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new EntityNotFoundException("Project not found with id: " + projectId));
    }

    /** Load a project and enforce visibility (404 for a foreign private project). */
    private Project requireVisibleProject(Long projectId, User user) {
        Project project = requireProject(projectId);
        checkProjectVisible(project, user);
        return project;
    }

    /** Same rule as {@code ProjectService.canSeePrivate} / {@code ProjectActorService}. */
    private boolean canSeePrivate(Project project, User user) {
        if (user == null) {
            return false;
        }
        boolean isOwner = project.getOwner() != null
                && user.getId() != null && user.getId().equals(project.getOwner().getId());
        return isOwner || user.getRole() == Role.ADMIN;
    }

    private void checkProjectVisible(Project project, User user) {
        if (!Project.VISIBILITY_PRIVATE.equals(project.getVisibility())) {
            return;
        }
        if (!canSeePrivate(project, user)) {
            throw new EntityNotFoundException("Project not found with id: " + project.getId());
        }
    }

    /**
     * Who may write work items of a project: its owner, an ADMIN user, or a caller
     * whose actor holds at least the {@code worker} role there. Anonymous callers
     * and callers with no role in the project may not.
     */
    private boolean canWriteProject(Project project, User user, Long actorId) {
        if (user != null) {
            boolean isOwner = project.getOwner() != null
                    && user.getId() != null && user.getId().equals(project.getOwner().getId());
            if (isOwner || user.getRole() == Role.ADMIN) {
                return true;
            }
        }
        return projectActorService.hasAtLeast(project.getId(), actorId, ProjectActor.ROLE_WORKER);
    }

    /**
     * Enforce write access. Visibility has already been checked, so reaching here
     * means the project is public (or belongs to the caller) and the caller simply
     * has no right to write → 403 Forbidden.
     */
    private void requireProjectWrite(Project project, User user, Long actorId, String action) {
        if (canWriteProject(project, user, actorId)) {
            return;
        }
        throw new AccessDeniedException("You can only " + action
                + " projects you own, projects you administer, or projects where your actor has"
                + " at least the '" + ProjectActor.ROLE_WORKER + "' role");
    }

    /** Drop items living in projects the caller may not see. */
    private List<WorkItem> filterVisible(List<WorkItem> items, User user) {
        if (items.isEmpty()) {
            return items;
        }
        Set<Long> projectIds = items.stream()
                .map(WorkItem::getProjectId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, Project> projects = new LinkedHashMap<>();
        for (Project project : projectRepository.findAllById(projectIds)) {
            projects.put(project.getId(), project);
        }
        List<WorkItem> visible = new ArrayList<>();
        for (WorkItem item : items) {
            if (isVisible(projects.get(item.getProjectId()), user)) {
                visible.add(item);
            }
        }
        return visible;
    }

    private boolean isVisible(Project project, User user) {
        if (project == null) {
            // Dangling project reference: treat it as invisible rather than leak it.
            return false;
        }
        return !Project.VISIBILITY_PRIVATE.equals(project.getVisibility()) || canSeePrivate(project, user);
    }

    // ── DTO conversion (manual — no MapStruct, SPEC rule 13) ──────────────────

    /**
     * The read shape of a work item (SPEC rule 13: manual conversion, no MapStruct).
     * <p>
     * <b>{@code claimToken} is deliberately not filled here (WIKI4AI-177).</b> The
     * token is the second half of the pair by which an actor proves it holds a lease;
     * the only endpoint that has a use for it is the one that issued it —
     * {@code POST /work-items/{id}/claim} and the lease extension, both answered with
     * {@link WorkItemClaimDTO}. This method serves every read path, and a read path
     * hands its answer to whoever may read the project: measured on dev, one
     * {@code GET /work-items?status=claimed} under a legacy web JWT returned the claim
     * tokens of every claimed item of every project the caller could see. The UI needs
     * {@code leaseExpiresAt} to count a lease down and never needs the token, so the
     * field stays on the DTO — callers that build one internally are unaffected — it is
     * simply no longer filled from a work item.
     * <p>
     * {@code leaseExpiresAt} stays. It is not a secret: it says when a lease ends,
     * which is operational information the board renders.
     */
    private WorkItemDTO convertToDTO(WorkItem item) {
        return WorkItemDTO.builder()
                .id(item.getId())
                .projectId(item.getProjectId())
                .title(item.getTitle())
                .body(item.getBody())
                .status(item.getStatus())
                .priority(item.getPriority())
                .labels(decodeLabels(item.getLabels()))
                .parentId(item.getParentId())
                .externalRef(item.getExternalRef())
                .assigneeActorId(item.getAssigneeActorId())
                // WIKI4AI-177: no claimToken on a read path. WorkItemClaimDTO is the
                // only DTO that carries it, and only claim / lease-extend return that.
                .leaseExpiresAt(item.getLeaseExpiresAt())
                .createdByUserId(item.getCreatedByUserId())
                .createdByActorId(item.getCreatedByActorId())
                .updatedByActorId(item.getUpdatedByActorId())
                .createdAt(item.getCreatedAt())
                .updatedAt(item.getUpdatedAt())
                .version(item.getVersion())
                .build();
    }

    /**
     * The claim shape: the triple a holder has to keep — who holds it, the token that
     * proves it, and until when (WIKI4AI-142). Returned by {@code claimWorkItem} and
     * {@code extendLease} and by nothing else, which is what makes it the only place
     * the token leaves the application (WIKI4AI-177).
     */
    private WorkItemClaimDTO convertToClaimDTO(WorkItem item) {
        return WorkItemClaimDTO.builder()
                .id(item.getId())
                .status(item.getStatus())
                .assigneeActorId(item.getAssigneeActorId())
                .claimToken(item.getClaimToken())
                .leaseExpiresAt(item.getLeaseExpiresAt())
                .version(item.getVersion())
                .updatedAt(item.getUpdatedAt())
                .build();
    }
}
