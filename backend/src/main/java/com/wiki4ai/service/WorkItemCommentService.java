package com.wiki4ai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wiki4ai.dto.WorkItemCommentDTO;
import com.wiki4ai.dto.WorkItemCommentRequestDTO;
import com.wiki4ai.exception.BadRequestException;
import com.wiki4ai.exception.CommentContractException;
import com.wiki4ai.model.Actor;
import com.wiki4ai.model.WorkItemComment;
import com.wiki4ai.repository.ActorRepository;
import com.wiki4ai.repository.WorkItemCommentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Comments of a work item (WIKI4AI-148) and the enforcement of their per-kind
 * contracts (WIKI4AI-149).
 *
 * <h2>The order the checks run in, and why that order</h2>
 * <ol>
 *   <li><b>Access</b> — {@code WorkItemService.requireCommentAccess}: a foreign
 *   private item is 404, a foreign public item 403. First, because nothing else
 *   about a request matters if the caller may not make it, and because answering 400
 *   for a malformed body on someone else's private item would confirm the item
 *   exists.</li>
 *   <li><b>Attribution</b> — the credential must name an actor, else 403. See
 *   {@link #create} for why a comment is the one write in this layer that refuses an
 *   unattributable caller.</li>
 *   <li><b>Required request fields</b> — {@code body} and {@code kind}: 400. These
 *   are the fields the request itself has to carry.</li>
 *   <li><b>The payload contract of the kind</b> —
 *   {@code WorkItemCommentContract.validate}: 422. The request was understood; its
 *   payload breaks a contract.</li>
 * </ol>
 *
 * <h2>400 and 422 are different failures and this class is where the line is drawn</h2>
 * <table border="1">
 *   <caption>The same endpoint, four statuses</caption>
 *   <tr><th>request</th><th>status</th><th>why</th></tr>
 *   <tr><td>{@code {"kind":"note"}} — no body</td><td><b>400</b></td>
 *       <td>{@code body} is a required field of the request; V22 declares it
 *       {@code NOT NULL}. Nothing was understood wrongly — a piece is absent.</td></tr>
 *   <tr><td>{@code {"kind":"nonsense","body":"x"}}</td><td><b>400</b></td>
 *       <td>The request names a kind the API does not have.</td></tr>
 *   <tr><td>{@code {"kind":"result","body":"x"}} — no payload</td><td><b>422</b></td>
 *       <td>Every field of the request is present and readable; the payload does not
 *       satisfy the contract of {@code result}.</td></tr>
 *   <tr><td>{@code {"kind":"blocker","body":"x","payload":{"blocking":"…","needs_human":"true"}}}
 *       </td><td><b>422</b></td>
 *       <td>{@code needs_human} was understood as a value; it is the wrong type for a
 *       filterable flag.</td></tr>
 *   <tr><td>{@code {"kind":"handoff","body":"x","payload":{"to_handle":"agent-x","context":"…"}}}
 *       where {@code agent-x} is active but has no role in this project</td><td><b>422</b></td>
 *       <td>WIKI4AI-174. The payload satisfies the shape of {@code handoff}; it names
 *       an actor who can never receive it. Same status and same reasoning as naming an
 *       actor who does not exist — a request that is fully understood and describes a
 *       silent stall.</td></tr>
 * </table>
 * A 400 tells the client its request was malformed. A 422 tells it the request was
 * fine and the payload has to change — and names which fields. Merging them would
 * make the contract unfixable by inspection, which is why WIKI4AI-149 asks for 422
 * and why {@code GlobalExceptionHandler} gained a handler for it instead of reusing
 * the 400 one.
 *
 * <h2>The filters of WIKI4AI-151: {@code kind} and {@code needs_human}</h2>
 * {@code kind} is an indexed column and is pushed into the query. {@code needs_human} is
 * a key of the {@code blocker} payload, and it is filtered by reading the item's
 * blockers and matching the decoded key — never by pattern-matching the payload TEXT,
 * which is the same refusal WIKI4AI-144 made about {@code labels}. No column was added
 * and no table was added to make it filterable. See
 * {@link #list(Long, String, String, int, int, String)} for the semantics of the two
 * filters together, and for why {@code needs_human} is a state this service stores and
 * searches, never a message this service sends.
 *
 * <h2>Comments are immutable</h2>
 * This service has a {@code create} and a {@code list} and nothing else. There is no
 * update and no delete method to call, the repository's mutating methods all throw,
 * the entity's {@code @PreUpdate} / {@code @PreRemove} throw, and V22 installs a
 * BEFORE UPDATE trigger. See {@link WorkItemComment} for the four layers and why none
 * of them is redundant.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorkItemCommentService {

    /** Hard ceiling of one page, same shape as the timeline and documents endpoints. */
    public static final int MAX_PAGE_SIZE = 100;

    /**
     * The payload key the {@code needs_human} filter of WIKI4AI-151 reads. It is a key
     * of the {@code blocker} contract of WIKI4AI-149, not a column: V22 has no
     * {@code needs_human} column, this story adds none, and the filter decodes the JSON
     * payload instead of pattern-matching its TEXT.
     */
    public static final String NEEDS_HUMAN_FIELD = "needs_human";

    /** Default page size, same shape as the timeline and documents endpoints. */
    public static final int DEFAULT_PAGE_SIZE = 50;

    private static final TypeReference<Map<String, Object>> PAYLOAD_TYPE =
            new TypeReference<>() {
            };

    private final WorkItemCommentRepository workItemCommentRepository;
    private final WorkItemService workItemService;
    private final RequestActorResolver requestActorResolver;
    private final ActorRepository actorRepository;

    /**
     * Membership of a project, asked of the service that owns it (WIKI4AI-174). Not a
     * repository: the rule "this actor has a role in this project" is
     * {@code ProjectActorService}'s, and SPEC rule 7 says a rule is enforced where it
     * lives rather than re-derived by the caller.
     */
    private final ProjectActorService projectActorService;

    private final ObjectMapper objectMapper;

    // ── Write: the only door in the application ───────────────────────────────

    /**
     * Append one comment to a work item.
     *
     * @param workItemId the item to comment on
     * @param dto        the request body: kind, body, payload
     * @param username   authenticated principal name
     * @return the stored comment, with its id, its author and its {@code created_at}
     * @throws EntityNotFoundException  the item does not exist, or lives in someone
     *                                  else's private project (404)
     * @throws AccessDeniedException    the item is in a public project the caller has
     *                                  no part in, or the caller's credential names no
     *                                  actor (403)
     * @throws BadRequestException      {@code body} missing or blank, or {@code kind}
     *                                  missing or not one of the six (400)
     * @throws CommentContractException the payload breaks the contract of the kind
     *                                  (422)
     */
    @Transactional
    public WorkItemCommentDTO create(Long workItemId, WorkItemCommentRequestDTO dto, String username) {
        // 1. Access. Also the 404 of "the item does not exist". The project it answers
        //    for is the one the handoff rule of WIKI4AI-174 has to check against.
        Long projectId = workItemService.requireCommentAccess(workItemId, username);

        // 2. Attribution. A comment is a statement an actor made, so this is the one
        // write in the coordination layer that refuses a caller it cannot attribute:
        // work_items tolerate a null created_by_actor_id because an item can exist
        // before anybody is named, but a comment with no author is not a comment — it
        // is unaccountable text in a channel whose whole purpose is saying who said
        // what. A legacy JWT carries no actor and no user with a registered
        // kind='human' actor can post; the message says what to do about it.
        Long authorActorId = requestActorResolver.currentAttributionActorId(username);
        if (authorActorId == null) {
            throw new AccessDeniedException(
                    "A comment must be attributed to an actor. Authenticate with a w4a_ API token"
                            + " bound to an actor, or register a 'human' actor for this account"
                            + " under /api/v1/actors.");
        }

        // 3. Required request fields -> 400.
        String body = requireBody(dto);
        String kind = normalizeKind(dto.getKind());

        // 4. The payload contract of the kind -> 422. One place knows the rules.
        WorkItemCommentContract.validate(kind, dto.getPayload());
        if (WorkItemCommentContract.KIND_HANDOFF.equals(kind)) {
            requireHandoffTarget(dto.getPayload(), projectId);
        }

        WorkItemComment saved = workItemCommentRepository.save(WorkItemComment.builder()
                .workItemId(workItemId)
                .authorActorId(authorActorId)
                .kind(kind)
                .body(body)
                .payload(toJson(dto.getPayload()))
                .build());

        log.debug("work_item_comments: kind={} item={} author={}", kind, workItemId, authorActorId);
        return convertToDTO(saved);
    }

    // ── Reads ─────────────────────────────────────────────────────────────────

    /**
     * The comments of one work item, oldest first, paginated (AC of WIKI4AI-148).
     * <p>
     * Access is checked here rather than in the controller: this method takes the
     * caller's username anyway, and a read method that can be called by a bean that
     * forgot to check is a read method that leaks.
     *
     * @param workItemId the item whose comments are read
     * @param kind       optional kind filter, or null for every kind
     * @param page       0-indexed page number
     * @param size       page size, clamped to 1..{@value #MAX_PAGE_SIZE}
     * @param username   authenticated principal name
     */
    public Page<WorkItemCommentDTO> list(Long workItemId, String kind, int page, int size, String username) {
        return list(workItemId, kind, null, page, size, username);
    }

    /**
     * The comments of one work item with the filters of WIKI4AI-151.
     *
     * <h2>{@code kind} is a column filter; {@code needs_human} is a payload filter</h2>
     * {@code kind} is an indexed column of V22 ({@code idx_wic_item_kind}), so it is
     * pushed into the query. {@code needs_human} is not a column and this story adds
     * neither a column nor a table: it is a key inside the JSON {@code payload} TEXT of
     * a {@code blocker} comment, guaranteed to be there and to be a JSON boolean by the
     * contract of WIKI4AI-149. Filtering it in SQL would mean pattern-matching a TEXT
     * column, which is what WIKI4AI-144 already refused for {@code labels} for the same
     * reason: a substring test on a TEXT column matches things the filter never asked
     * for. So the item's {@code blocker} comments are read — one indexed query, bounded
     * by how many blockers one item has — decoded into their payload map, and matched on
     * the whole key. Same method as the label filter, applied to JSON instead of a
     * comma-separated list.
     *
     * <h2>{@code needs_human} is a property of {@code blocker} and of nothing else</h2>
     * The other five kinds have no such field in their contract, so the filter narrows
     * the read to {@code blocker} comments. Combined with a {@code kind} that is not
     * {@code blocker} the two filters cannot both be satisfied, and the answer is a
     * defined one rather than an error: an <b>empty page</b> (200, {@code totalElements
     * = 0}). "Give me the decisions that need a human" is a coherent question with an
     * empty answer; making it a 400 would turn a valid combination into a malformed
     * request.
     *
     * <p>
     * A comment whose payload carries no {@code needs_human} key, or carries it as
     * something that is not a JSON boolean, matches <b>neither</b> {@code true} nor
     * {@code false}. Absence is not false: reporting it as false would invent a fact
     * about a comment that never stated one.
     *
     * <p>
     * <b>This filter is a read.</b> {@code needs_human=true} is a state an agent records
     * about a blocker, and the only thing this API does with it is store it and let
     * somebody find it. There is no notification, no e-mail, no scheduler and no
     * {@code @Scheduled} task behind it — wiki4ai never pokes a human; a human (or the
     * agent watching the board) queries for it.
     *
     * @param workItemId the item whose comments are read
     * @param kind       optional kind filter, or null for every kind
     * @param needsHuman optional {@code needs_human} filter: {@code "true"} or
     *                   {@code "false"}; null or blank means no filter. Any other value
     *                   is 400.
     * @param page       0-indexed page number
     * @param size       page size, clamped to 1..{@value #MAX_PAGE_SIZE}
     * @param username   authenticated principal name
     */
    public Page<WorkItemCommentDTO> list(Long workItemId, String kind, String needsHuman,
                                         int page, int size, String username) {
        workItemService.requireCommentAccess(workItemId, username);

        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int pageNumber = Math.max(page, 0);
        // No Sort in the Pageable on purpose: the repository method already carries
        // the ordering (createdAt ASC, id ASC) and putting it in both places would
        // only make the generated ORDER BY harder to read.
        Pageable pageable = PageRequest.of(pageNumber, pageSize);

        Boolean wanted = parseNeedsHuman(needsHuman);
        if (wanted == null) {
            // No payload filter: the pre-WIKI4AI-151 shape of this read, unchanged.
            return (kind == null)
                    ? workItemCommentRepository.findByWorkItemIdOrderByCreatedAtAscIdAsc(workItemId, pageable)
                    .map(this::convertToDTO)
                    : workItemCommentRepository.findByWorkItemIdAndKindOrderByCreatedAtAscIdAsc(
                            workItemId, normalizeKind(kind), pageable)
                    .map(this::convertToDTO);
        }

        String kindFilter = (kind == null) ? WorkItemCommentContract.KIND_BLOCKER : normalizeKind(kind);
        if (!WorkItemCommentContract.KIND_BLOCKER.equals(kindFilter)) {
            // kind=decision AND needs_human=true: no comment can be both. Empty page.
            return Page.empty(pageable);
        }

        // One indexed read of the item's blockers, then whole-key matching on the
        // decoded payload. No LIKE, no new column, no new table.
        List<WorkItemCommentDTO> matching = workItemCommentRepository
                .findByWorkItemIdAndKindOrderByCreatedAtAscIdAsc(workItemId, kindFilter).stream()
                .filter(comment -> wanted.equals(needsHumanOf(comment)))
                .map(this::convertToDTO)
                .collect(Collectors.toList());

        int from = Math.min(matching.size(), pageNumber * pageSize);
        int to = Math.min(matching.size(), from + pageSize);
        return new PageImpl<>(matching.subList(from, to), pageable, matching.size());
    }

    /** Every comment of one item, oldest first. Test and diagnostics helper. */
    public List<WorkItemCommentDTO> listAll(Long workItemId) {
        return workItemCommentRepository.findByWorkItemIdOrderByCreatedAtAscIdAsc(workItemId).stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    /** Whether an item has at least one comment of a kind (the lookup WIKI4AI-150 needs). */
    public boolean hasCommentOfKind(Long workItemId, String kind) {
        return workItemCommentRepository.existsByWorkItemIdAndKind(workItemId, normalizeKind(kind));
    }

    // ── Validation helpers ────────────────────────────────────────────────────

    /**
     * {@code body} is required (V22: {@code body TEXT NOT NULL}). Missing or blank is
     * 400 — a required field of the request is absent — and deliberately not 422:
     * nothing about a payload contract has been evaluated yet, and a 422 here would
     * tell the client that its payload is the problem when the payload is not even
     * the field in question.
     */
    private String requireBody(WorkItemCommentRequestDTO dto) {
        if (dto == null || dto.getBody() == null || dto.getBody().isBlank()) {
            throw new BadRequestException("Comment body is required");
        }
        return dto.getBody().trim();
    }

    /**
     * Normalize a kind value; an unknown value is 400, not a silent empty result and
     * not a 422. The kind is not part of a payload — it is the request naming which
     * rule set it wants to be checked against, and a request that names a rule set
     * this API does not have is malformed. Same shape as
     * {@code WorkItemService.normalizeStatus}.
     */
    private String normalizeKind(String kind) {
        if (kind == null || kind.isBlank()) {
            throw new BadRequestException("Comment kind is required. Allowed values: "
                    + allowedKinds());
        }
        String value = kind.trim().toLowerCase(Locale.ROOT);
        if (!WorkItemCommentContract.isKnownKind(value)) {
            throw new BadRequestException("Unknown kind '" + kind + "'. Allowed values: "
                    + allowedKinds());
        }
        return value;
    }

    private String allowedKinds() {
        return new TreeSet<>(WorkItemCommentContract.kinds()).stream().collect(Collectors.joining(", "));
    }

    /**
     * Parse the {@code needs_human} query parameter (WIKI4AI-151).
     * <p>
     * It is read as a {@link String} and validated here rather than declared as a
     * {@code Boolean} on the controller, so the failure names the parameter the client
     * actually typed ({@code needs_human}, snake_case, the name in the AC) instead of
     * the Java method parameter, and so the message states the allowed values. Same
     * shape as {@code WorkItemService.normalizeStatus}: an unknown filter value is 400,
     * never a silent empty result and never a 422 — nothing about a payload contract is
     * being evaluated, the request itself carries a value this API cannot filter on.
     *
     * @return {@link Boolean#TRUE} / {@link Boolean#FALSE}, or {@code null} when no
     *         filter was asked for (absent or blank — the convention every filter of
     *         this project uses)
     */
    private Boolean parseNeedsHuman(String needsHuman) {
        if (needsHuman == null || needsHuman.isBlank()) {
            return null;
        }
        String value = needsHuman.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(value)) {
            return Boolean.TRUE;
        }
        if ("false".equals(value)) {
            return Boolean.FALSE;
        }
        throw new BadRequestException("Invalid value for parameter 'needs_human': '" + needsHuman
                + "'. Allowed values: true, false. The filter applies to comments of kind 'blocker',"
                + " whose payload carries 'needs_human' as a JSON boolean (WIKI4AI-149).");
    }

    /**
     * The {@code needs_human} of one stored comment, decoded from its payload.
     *
     * @return the Boolean the payload carries, or {@code null} when the comment says
     *         nothing about it: no payload, no such key, or a value that is not a JSON
     *         boolean. Null matches neither filter — see
     *         {@link #list(Long, String, String, int, int, String)}.
     */
    private Boolean needsHumanOf(WorkItemComment comment) {
        Map<String, Object> payload = toMap(comment.getPayload());
        if (payload == null) {
            return null;
        }
        Object value = payload.get(NEEDS_HUMAN_FIELD);
        return (value instanceof Boolean flag) ? flag : null;
    }

    /**
     * The one rule of the contract that needs the actor registry: {@code handoff}
     * names the actor the work is being passed to, and a handoff to an actor who does
     * not exist is exactly the mistake that is otherwise invisible — the comment is
     * written, the item waits, nobody comes. So the handle must resolve to an actor
     * whose status is {@code active}, and the failure is 422 with the handle named in
     * the message, because the request was understood and its payload is what is
     * wrong.
     * <p>
     * A handle, not an id: {@code to_handle} is how actors name each other in prose
     * and prompts, and an id in a payload would be a second way to point at an actor
     * that this API does not otherwise use. Handles are stored normalized to lowercase
     * by {@code ActorService}, so the lookup normalizes the same way.
     * <p>
     * {@code paused} and {@code archived} actors are refused for the same reason:
     * handing work to an actor that is not taking work is the same silent stall.
     * <p>
     * <h2>The second condition of the same rule (WIKI4AI-174)</h2>
     * Existing and active is necessary but not sufficient. The {@code mention} stream
     * of the inbox (WIKI4AI-152) is scoped to the projects an actor has a row for in
     * {@code project_actors}, so a handoff to an active actor with no role in
     * <b>this item's project</b> is accepted, written, and never seen by the actor it
     * names — the identical silent stall WIKI4AI-149 refuses, one step later. It is
     * therefore refused by this same method, with the same status and the same shape of
     * message: the request was fully understood, and its payload names something that
     * cannot receive the work.
     * <p>
     * <b>Any role passes, including {@code viewer}.</b> A {@code viewer} may not write
     * a comment — that is the write rule of {@code WorkItemService.requireProjectWrite},
     * already applied to the author above — but receiving a handoff is not a write, it
     * is being named. Refusing a handoff <i>to</i> a viewer would mean an agent could
     * only ever pass work to actors who could have taken the item themselves, which is
     * not what a handoff is. Membership, not rank, is the condition here.
     * <p>
     * The inbox scoping of WIKI4AI-152 is not changed by this: the inbox stays as
     * strict as it was, and this method stops accepting handoffs that its own scoping
     * would silently drop.
     *
     * @param payload   the request payload, already contract-validated
     * @param projectId the project of the item being commented on, from the access
     *                  check that already loaded it
     * @throws CommentContractException 422: no such actor, an actor that is not
     *                  {@code active}, or an actor with no role in this project
     */
    private void requireHandoffTarget(Map<String, Object> payload, Long projectId) {
        Object raw = payload == null
                ? null
                : payload.get(WorkItemCommentContract.HANDOFF_TARGET_FIELD);
        String handle = ((String) raw).trim().toLowerCase(Locale.ROOT);

        Actor target = actorRepository.findByHandle(handle).orElse(null);
        if (target == null) {
            throw new CommentContractException(WorkItemCommentContract.KIND_HANDOFF, List.of(
                    "'" + WorkItemCommentContract.HANDOFF_TARGET_FIELD + "' names no known actor: '"
                            + handle + "'"));
        }
        if (!Actor.STATUS_ACTIVE.equals(target.getStatus())) {
            throw new CommentContractException(WorkItemCommentContract.KIND_HANDOFF, List.of(
                    "'" + WorkItemCommentContract.HANDOFF_TARGET_FIELD + "' names actor '" + handle
                            + "' whose status is '" + target.getStatus() + "'; a handoff needs an"
                            + " active actor"));
        }
        // WIKI4AI-174: active and existing, but with no row in project_actors this
        // actor's inbox never carries this project's streams — the handoff would be
        // filed where nobody reads it.
        if (projectActorService.getRole(projectId, target.getId()) == null) {
            throw new CommentContractException(WorkItemCommentContract.KIND_HANDOFF, List.of(
                    "'" + WorkItemCommentContract.HANDOFF_TARGET_FIELD + "' names actor '" + handle
                            + "' who is not a member of this project; the handoff would never reach them."
                            + " Add the actor to the project first: POST /api/v1/projects/" + projectId
                            + "/actors with {\"actorId\":" + target.getId() + ",\"role\":\"worker\"}"));
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Payload as JSON TEXT — the same arrangement {@code work_items.labels} uses for
     * its list. Serialised with the application's own {@link ObjectMapper}, so the
     * escaping is the escaping JSON needs. An empty map is stored as null: it carries
     * nothing, and null is the value that means "no payload".
     */
    private String toJson(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            // The comment itself is valid and was accepted; losing its optional
            // detail must not turn a 201 into a 500.
            log.warn("Could not serialise the payload of a work item comment; storing it without one: {}",
                    e.getMessage());
            return null;
        }
    }

    /**
     * The stored JSON TEXT parsed back into the map the DTO carries. Null stays null.
     * A value that is not JSON cannot be represented as a {@code Map<String,Object>}
     * — it can only be written by hand, outside this service — so it is reported in
     * the log and read as no payload rather than failing the read.
     */
    private Map<String, Object> toMap(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(payload, PAYLOAD_TYPE);
        } catch (Exception e) {
            log.warn("Stored comment payload is not JSON; reporting it as absent: {}", e.getMessage());
            return null;
        }
    }

    private WorkItemCommentDTO convertToDTO(WorkItemComment comment) {
        Map<String, Object> payload = toMap(comment.getPayload());
        return WorkItemCommentDTO.builder()
                .id(comment.getId())
                .workItemId(comment.getWorkItemId())
                .authorActorId(comment.getAuthorActorId())
                .kind(comment.getKind())
                .body(comment.getBody())
                .payload(payload == null ? null : new LinkedHashMap<>(payload))
                .createdAt(comment.getCreatedAt())
                .build();
    }
}
