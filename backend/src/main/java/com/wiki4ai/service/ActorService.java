package com.wiki4ai.service;

import com.wiki4ai.dto.ActorCreateDTO;
import com.wiki4ai.dto.ActorDTO;
import com.wiki4ai.dto.ActorHeartbeatDTO;
import com.wiki4ai.dto.ActorUpdateDTO;
import com.wiki4ai.dto.ActorWhoamiDTO;
import com.wiki4ai.exception.BadRequestException;
import com.wiki4ai.model.Actor;
import com.wiki4ai.model.Role;
import com.wiki4ai.model.User;
import com.wiki4ai.repository.ActorRepository;
import com.wiki4ai.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Service layer for the actor registry (WIKI4AI-131).
 * <p>
 * Handles CRUD of actors, handle normalization and uniqueness (a duplicate
 * handle is a 409 Conflict), validation of {@code kind} and {@code status}
 * (400 Bad Request) and the ownership rules.
 * <p>
 * Ownership is enforced HERE, not in SecurityConfig: the JWT filter grants
 * ROLE_USER to everyone, so role checks in the filter chain are useless.
 * Only the owning user or an ADMIN may modify or delete an actor; anyone else
 * gets 403 Forbidden. Actors are a shared registry, so reading is open to
 * every authenticated caller.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ActorService {

    /**
     * Heartbeat throttle window in seconds (WIKI4AI-133). A heartbeat arriving
     * less than this many seconds after the previous one performs NO write at
     * all — not even the presence fields — so a chatty agent cannot flood the
     * database. Kept as a named constant on purpose: tuning the presence
     * granularity of the coordination layer must be a one-line change.
     */
    public static final long HEARTBEAT_THROTTLE_SECONDS = 30;

    private final ActorRepository actorRepository;
    private final UserRepository userRepository;

    // ── Current-user resolution ───────────────────────────────────────────────

    /**
     * Resolve the requesting user from the authenticated username.
     * Returns null for anonymous callers (test mode with security disabled);
     * in production every actor endpoint requires a valid JWT.
     */
    private User resolveUser(String username) {
        if (username == null || username.isBlank()
                || "anonymous".equals(username) || "anonymousUser".equals(username)) {
            return null;
        }
        return userRepository.findByUsername(username).orElse(null);
    }

    /**
     * Resolve the requesting user or reject the request (403) when no
     * authenticated user can be resolved. Used by write operations that need
     * a concrete owner.
     */
    private User requireUser(String username) {
        User user = resolveUser(username);
        if (user == null) {
            throw new AccessDeniedException("Authentication required");
        }
        return user;
    }

    // ── Actors ────────────────────────────────────────────────────────────────

    /**
     * List actors ordered by handle. The optional status filter accepts
     * 'active', 'paused' or 'archived' (case-insensitive); an unknown status
     * is rejected with 400 so a typo cannot silently return an empty list.
     */
    public List<ActorDTO> listActors(String status, String username) {
        resolveUser(username);

        List<Actor> actors;
        if (status == null || status.isBlank()) {
            actors = actorRepository.findAllByOrderByHandleAsc();
        } else {
            // normalizeStatus validates the value (400 for an unknown status)
            // and returns it lowercased, so a typo cannot silently return an
            // empty list.
            actors = actorRepository.findByStatusOrderByHandleAsc(normalizeStatus(status));
        }

        return actors.stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    /**
     * Get a single actor by id. Actors are a shared registry, so any
     * authenticated caller may read any actor.
     */
    public ActorDTO getActor(Long id, String username) {
        resolveUser(username);
        return convertToDTO(requireActor(id));
    }

    /**
     * Who the caller is (WIKI4AI-134) — the answer of {@code GET /api/v1/actors/whoami}.
     * <p>
     * Everything in the answer comes from the verified credential and the user it
     * belongs to; nothing is read from the request body, a query parameter or a
     * header. The identity itself is resolved by {@link RequestActorResolver} and
     * passed in, so this method has no hidden dependency on the security context
     * and is testable as a plain unit call.
     * <p>
     * Rules:
     * <ul>
     *   <li>a token bound to an actor → that actor is reported with its
     *       {@code handle} and {@code kind},</li>
     *   <li>a credential that names no actor (legacy JWT, unbound token) →
     *       {@code actorId} and {@code handle} are null. That is a normal 200
     *       answer for a human caller, not an error,</li>
     *   <li>an actor id that no longer resolves (the registry row was deleted) →
     *       nulls as well, never a 404: whoami describes the caller, it does not
     *       look up registry entries,</li>
     *   <li>the scopes listed are the token's scopes — empty for a legacy JWT.</li>
     * </ul>
     * whoami deliberately does NOT invent an actor for a human: it reports what
     * the credential says. Attribution of a human's writes through their own
     * human actor is a separate rule, applied by
     * {@link RequestActorResolver#currentAttributionActorId(String)} when a
     * document is written.
     */
    public ActorWhoamiDTO whoami(String username, RequestActorResolver.CallerIdentity identity) {
        RequestActorResolver.CallerIdentity effective =
                identity != null ? identity : RequestActorResolver.CallerIdentity.none();

        User user = resolveUser(username);
        Long userId = user != null ? user.getId() : effective.userId();
        String resolvedUsername = user != null && user.getUsername() != null ? user.getUsername() : username;

        Long actorId = effective.actorId();
        String handle = null;
        String kind = null;
        if (actorId != null) {
            Actor actor = actorRepository.findById(actorId).orElse(null);
            if (actor != null) {
                handle = actor.getHandle();
                kind = actor.getKind();
            } else {
                // Dangling reference: report "no actor" rather than failing.
                actorId = null;
            }
        }

        return ActorWhoamiDTO.builder()
                .userId(userId)
                .username(resolvedUsername)
                .actorId(actorId)
                .handle(handle)
                .kind(kind)
                .scopes(effective.scopes())
                .build();
    }

    /**
     * Create a new actor. The owner is the authenticated caller.
     * Validation: handle is required and well-formed (400), kind and status
     * must be one of the allowed values (400), a handle that is already taken
     * is rejected with 409 Conflict.
     */
    @Transactional
    public ActorDTO createActor(ActorCreateDTO dto, String username) {
        User user = requireUser(username);

        String handle = normalizeHandle(dto.getHandle());
        if (actorRepository.existsByHandle(handle)) {
            // GlobalExceptionHandler maps IllegalArgumentException -> 409 Conflict.
            throw new IllegalArgumentException("An actor with handle '" + handle + "' already exists");
        }

        String kind = normalizeKind(dto.getKind());
        String status = normalizeStatus(dto.getStatus());

        Actor actor = new Actor();
        actor.setHandle(handle);
        actor.setDisplayName(trimToNull(dto.getDisplayName()));
        actor.setKind(kind);
        actor.setStatus(status);
        actor.setSystemLabel(trimToNull(dto.getSystemLabel()));
        actor.setCapabilities(trimToNull(dto.getCapabilities()));
        actor.setWebhookUrl(trimToNull(dto.getWebhookUrl()));
        actor.setOwnerUserId(user.getId());

        return convertToDTO(saveActor(actor));
    }

    /**
     * Update an existing actor (PATCH-like: null fields are not changed).
     * Only the owning user or an ADMIN may update; anyone else gets 403
     * Forbidden. Renaming to a handle that another actor already owns is a
     * 409 Conflict.
     */
    @Transactional
    public ActorDTO updateActor(Long id, ActorUpdateDTO dto, String username) {
        User user = requireUser(username);
        Actor actor = requireActor(id);
        checkOwnership(actor, user, "update");

        if (dto.getHandle() != null && !dto.getHandle().isBlank()) {
            String handle = normalizeHandle(dto.getHandle());
            if (!handle.equals(actor.getHandle()) && actorRepository.existsByHandle(handle)) {
                // GlobalExceptionHandler maps IllegalArgumentException -> 409 Conflict.
                throw new IllegalArgumentException("An actor with handle '" + handle + "' already exists");
            }
            actor.setHandle(handle);
        }
        if (dto.getDisplayName() != null) {
            actor.setDisplayName(trimToNull(dto.getDisplayName()));
        }
        if (dto.getKind() != null && !dto.getKind().isBlank()) {
            actor.setKind(normalizeKind(dto.getKind()));
        }
        if (dto.getSystemLabel() != null) {
            actor.setSystemLabel(trimToNull(dto.getSystemLabel()));
        }
        if (dto.getCapabilities() != null) {
            actor.setCapabilities(trimToNull(dto.getCapabilities()));
        }
        if (dto.getWebhookUrl() != null) {
            actor.setWebhookUrl(trimToNull(dto.getWebhookUrl()));
        }
        if (dto.getLastSeenAt() != null) {
            actor.setLastSeenAt(dto.getLastSeenAt());
        }
        if (dto.getStatus() != null && !dto.getStatus().isBlank()) {
            actor.setStatus(normalizeStatus(dto.getStatus()));
        }

        return convertToDTO(saveActor(actor));
    }

    /**
     * Delete an actor. Only the owning user or an ADMIN may delete; anyone else
     * gets 403 Forbidden.
     */
    @Transactional
    public void deleteActor(Long id, String username) {
        User user = requireUser(username);
        Actor actor = requireActor(id);
        checkOwnership(actor, user, "delete");
        actorRepository.delete(actor);
    }

    // ── Presence heartbeat (WIKI4AI-133) ──────────────────────────────────────

    /**
     * Record that an actor is alive, optionally with its presence status and a
     * note. Called by POST /api/v1/actors/{id}/heartbeat.
     * <p>
     * Rules:
     * <ul>
     *   <li>unknown actor → 404,</li>
     *   <li>caller is neither the actor's owner nor an ADMIN → 403 (exactly the
     *       {@link #checkOwnership} rule used by update/delete),</li>
     *   <li>archived actor → 400 (archived actors are history, not presence),</li>
     *   <li>invalid presence status or a note longer than 256 characters → 400,</li>
     *   <li><b>throttle:</b> when {@code last_seen_at} is younger than
     *       {@link #HEARTBEAT_THROTTLE_SECONDS} seconds, NOTHING is written —
     *       neither last_seen_at nor presence_status nor presence_note. Two calls
     *       in a row produce exactly one write.</li>
     * </ul>
     * The throttle decision is not a signal for the client: the caller gets 204
     * No Content either way (decided by the controller).
     * <p>
     * PATCH-like semantics on the write: a null field leaves the current value
     * unchanged, so a bare "I am alive" ping keeps the last reported presence.
     * <p>
     * The {@code username} overload is the HTTP entry point (every other method
     * of this service takes the authenticated username); the {@code caller}
     * overload is the implementation and is what later stories — API tokens that
     * already resolve an actor and its user — will call directly.
     */
    @Transactional
    public void heartbeat(Long actorId, ActorHeartbeatDTO dto, String username) {
        heartbeat(actorId, requireUser(username), dto);
    }

    /**
     * Heartbeat for an already resolved caller. See the overload above for the
     * full rule set; a null caller is rejected with 403 like an anonymous one.
     */
    @Transactional
    public void heartbeat(Long actorId, User caller, ActorHeartbeatDTO dto) {
        if (caller == null) {
            throw new AccessDeniedException("Authentication required");
        }
        Actor actor = requireActor(actorId);
        checkOwnership(actor, caller, "update");

        if (Actor.STATUS_ARCHIVED.equals(actor.getStatus())) {
            throw new BadRequestException(
                    "Actor '" + actor.getHandle() + "' is archived and cannot send heartbeats");
        }

        String presenceStatus = dto == null ? null : normalizePresenceStatus(dto.getStatus());
        String presenceNote = dto == null ? null : validatePresenceNote(dto.getNote());

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime lastSeen = actor.getLastSeenAt();
        if (lastSeen != null && lastSeen.isAfter(now.minusSeconds(HEARTBEAT_THROTTLE_SECONDS))) {
            // Inside the throttle window: deliberately no save() at all.
            return;
        }

        actor.setLastSeenAt(now);
        if (presenceStatus != null) {
            actor.setPresenceStatus(presenceStatus);
        }
        if (presenceNote != null) {
            // trimToNull: an explicitly blank note clears the previous one.
            actor.setPresenceNote(trimToNull(presenceNote));
        }
        actorRepository.save(actor);
    }

    // ── Access and lookup helpers ─────────────────────────────────────────────

    /**
     * Load an actor or fail with 404 Not Found.
     */
    private Actor requireActor(Long id) {
        return actorRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Actor not found with id: " + id));
    }

    /**
     * Enforce the ownership rule for update/delete. Only the actor's owner or
     * an ADMIN may modify the actor. Actors carry no visibility column, so a
     * foreign actor is always visible (403), never hidden (404). An unowned
     * system actor can only be modified by an ADMIN.
     */
    private void checkOwnership(Actor actor, User user, String action) {
        boolean isAdmin = user.getRole() == Role.ADMIN;
        boolean isOwner = user.getId() != null && user.getId().equals(actor.getOwnerUserId());
        if (isAdmin || isOwner) {
            return;
        }
        throw new AccessDeniedException(
                "You can only " + action + " actors that you own");
    }

    /**
     * Save the actor, translating a UNIQUE-constraint violation into the same
     * 409 shape the pre-check produces. The pre-check covers the normal case;
     * this covers two concurrent creates racing for the same handle.
     */
    private Actor saveActor(Actor actor) {
        try {
            return actorRepository.save(actor);
        } catch (DataIntegrityViolationException e) {
            // GlobalExceptionHandler maps IllegalArgumentException -> 409 Conflict.
            throw new IllegalArgumentException(
                    "An actor with handle '" + actor.getHandle() + "' already exists");
        }
    }

    // ── Validation helpers ────────────────────────────────────────────────────

    /**
     * Normalize a handle: trim + lowercase. Lowercasing is what makes the case
     * sensitive DB UNIQUE constraint and the service-level duplicate check
     * agree, so "Agent-Coder" and "agent-coder" cannot both exist.
     */
    private String normalizeHandle(String handle) {
        if (handle == null || handle.isBlank()) {
            throw new BadRequestException("Actor handle is required");
        }
        String normalized = handle.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 64) {
            throw new BadRequestException("Actor handle must be at most 64 characters");
        }
        if (!normalized.matches("[a-z0-9._-]+")) {
            throw new BadRequestException(
                    "Actor handle may contain only letters, digits, dot, dash and underscore");
        }
        return normalized;
    }

    /**
     * Normalize and validate the kind ('agent' | 'human' | 'service');
     * null/blank defaults to 'agent'.
     */
    private String normalizeKind(String kind) {
        if (kind == null || kind.isBlank()) {
            return Actor.KIND_AGENT;
        }
        String normalized = kind.trim().toLowerCase(Locale.ROOT);
        if (!Actor.KIND_AGENT.equals(normalized)
                && !Actor.KIND_HUMAN.equals(normalized)
                && !Actor.KIND_SERVICE.equals(normalized)) {
            throw new BadRequestException(
                    "Invalid kind '" + kind + "'. Allowed values: 'agent', 'human', 'service'");
        }
        return normalized;
    }

    /**
     * Normalize and validate the status ('active' | 'paused' | 'archived');
     * null/blank defaults to 'active'.
     */
    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return Actor.STATUS_ACTIVE;
        }
        String normalized = status.trim().toLowerCase(Locale.ROOT);
        if (!Actor.STATUS_ACTIVE.equals(normalized)
                && !Actor.STATUS_PAUSED.equals(normalized)
                && !Actor.STATUS_ARCHIVED.equals(normalized)) {
            throw new BadRequestException(
                    "Invalid status '" + status + "'. Allowed values: 'active', 'paused', 'archived'");
        }
        return normalized;
    }

    /**
     * Normalize and validate the PRESENCE status ('idle' | 'working' | 'busy')
     * reported by a heartbeat (WIKI4AI-133). null/blank means "no change" and
     * returns null — unlike {@link #normalizeStatus}, which has a default, the
     * presence of an actor is only ever what the actor last reported.
     */
    private String normalizePresenceStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String normalized = status.trim().toLowerCase(Locale.ROOT);
        if (!Actor.PRESENCE_IDLE.equals(normalized)
                && !Actor.PRESENCE_WORKING.equals(normalized)
                && !Actor.PRESENCE_BUSY.equals(normalized)) {
            throw new BadRequestException(
                    "Invalid presence status '" + status + "'. Allowed values: 'idle', 'working', 'busy'");
        }
        return normalized;
    }

    /**
     * Validate the presence note of a heartbeat: null stays null (no change),
     * anything longer than the 256-character column is a 400 Bad Request. The
     * request body carries the same {@code @Size} limit; this is the service-side
     * guard for callers that build the DTO directly.
     */
    private String validatePresenceNote(String note) {
        if (note == null) {
            return null;
        }
        if (note.length() > 256) {
            throw new BadRequestException("Presence note must be at most 256 characters");
        }
        return note;
    }

    /**
     * Trim a nullable text field, mapping a blank value to null.
     */
    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    // ── DTO conversion (manual — this project does not use MapStruct) ─────────

    /**
     * Convert an Actor entity to its DTO.
     */
    private ActorDTO convertToDTO(Actor actor) {
        return ActorDTO.builder()
                .id(actor.getId())
                .handle(actor.getHandle())
                .displayName(actor.getDisplayName())
                .kind(actor.getKind())
                .ownerUserId(actor.getOwnerUserId())
                .systemLabel(actor.getSystemLabel())
                .capabilities(actor.getCapabilities())
                .webhookUrl(actor.getWebhookUrl())
                .lastSeenAt(actor.getLastSeenAt())
                .status(actor.getStatus())
                .presenceStatus(actor.getPresenceStatus())
                .presenceNote(actor.getPresenceNote())
                .createdAt(actor.getCreatedAt())
                .updatedAt(actor.getUpdatedAt())
                .build();
    }
}
