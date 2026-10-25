package com.wiki4ai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wiki4ai.dto.InboxItemDTO;
import com.wiki4ai.dto.InboxResponseDTO;
import com.wiki4ai.dto.UnseenEventsDTO;
import com.wiki4ai.exception.BadRequestException;
import com.wiki4ai.model.Actor;
import com.wiki4ai.model.ProjectActor;
import com.wiki4ai.model.Role;
import com.wiki4ai.model.User;
import com.wiki4ai.model.WorkItem;
import com.wiki4ai.model.WorkItemComment;
import com.wiki4ai.model.WorkItemEvent;
import com.wiki4ai.repository.ActorRepository;
import com.wiki4ai.repository.ProjectActorRepository;
import com.wiki4ai.repository.UserRepository;
import com.wiki4ai.repository.WorkItemCommentRepository;
import com.wiki4ai.repository.WorkItemEventRepository;
import com.wiki4ai.repository.WorkItemRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The inbox of an actor (WIKI4AI-152) and its "what is new" marker (WIKI4AI-153).
 *
 * <h2>The four streams, and what each one is</h2>
 * <table border="1">
 *   <caption>GET /api/v1/actors/{id}/inbox</caption>
 *   <tr><th>stream</th><th>rows</th><th>rule</th></tr>
 *   <tr><td>{@code item}</td><td>{@code work_items}</td>
 *       <td>{@code assignee_actor_id = :id}</td></tr>
 *   <tr><td>{@code comment}</td><td>{@code work_item_comments}</td>
 *       <td>comments of items I created or hold</td></tr>
 *   <tr><td>{@code mention}</td><td>{@code work_item_comments}</td>
 *       <td>{@code kind = 'handoff'} whose payload {@code to_handle} is my handle</td></tr>
 *   <tr><td>{@code released}</td><td>{@code work_item_events}</td>
 *       <td>events naming me as the actor whose claim was taken back</td></tr>
 * </table>
 *
 * <h2>Why the streams are NOT merged by id — the core decision of WIKI4AI-152</h2>
 * The AC says "usporiadane podla monotonneho id". Inside one stream that is exactly what
 * this class does, and it is the right ordering there: the ids of a table are its insertion
 * order, so a stream ordered by id is ordered by the order things were written.
 * <p>
 * Across streams it is meaningless, and merging by id would be a bug rather than a
 * simplification. {@code work_items.id = 5} and {@code work_item_comments.id = 5} are not
 * the same moment: they are two independent sequences counting unrelated things. A comment
 * written today can have id 5 while an item created a month ago has id 500, and merging by
 * id would present them as "5 then 500" — a claim about their order that nothing in the
 * database supports. The only ordering that means something across tables of different
 * ages is the one the rows actually carry: {@code created_at}. So:
 * <ul>
 *   <li><b>inside a stream: {@code id}</b> — monotone, index-backed, and what the keyset
 *   pagination of that stream advances over;</li>
 *   <li><b>across streams: {@code created_at}, with {@code id} only as a tie-break</b> —
 *   a tie-break, not a cross-stream ordering key, and it is there because a
 *   {@code TIMESTAMP(6)} can repeat and a list whose order is not fully determined is a
 *   bug, not a detail.</li>
 * </ul>
 *
 * <h2>Why the cursor is per-stream, and why it is opaque</h2>
 * Four keyset paginations need four positions; one number cannot carry them. Advancing a
 * single global position would either skip rows of the stream that was behind or re-read
 * rows of the stream that was ahead — the classic way a merged cursor loses data. So the
 * cursor carries one position per stream.
 * <p>
 * It is opaque because the client has no business assembling it. The encoding is
 * {@code Base64URL} over the plain text
 * {@code item:<id>,comment:<id>,mention:<id>,released:<id>}: four numbers in a fixed
 * order, nothing else. It is not Java serialization (no class name, no serialVersionUID, no
 * {@code ObjectInputStream} — a serialized object in a query parameter is an
 * deserialisation surface and a format that cannot survive a refactor of the class), it is
 * not SQL (a cursor that is a SQL fragment is an injection surface), and it is not a
 * timestamp (a timestamp cursor cannot be a keyset position). A client that treats it as a
 * string to return works; a client that decodes it is coupling itself to a format this
 * class is free to change, which is why the format is documented here and nowhere in the
 * contract.
 * <p>
 * A cursor that does not decode, does not match the shape, or is absurdly long is a
 * {@link BadRequestException} — 400, not 500. A malformed cursor is a bad request.
 *
 * <h2>Mentions are not a feature; they are handoffs</h2>
 * There is no mention table, no mention column, no {@code @name} syntax in this repository,
 * and this class does not add one. A mention is what WIKI4AI-148/149 already store: a
 * comment of {@code kind = 'handoff'} whose payload carries {@code to_handle}
 * ({@code WorkItemCommentContract.HANDOFF_TARGET_FIELD}), validated at write time to name a
 * real active actor. Reading one back is therefore: select the {@code handoff} comments of
 * the items in scope through the {@code (work_item_id, kind)} index of V22, decode each
 * payload JSON, and compare the whole value of {@code to_handle} with the actor's normalized
 * handle — the same method WIKI4AI-151 used for {@code needs_human}. No {@code LIKE} on the
 * payload TEXT, no new column, no new table, no new syntax.
 *
 * <h2>Which handoffs are in scope, and why</h2>
 * A handoff addressed to me is only worth showing if I am allowed to read the item it is
 * written on. {@code WorkItemService.requireCommentAccess} answers that for one item: a
 * foreign public project is 403, a foreign private one 404. The inbox cannot quietly show
 * what the item's own endpoint refuses, so the candidate set is the items of the projects
 * this actor is a member of ({@code project_actors}), minus the items I created or hold —
 * those are already in the {@code comment} stream, and a handoff on my own item must appear
 * once, not twice.
 * <p>
 * The consequence is stated rather than hidden: a handoff addressed to an actor that has no
 * role in the item's project is not surfaced. That is also the case where the handoff cannot
 * be acted on — acting on an item needs at least {@code worker} there — so the inbox does
 * not hide work that was reachable. Making such a handoff reachable would need a per-actor
 * index over the payload, which means a generated column or a table: a schema decision this
 * story does not own, and V24 is fixed by SPEC to exactly three indexes.
 *
 * <h2>Which events mean "an item slipped away from me"</h2>
 * {@code lease_expired} — the reaper's release of a lease whose TTL passed — and
 * {@code stale_claim_cleared} — the heal sweep's removal of a claim that was sitting on a
 * claim-free state. Those are the two events of WIKI4AI-145 that are produced by a timer and
 * therefore carry the id of the actor the change happened <i>to</i> (see
 * {@code WorkItemEventService}'s event vocabulary). {@code claim_released} is deliberately
 * NOT in the list: its {@code actor_id} is the actor that performed the transition, not the
 * one that lost the item, so filtering it by {@code actor_id = me} would report items I
 * handed over as items I lost. No new event type is invented: both names are constants of
 * {@link WorkItemEvent}.
 *
 * <h2>Constant number of statements, whatever the number of items</h2>
 * Every stream is one repository method. The item-id sets that scope the two comment
 * streams are resolved once per read, never per item, and the titles of the items referenced
 * by the comment, mention and released streams are loaded in one {@code findAllById}. A read
 * of an inbox with one item and a read of an inbox with a hundred items execute the same
 * number of statements — asserted with Hibernate's own statement counter in
 * {@code ActorInboxIntegrationTest}.
 *
 * <h2>The marker of WIKI4AI-153</h2>
 * {@code actors.last_seen_event_id} (V23), nullable, no default. An event is unseen when its
 * id is greater than the marker, and a null marker means "never read", which makes the
 * actor's whole event stream unseen. The marker is advanced only by
 * {@link #markSeen} — a GET that wrote would make the counter useless and would make a
 * retried poll silently acknowledge things. The server, not the client, decides the value:
 * an {@code eventId} in the request is clamped to the highest event id that actually exists
 * in this actor's stream, so no caller can mark events as seen before they exist. See
 * {@link #markSeen}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ActorInboxService {

    /** Default page size of one stream, same shape as every other page in this API. */
    public static final int DEFAULT_LIMIT = 50;

    /** Hard ceiling of one stream's page. No query of this class can ever be larger. */
    public static final int MAX_LIMIT = 50;

    /**
     * Ceiling of the id list {@link #getUnseen} reports. The count is exact; the ids are the
     * oldest ones up to this many, so one response cannot grow without bound.
     */
    public static final int MAX_UNSEEN_EVENT_IDS = 200;

    /** The four streams, in the order their positions appear in a cursor. */
    private static final List<String> STREAMS = List.of(
            InboxItemDTO.STREAM_ITEM, InboxItemDTO.STREAM_COMMENT,
            InboxItemDTO.STREAM_MENTION, InboxItemDTO.STREAM_RELEASED);

    /**
     * The shape of a decoded cursor: four non-negative positions in a fixed order.
     * <p>
     * The digit count is capped at 19 so a cursor cannot smuggle a number too large for a
     * {@code long} past the parser and out of the 400 it deserves.
     */
    private static final Pattern CURSOR_PATTERN = Pattern.compile(
            "^item:(0|[1-9][0-9]{0,18}),comment:(0|[1-9][0-9]{0,18}),"
                    + "mention:(0|[1-9][0-9]{0,18}),released:(0|[1-9][0-9]{0,18})$");

    /** Longest cursor this service will even try to decode. */
    private static final int MAX_CURSOR_LENGTH = 256;

    /**
     * The events that name the actor a claim was taken away from. Both are constants of
     * {@link WorkItemEvent}; neither is invented here.
     */
    private static final Set<String> RELEASED_EVENTS = Set.of(
            WorkItemEvent.EVENT_LEASE_EXPIRED,
            WorkItemEvent.EVENT_STALE_CLAIM_CLEARED);

    private static final TypeReference<Map<String, Object>> PAYLOAD_TYPE =
            new TypeReference<>() {
            };

    private final ActorRepository actorRepository;
    private final UserRepository userRepository;
    private final WorkItemRepository workItemRepository;
    private final WorkItemCommentRepository workItemCommentRepository;
    private final WorkItemEventRepository workItemEventRepository;
    private final ProjectActorRepository projectActorRepository;
    private final RequestActorResolver requestActorResolver;
    private final ObjectMapper objectMapper;

    // ── WIKI4AI-152: the inbox ────────────────────────────────────────────────

    /**
     * One page of an actor's inbox.
     *
     * @param actorId  the actor whose inbox is read
     * @param cursor   the opaque cursor of the previous page, or null for the first page
     * @param limit    page size per stream; null means {@link #DEFAULT_LIMIT}; must be
     *                 1..{@link #MAX_LIMIT}
     * @param username the authenticated caller
     * @return the merged, chronological page and the cursor of the next one
     * @throws EntityNotFoundException  the actor does not exist (404)
     * @throws AccessDeniedException    the caller may not read this actor's inbox (403)
     * @throws BadRequestException      a malformed cursor or a limit outside 1..50 (400)
     */
    public InboxResponseDTO getInbox(Long actorId, String cursor, Integer limit, String username) {
        // Request shape before identity: a malformed request is malformed for everyone, and
        // the order is also what makes the AC-154 proof possible — a request rejected on its
        // limit never reaches the database at all, which the test measures.
        int effectiveLimit = requireLimit(limit);
        Map<String, Long> after = decodeCursor(cursor);
        Actor actor = requireInboxAccess(actorId, username);

        // The two id sets are resolved once per read. They are what keeps the comment
        // streams at one query each instead of one query per item.
        List<Long> myItemIds = workItemRepository.findIdsAssignedOrCreatedBy(actor.getId());
        List<Long> mentionItemIds = mentionScopeIds(actor.getId(), myItemIds);

        List<WorkItem> assigned = workItemRepository.findByAssigneeActorIdAndIdGreaterThanOrderByIdAsc(
                actor.getId(), after.get(InboxItemDTO.STREAM_ITEM), PageRequest.of(0, effectiveLimit));

        List<WorkItemComment> comments = myItemIds.isEmpty()
                ? List.of()
                : workItemCommentRepository.findByWorkItemIdInAndIdGreaterThanOrderByIdAsc(
                        myItemIds, after.get(InboxItemDTO.STREAM_COMMENT), PageRequest.of(0, effectiveLimit));

        List<WorkItemComment> handoffs = mentionItemIds.isEmpty()
                ? List.of()
                : workItemCommentRepository.findByWorkItemIdInAndKindAndIdGreaterThanOrderByIdAsc(
                        mentionItemIds, WorkItemComment.KIND_HANDOFF,
                        after.get(InboxItemDTO.STREAM_MENTION), PageRequest.of(0, effectiveLimit));

        List<WorkItemEvent> released = workItemEventRepository.findByActorIdAndEventInAndIdGreaterThanOrderByIdAsc(
                actor.getId(), RELEASED_EVENTS, after.get(InboxItemDTO.STREAM_RELEASED),
                PageRequest.of(0, effectiveLimit));

        // Titles of the items the other three streams point at, in one query.
        Map<Long, String> titles = titlesOf(referencedItemIds(comments, handoffs, released));

        List<InboxItemDTO> items = new ArrayList<>();
        assigned.forEach(item -> items.add(toItemDTO(item)));
        comments.forEach(comment -> items.add(toCommentDTO(comment, InboxItemDTO.STREAM_COMMENT, titles)));

        // The payload is decoded here and nowhere else, and the whole value is compared:
        // 'to_handle' is a handle, not a substring.
        String handle = normalizeHandle(actor.getHandle());
        handoffs.stream()
                .filter(comment -> addressedTo(comment, handle))
                .forEach(comment -> items.add(toCommentDTO(comment, InboxItemDTO.STREAM_MENTION, titles)));

        released.forEach(event -> items.add(toEventDTO(event, titles)));

        items.sort(Comparator.comparing(InboxItemDTO::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(InboxItemDTO::getId, Comparator.nullsLast(Comparator.naturalOrder())));

        Map<String, Long> next = new LinkedHashMap<>(after);
        next.put(InboxItemDTO.STREAM_ITEM, furthest(assigned, WorkItem::getId, after.get(InboxItemDTO.STREAM_ITEM)));
        next.put(InboxItemDTO.STREAM_COMMENT, furthest(comments, WorkItemComment::getId, after.get(InboxItemDTO.STREAM_COMMENT)));
        // The mention position advances over the handoffs SCANNED, not the ones kept: a
        // handoff addressed to somebody else has still been read, and rewinding past it
        // would read it again on the next poll.
        next.put(InboxItemDTO.STREAM_MENTION, furthest(handoffs, WorkItemComment::getId, after.get(InboxItemDTO.STREAM_MENTION)));
        next.put(InboxItemDTO.STREAM_RELEASED, furthest(released, WorkItemEvent::getId, after.get(InboxItemDTO.STREAM_RELEASED)));

        return InboxResponseDTO.builder()
                .items(items)
                .nextCursor(encodeCursor(next))
                .limit(effectiveLimit)
                .totalElements(items.size())
                .build();
    }

    // ── WIKI4AI-153: what is new, and marking it seen ─────────────────────────

    /**
     * How many events of this actor's stream it has not been shown, and which ones.
     *
     * @param actorId  the actor
     * @param username the authenticated caller
     * @return the count, the oldest unseen ids, the current marker and the newest id of the
     *         stream — the ceiling {@link #markSeen} clamps to
     */
    public UnseenEventsDTO getUnseen(Long actorId, String username) {
        Actor actor = requireInboxAccess(actorId, username);
        Long marker = actor.getLastSeenEventId();
        PageRequest window = PageRequest.of(0, MAX_UNSEEN_EVENT_IDS);

        // NULL is not 0: with no marker every event of this actor is unseen.
        List<WorkItemEvent> unseen = marker == null
                ? workItemEventRepository.findByActorIdAndIdGreaterThanOrderByIdAsc(actor.getId(), 0L, window)
                : workItemEventRepository.findByActorIdAndIdGreaterThanOrderByIdAsc(actor.getId(), marker, window);

        List<Long> ids = new ArrayList<>();
        unseen.forEach(event -> ids.add(event.getId()));

        return UnseenEventsDTO.builder()
                .count(ids.size())
                .eventIds(ids)
                .lastSeenEventId(marker)
                .newestEventId(newestEventIdOf(actor.getId()))
                .build();
    }

    /**
     * Advance the actor's marker.
     * <p>
     * <b>The server decides the value, not the caller.</b> {@code requestedEventId} is
     * clamped to {@link #newestEventIdOf} — the highest id that actually exists in this
     * actor's event stream. Without that clamp a caller could post {@code 999999999} once and
     * spend its whole future inbox acknowledging events it never saw: an agent could silence
     * itself permanently, which is exactly the door a marker must not leave open.
     * <p>
     * The marker also never moves backwards, and a second call with nothing new changes
     * nothing and is not an error — marking seen is an acknowledgement, not a mutation with
     * a precondition.
     *
     * @param actorId          the actor
     * @param requestedEventId what the caller believes it has seen, or null to mean
     *                         "everything there is right now"
     * @param username         the authenticated caller
     */
    @Transactional
    public void markSeen(Long actorId, Long requestedEventId, String username) {
        Actor actor = requireInboxAccess(actorId, username);

        if (requestedEventId != null && requestedEventId < 0) {
            throw new BadRequestException("Parameter 'eventId' must not be negative");
        }

        Long ceiling = newestEventIdOf(actor.getId());
        if (ceiling == null) {
            // This actor has no events at all. There is no id to advance to, and inventing
            // one — 0 in particular — is what the nullability of V23 exists to avoid.
            return;
        }

        long target = requestedEventId == null ? ceiling : Math.min(requestedEventId, ceiling);
        Long current = actor.getLastSeenEventId();
        if (current != null && current >= target) {
            return;
        }
        actor.setLastSeenEventId(target);
        actorRepository.save(actor);
    }

    // ── Access (SPEC rule 7: the rule lives here, once) ────────────────────────

    /**
     * Who may read an actor's inbox. The one place this rule is written.
     * <p>
     * The inbox is the most personal view this API has: it is a list of the work an actor
     * holds, what was said about it, what was handed to it and what it lost. The precedent
     * this follows is {@code WorkItemService.requireEventTimelineAccess} and
     * {@code requireCommentAccess}: a timeline and a comment thread are actor-attributed
     * history and get the strict answer — a foreign public project is 403, not 200 — while a
     * private one is 404. An inbox is the same kind of data seen from the actor's side, so it
     * gets the same strictness, with the actor rather than a project as the thing being
     * protected:
     * <ol>
     *   <li>an actor that does not exist → <b>404</b>. Actors carry no visibility column, so
     *   a foreign actor is never hidden (the same reasoning {@code ActorService.checkOwnership}
     *   states); 404 here means "no such actor", nothing else;</li>
     *   <li>an ADMIN user → <b>200</b> for any actor, as everywhere else in this project;</li>
     *   <li>the actor the verified credential names → <b>200</b>. For an agent that is the
     *   actor its {@code w4a_} token is bound to; for a human it is their own
     *   {@code kind='human'} actor, resolved by {@link RequestActorResolver} and nowhere
     *   else;</li>
     *   <li>a credential that names no actor at all — a legacy JWT, an unbound token →
     *   <b>403</b>. The inbox is per-actor and there is no actor to open it for. This is not
     *   a 404 and not a 200 with an empty body: the caller is authenticated, the inbox is
     *   simply not theirs to read;</li>
     *   <li>any other actor → <b>403</b>. A human who registered several agents reads each
     *   agent's inbox with that agent's token, or as an ADMIN. That is a consequence of the
     *   marker and the cursor being per-actor: a shared inbox would need a shared marker,
     *   and WIKI4AI-153 deliberately has one marker per agent.</li>
     * </ol>
     */
    public Actor requireInboxAccess(Long actorId, String username) {
        if (actorId == null) {
            throw new EntityNotFoundException("Actor not found with id: null");
        }
        Actor actor = actorRepository.findById(actorId)
                .orElseThrow(() -> new EntityNotFoundException("Actor not found with id: " + actorId));

        User user = resolveUser(username);
        if (user != null && user.getRole() == Role.ADMIN) {
            return actor;
        }

        Long callerActorId = requestActorResolver.currentAttributionActorId(username);
        if (callerActorId == null) {
            throw new AccessDeniedException("An inbox belongs to an actor, and this credential"
                    + " names no actor; read it with a token bound to the actor");
        }
        if (!callerActorId.equals(actor.getId())) {
            throw new AccessDeniedException("You can only read the inbox of the actor you are"
                    + " acting as, or of any actor if you are an ADMIN user");
        }
        return actor;
    }

    // ── Cursor ────────────────────────────────────────────────────────────────

    /**
     * Decode the opaque cursor into its four stream positions.
     * <p>
     * Base64URL, no padding, over {@code item:<id>,comment:<id>,mention:<id>,released:<id>}.
     * Every failure — not Base64, not the shape, too long — is a 400 naming 'cursor'.
     */
    private Map<String, Long> decodeCursor(String cursor) {
        Map<String, Long> positions = new LinkedHashMap<>();
        STREAMS.forEach(stream -> positions.put(stream, 0L));
        if (cursor == null || cursor.isBlank()) {
            return positions;
        }
        if (cursor.length() > MAX_CURSOR_LENGTH) {
            throw new BadRequestException("Parameter 'cursor' is too long");
        }

        String decoded;
        try {
            decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Parameter 'cursor' is not a valid inbox cursor");
        }

        Matcher matcher = CURSOR_PATTERN.matcher(decoded);
        if (!matcher.matches()) {
            throw new BadRequestException("Parameter 'cursor' is not a valid inbox cursor");
        }
        List<String> names = List.of(InboxItemDTO.STREAM_ITEM, InboxItemDTO.STREAM_COMMENT,
                InboxItemDTO.STREAM_MENTION, InboxItemDTO.STREAM_RELEASED);
        for (int i = 0; i < names.size(); i++) {
            positions.put(names.get(i), Long.parseLong(matcher.group(i + 1)));
        }
        return positions;
    }

    /** Encode the four positions back into the opaque cursor the client returns. */
    private String encodeCursor(Map<String, Long> positions) {
        StringBuilder raw = new StringBuilder();
        for (String stream : STREAMS) {
            if (!raw.isEmpty()) {
                raw.append(',');
            }
            raw.append(stream).append(':').append(positions.getOrDefault(stream, 0L));
        }
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.toString().getBytes(StandardCharsets.UTF_8));
    }

    // ── limit ─────────────────────────────────────────────────────────────────

    /**
     * Validate the page size. AC of WIKI4AI-152: default 50, ceiling 50, and anything
     * outside 1..50 is a 400 that names 'limit' — 0, -1 and 51 included.
     * <p>
     * The ceiling is then applied a second time as {@link Math#min} when the page is built,
     * so the value {@link #MAX_LIMIT} is the largest page any query of this class can ask
     * for even if a caller is added later that does not go through this method. A request
     * asking for 10000 therefore never reaches a query as 10000 in either path.
     */
    private int requireLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new BadRequestException("Parameter 'limit' must be between 1 and " + MAX_LIMIT
                    + "; got " + limit);
        }
        return Math.min(limit, MAX_LIMIT);
    }

    // ── Scope helpers ─────────────────────────────────────────────────────────

    /**
     * Items whose handoffs may be shown to this actor: the items of the projects this actor
     * is a member of, minus the items it created or holds — those are already in the
     * {@code comment} stream. See the class comment for why membership is the scope.
     */
    private List<Long> mentionScopeIds(Long actorId, List<Long> myItemIds) {
        List<Long> projectIds = new ArrayList<>();
        projectActorRepository.findByActorId(actorId)
                .forEach(assignment -> projectIds.add(assignment.getProjectId()));
        if (projectIds.isEmpty()) {
            return List.of();
        }
        Set<Long> mine = new LinkedHashSet<>(myItemIds);
        List<Long> scoped = new ArrayList<>();
        for (Long itemId : workItemRepository.findIdsOfProjects(projectIds)) {
            if (!mine.contains(itemId)) {
                scoped.add(itemId);
            }
        }
        return scoped;
    }

    /** The highest event id of this actor's stream, or null when it has no events. */
    private Long newestEventIdOf(Long actorId) {
        return workItemEventRepository.findFirstByActorIdOrderByIdDesc(actorId)
                .map(WorkItemEvent::getId)
                .orElse(null);
    }

    private Set<Long> referencedItemIds(List<WorkItemComment> comments,
                                        List<WorkItemComment> handoffs,
                                        List<WorkItemEvent> released) {
        Set<Long> ids = new LinkedHashSet<>();
        comments.forEach(comment -> ids.add(comment.getWorkItemId()));
        handoffs.forEach(comment -> ids.add(comment.getWorkItemId()));
        released.forEach(event -> ids.add(event.getWorkItemId()));
        return ids;
    }

    /** One query for every title the merged page needs. Never one per row. */
    private Map<Long, String> titlesOf(Collection<Long> itemIds) {
        if (itemIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> titles = new LinkedHashMap<>();
        workItemRepository.findAllById(itemIds)
                .forEach(item -> titles.put(item.getId(), item.getTitle()));
        return titles;
    }

    // ── Conversion ────────────────────────────────────────────────────────────

    private InboxItemDTO toItemDTO(WorkItem item) {
        return InboxItemDTO.builder()
                .stream(InboxItemDTO.STREAM_ITEM)
                .id(item.getId())
                .workItemId(item.getId())
                .kind(item.getStatus())
                .title(item.getTitle())
                .body(item.getBody())
                .createdAt(item.getCreatedAt())
                .build();
    }

    private InboxItemDTO toCommentDTO(WorkItemComment comment, String stream, Map<Long, String> titles) {
        return InboxItemDTO.builder()
                .stream(stream)
                .id(comment.getId())
                .workItemId(comment.getWorkItemId())
                .kind(comment.getKind())
                .title(titles.get(comment.getWorkItemId()))
                .body(comment.getBody())
                .payload(decodePayload(comment.getPayload()))
                .createdAt(comment.getCreatedAt())
                .build();
    }

    private InboxItemDTO toEventDTO(WorkItemEvent event, Map<Long, String> titles) {
        return InboxItemDTO.builder()
                .stream(InboxItemDTO.STREAM_RELEASED)
                .id(event.getId())
                .workItemId(event.getWorkItemId())
                .kind(event.getEvent())
                .title(titles.get(event.getWorkItemId()))
                .payload(decodePayload(event.getPayload()))
                .createdAt(event.getCreatedAt())
                .build();
    }

    /**
     * Payload JSON as a map, or null.
     * <p>
     * Null on unparsable stored text rather than an exception: a row written before this
     * class existed is not a bad request now, and an inbox read must not be taken down by
     * one odd row.
     */
    private Map<String, Object> decodePayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(payload, PAYLOAD_TYPE);
        } catch (Exception e) {
            log.warn("Inbox: payload of a work item row is not a JSON object and is reported"
                    + " without it: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Whether a {@code handoff} comment is addressed to this handle.
     * <p>
     * Whole-value comparison of the decoded {@code to_handle} against the actor's normalized
     * handle — the same normalization {@code ActorService} and
     * {@code WorkItemCommentService.requireHandoffTarget} apply, so the two ends of a handoff
     * agree by construction. No pattern matching on the payload TEXT.
     */
    private boolean addressedTo(WorkItemComment comment, String handle) {
        Map<String, Object> payload = decodePayload(comment.getPayload());
        if (payload == null) {
            return false;
        }
        Object raw = payload.get(WorkItemCommentContract.HANDOFF_TARGET_FIELD);
        return raw instanceof String text && normalizeHandle(text).equals(handle);
    }

    private String normalizeHandle(String handle) {
        return handle == null ? null : handle.trim().toLowerCase(Locale.ROOT);
    }

    // ── Small helpers ─────────────────────────────────────────────────────────

    /** The position the next page of this stream starts at: the last row read, or unchanged. */
    private <T> Long furthest(List<T> rows, java.util.function.Function<T, Long> idOf, Long current) {
        Long furthest = current;
        for (T row : rows) {
            Long id = idOf.apply(row);
            if (id != null && (furthest == null || id > furthest)) {
                furthest = id;
            }
        }
        return furthest == null ? 0L : furthest;
    }

    private User resolveUser(String username) {
        if (username == null || username.isBlank()
                || "anonymous".equals(username) || "anonymousUser".equals(username)) {
            return null;
        }
        return userRepository.findByUsername(username).orElse(null);
    }
}
