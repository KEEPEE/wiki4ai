package com.wiki4ai.service;

import com.wiki4ai.dto.ResourceDTO;
import com.wiki4ai.dto.ResourceRequestDTO;
import com.wiki4ai.dto.ReservationDTO;
import com.wiki4ai.dto.ReservationRequestDTO;
import com.wiki4ai.exception.BadRequestException;
import com.wiki4ai.model.Actor;
import com.wiki4ai.model.Project;
import com.wiki4ai.model.ProjectActor;
import com.wiki4ai.model.Reservation;
import com.wiki4ai.model.Resource;
import com.wiki4ai.model.Role;
import com.wiki4ai.model.User;
import com.wiki4ai.model.WorkItem;
import com.wiki4ai.repository.ActorRepository;
import com.wiki4ai.repository.ProjectRepository;
import com.wiki4ai.repository.ReservationRepository;
import com.wiki4ai.repository.ResourceRepository;
import com.wiki4ai.repository.UserRepository;
import com.wiki4ai.repository.WorkItemRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The capacity ledger of WIKI4AI-155..157: resources, the atomic reservation of their
 * capacity, and the two ways a holding ends.
 *
 * <h2>Where the rules live</h2>
 * Every rule of this story is enforced here and nowhere else — SPEC rule 7. The
 * controller maps results to statuses, {@code GlobalExceptionHandler} translates the
 * exceptions, and {@code SecurityConfig} is untouched: {@code /api/v1/resources/**} and
 * {@code /api/v1/reservations/**} already fall under its {@code anyRequest().authenticated()}.
 *
 * <h2>Access rule (decided once, here)</h2>
 * A resource is <b>global</b>: it belongs to no project, so the project-role precedent of
 * {@code ProjectActorService.hasAtLeast} cannot be applied to the resource itself. The
 * existing primitives are reused instead of inventing a fourth role table:
 * <ul>
 *   <li><b>create / update / delete a resource</b> → an <b>ADMIN user</b>
 *       ({@code users.role = 'ADMIN'}) <b>or</b> a verified credential whose token carries
 *       the {@code admin} scope ({@link ApiTokenService#SCOPE_ADMIN}). That scope is the
 *       project's existing "administrative" primitive and, by {@code ApiTokenService}'s own
 *       rule, can never be granted to a token bound to an agent actor — so capacity
 *       management stays a human/admin decision, which is the intent of a global ledger.
 *       A new global role concept would be a bigger idea than this story asks for.</li>
 *   <li><b>reserve</b> → the caller must act <b>as an actor</b>: the id resolved from the
 *       verified credential ({@link RequestActorResolver}) must exist and not be archived.
 *       A reservation is per-actor by construction ({@code reservations.actor_id}), so a
 *       credential that names no actor — a legacy JWT whose user has no human actor of
 *       their own — gets 403. When the reservation names a work item, the caller must
 *       additionally have write access to that item's project, decided by exactly the
 *       rule {@code WorkItemService} uses: project owner, ADMIN user, or an actor with at
 *       least the {@code worker} role there.</li>
 *   <li><b>read</b> resources and reservations → any authenticated caller. The ledger is
 *       operational information, not private content; nothing here is scoped to an owner.</li>
 *   <li><b>release</b> → the actor that holds the reservation, or an ADMIN user / an
 *       {@code admin}-scope token (an operator must be able to free a stuck slot).</li>
 * </ul>
 *
 * <h2>The invariant</h2>
 * {@code 0 <= active_slots <= capacity}, and — the part that matters —
 * {@code active_slots == SUM(slots) of the live reservations of that resource}. The
 * counter is a cache of the reservation rows, so the only code that may move it is the
 * pair of guarded UPDATEs in {@code ResourceRepository}, always paired with the statement
 * that moved the rows: {@code reserve} raises it and inserts the reservation in the same
 * transaction; a release lowers it only when its guarded row update returned 1.
 */
@Slf4j
@Service
@Transactional(readOnly = true)
public class ResourceService {

    /** How long a reservation lives unless the instance says otherwise (WIKI4AI-157). */
    private final int reservationTtlMinutes;

    private final ResourceRepository resourceRepository;
    private final ReservationRepository reservationRepository;
    private final UserRepository userRepository;
    private final ActorRepository actorRepository;
    private final ProjectRepository projectRepository;
    private final WorkItemRepository workItemRepository;
    private final ProjectActorService projectActorService;
    private final RequestActorResolver requestActorResolver;

    public ResourceService(ResourceRepository resourceRepository,
                           ReservationRepository reservationRepository,
                           UserRepository userRepository,
                           ActorRepository actorRepository,
                           ProjectRepository projectRepository,
                           WorkItemRepository workItemRepository,
                           ProjectActorService projectActorService,
                           RequestActorResolver requestActorResolver,
                           @Value("${wiki4ai.resources.reservation-ttl-minutes:60}") int reservationTtlMinutes) {
        this.resourceRepository = resourceRepository;
        this.reservationRepository = reservationRepository;
        this.userRepository = userRepository;
        this.actorRepository = actorRepository;
        this.projectRepository = projectRepository;
        this.workItemRepository = workItemRepository;
        this.projectActorService = projectActorService;
        this.requestActorResolver = requestActorResolver;
        this.reservationTtlMinutes = reservationTtlMinutes;
    }

    // ── Resources (WIKI4AI-155) ───────────────────────────────────────────────

    /** Every resource. Reading the ledger is unrestricted. */
    public List<ResourceDTO> list() {
        List<ResourceDTO> dtos = new ArrayList<>();
        for (Resource resource : resourceRepository.findAllByOrderByKeyAsc()) {
            dtos.add(toDTO(resource));
        }
        return dtos;
    }

    /** One resource by its client-facing key. Unknown key → 404. */
    public ResourceDTO get(String key) {
        return toDTO(requireResource(key));
    }

    @Transactional
    public ResourceDTO create(ResourceRequestDTO request, String username) {
        requireResourceAdmin(username, "create");
        if (request == null) {
            throw new BadRequestException("Request body is required");
        }
        String key = requireKey(request.getKey());
        if (resourceRepository.existsByKey(key)) {
            // Same shape as a duplicate actor handle or a duplicate work item external_ref.
            throw new IllegalArgumentException("Resource with key '" + key + "' already exists");
        }
        String name = requireName(request.getName());

        Resource resource = new Resource();
        resource.setKey(key);
        resource.setName(name);
        resource.setKind(normalizeKind(request.getKind()));
        resource.setCapacity(requireCapacity(request.getCapacity(), Resource.DEFAULT_CAPACITY));
        resource.setEnabled(request.getEnabled() == null || request.getEnabled());
        resource.setMetadata(request.getMetadata());
        // activeSlots is never set here: a new resource holds nothing, and the entity has
        // no setter for the counter. @PrePersist initialises it to 0.

        return toDTO(resourceRepository.save(resource));
    }

    /**
     * PATCH a resource. {@code capacity} may go up freely; it may not go below what is
     * currently held, because that would break the invariant through an ordinary update
     * and leave reservations sitting on capacity that no longer exists.
     */
    @Transactional
    public ResourceDTO update(String key, ResourceRequestDTO request, String username) {
        requireResourceAdmin(username, "update");
        if (request == null) {
            throw new BadRequestException("Request body is required");
        }
        Resource resource = requireResource(key);

        if (request.getKey() != null && !request.getKey().isBlank()) {
            String newKey = requireKey(request.getKey());
            if (resourceRepository.existsByKeyAndIdNot(newKey, resource.getId())) {
                throw new IllegalArgumentException("Resource with key '" + newKey + "' already exists");
            }
            resource.setKey(newKey);
        }
        if (request.getName() != null && !request.getName().isBlank()) {
            resource.setName(requireName(request.getName()));
        }
        if (request.getKind() != null && !request.getKind().isBlank()) {
            resource.setKind(normalizeKind(request.getKind()));
        }
        if (request.getMetadata() != null) {
            resource.setMetadata(request.getMetadata());
        }
        if (request.getEnabled() != null) {
            // Disabling stops new reservations; it does not end the held ones. Their slots
            // stay accounted for, exactly as they were.
            resource.setEnabled(request.getEnabled());
        }
        if (request.getCapacity() != null) {
            int capacity = requireCapacity(request.getCapacity(), resource.getCapacity());
            int held = resource.getActiveSlots() == null ? 0 : resource.getActiveSlots();
            if (capacity < held) {
                throw new IllegalArgumentException("Cannot set capacity of resource '" + resource.getKey()
                        + "' to " + capacity + ": " + held + " slot(s) are currently reserved");
            }
            resource.setCapacity(capacity);
        }

        return toDTO(resourceRepository.save(resource));
    }

    /**
     * Delete a resource.
     * <p>
     * <b>Refused (409) while the resource still has unreleased reservations.</b> V25 does
     * carry {@code ON DELETE CASCADE}, so the rows would go — but they would go with a
     * holder still believing it holds them, and a ledger whose rows can vanish under a
     * live holder is not auditable: the reservation, its actor and its reason would be
     * gone before anyone released them. Requiring an explicit release first keeps every
     * holding's end a recorded act. Once nothing is held, the delete is a plain 204.
     * <p>
     * The released history is removed here, in the open, rather than left to the cascade.
     * Two reasons. First, consistency across profiles: the foreign key with
     * {@code ON DELETE CASCADE} exists in V25, i.e. in PostgreSQL, while the test profile
     * builds its schema from the entities — and the entities reference each other by plain
     * ids, exactly like {@code WorkItem} does, so no foreign key is generated there and the
     * cascade the migration promises simply is not there. Second, honesty: a delete whose
     * side effects depend on a database feature one deployment has and another does not is a
     * behaviour that differs by profile. What is deleted, and in which order, is stated in
     * the code that does it.
     */
    @Transactional
    public void delete(String key, String username) {
        requireResourceAdmin(username, "delete");
        Resource resource = requireResource(key);
        long held = reservationRepository.countByResourceIdAndReleasedAtIsNull(resource.getId());
        if (held > 0) {
            throw new IllegalArgumentException("Resource '" + key + "' has " + held
                    + " active reservation(s); release them before deleting the resource");
        }
        // Children before parents, in the open: the released history goes with the resource.
        reservationRepository.deleteAll(
                reservationRepository.findByResourceIdOrderByCreatedAtDesc(resource.getId()));
        resourceRepository.delete(resource);
    }

    // ── Reservations (WIKI4AI-155 read side) ──────────────────────────────────

    /**
     * The holdings of one resource, live and released alike, newest first.
     * Reading is unrestricted — the ledger is operational information.
     */
    public List<ReservationDTO> listReservations(String key) {
        Resource resource = requireResource(key);
        List<ReservationDTO> dtos = new ArrayList<>();
        for (Reservation reservation : reservationRepository.findByResourceIdOrderByCreatedAtDesc(resource.getId())) {
            dtos.add(toDTO(reservation, resource.getKey()));
        }
        return dtos;
    }

    // ── The atomic reservation (WIKI4AI-156) ──────────────────────────────────

    /**
     * Reserve {@code slots} units of one resource.
     * <p>
     * The precondition is not checked in Java. {@code ResourceRepository.reserve} is a
     * single conditional UPDATE whose WHERE clause carries the whole test — the resource
     * is enabled and has room — so two callers racing for the last unit cannot both win.
     * The loser updates 0 rows and is answered 409. A pre-check here would turn the
     * operation into a read-then-write and would be exactly the bug this story is about.
     *
     * @param slotsRaw the raw value from the request body; parsed here, because the
     *                 request DTO carries it as a String (see {@code ReservationRequestDTO})
     * @return the reservation that was created
     */
    @Transactional
    public ReservationDTO reserve(String key, ReservationRequestDTO request, String username) {
        Resource probe = requireResource(key);
        int slots = parseSlots(request == null ? null : request.getSlots());
        String reason = requireReason(request == null ? null : request.getReason());
        Long workItemId = request == null ? null : request.getWorkItemId();

        Long actorId = requireUsableActor(username, "reserve");
        if (workItemId != null) {
            requireWorkItemWrite(workItemId, username, actorId);
        }

        String resourceKey = probe.getKey();
        int updated = resourceRepository.reserve(resourceKey, slots);
        if (updated == 0) {
            // The guard did not hold. Re-read to say WHY, and say the two reasons
            // differently: "you may not reserve from a resource that is switched off"
            // and "there is no room left" are different answers to different questions.
            Resource current = requireResource(resourceKey);
            if (!Boolean.TRUE.equals(current.getEnabled())) {
                throw new IllegalArgumentException("Resource '" + resourceKey + "' is disabled");
            }
            int available = current.availableSlots();
            throw new IllegalArgumentException("Insufficient capacity on resource '" + resourceKey
                    + "': requested " + slots + ", available " + available);
        }

        LocalDateTime now = LocalDateTime.now();
        Reservation reservation = Reservation.builder()
                .resourceId(probe.getId())
                .actorId(actorId)
                .workItemId(workItemId)
                .slots(slots)
                .reason(reason)
                .expiresAt(now.plusMinutes(reservationTtlMinutes))
                .build();
        Reservation saved = reservationRepository.save(reservation);

        // reserve() cleared the persistence context (clearAutomatically), so the counter is
        // re-read rather than trusted from the copy held before the UPDATE. The DTO a
        // caller sees must carry the values the database actually has now.
        Resource after = requireResource(resourceKey);
        log.debug("Reserved {} slot(s) on resource '{}' by actor {}: active_slots {} -> {}",
                slots, resourceKey, actorId, probe.getActiveSlots(), after.getActiveSlots());

        return toDTO(saved, resourceKey);
    }

    // ── Release (WIKI4AI-157) ─────────────────────────────────────────────────

    /**
     * Release one reservation by hand.
     * <p>
     * Idempotent through the row guard, not through a pre-check: {@code release} carries
     * {@code releasedAt IS NULL} in its WHERE clause, so a reservation that is already
     * released matches nothing, the statement updates 0 rows, and nothing is decremented.
     * A second call is therefore a successful no-op (204) that leaves {@code active_slots}
     * exactly where it was. A Java pre-check would be a read-modify-write and would let
     * two racing callers both lower the counter for one reservation.
     * <p>
     * An unknown id is 404 — that is a different fact from "already released", and the
     * caller has to be able to tell them apart.
     */
    @Transactional
    public void releaseReservation(Long reservationId, String username) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new EntityNotFoundException("Reservation not found with id: " + reservationId));

        requireReleaseAccess(reservation, username);

        LocalDateTime now = LocalDateTime.now();
        int updated = reservationRepository.release(reservation.getId(), now);
        if (updated == 0) {
            log.debug("Reservation {} was already released; nothing decremented", reservationId);
            return;
        }
        int slots = reservation.getSlots() == null ? Reservation.DEFAULT_SLOTS : reservation.getSlots();
        int lowered = resourceRepository.decrementSlots(reservation.getResourceId(), slots);
        if (lowered == 0) {
            // Cannot happen while the ledger is consistent, and it must not be silent: the
            // resource row is gone or its counter was already below what this reservation held.
            log.warn("Reservation {} released but resource {} could not lower active_slots by {}; "
                    + "the ledger and the counter disagree", reservationId, reservation.getResourceId(), slots);
        }
    }

    /**
     * One TTL sweep: every unreleased reservation whose {@code expires_at} is in the past
     * stops holding capacity.
     * <p>
     * SELECT candidates, then per-row guarded UPDATE, and the counter is lowered only for
     * the rows whose update returned 1 — the shape {@code WorkItemLeaseReaper} uses, forced
     * by JPQL having no {@code RETURNING}: the sweep needs each released reservation's
     * {@code slots} to lower the counter by exactly that amount, and a bulk UPDATE reports
     * a count instead of a set.
     * <p>
     * Idempotent for the same reason a manual release is: the write removes the very
     * condition that made the row a candidate, so a second sweep finds nothing and lowers
     * nothing. A row whose holder released it a moment earlier is not a candidate at all;
     * a row whose holder extended nothing is released once and once only, even if two
     * instances sweep at the same second.
     *
     * @return how many reservations this sweep released
     */
    @Transactional
    public int releaseExpiredReservations() {
        LocalDateTime now = LocalDateTime.now();
        List<Reservation> candidates = reservationRepository.findExpiredCandidates(now);
        int released = 0;
        for (Reservation candidate : candidates) {
            int updated = reservationRepository.release(candidate.getId(), now);
            if (updated == 0) {
                // Somebody else ended this holding between the SELECT and the UPDATE.
                // Lowering the counter here would be a false fact.
                continue;
            }
            int slots = candidate.getSlots() == null ? Reservation.DEFAULT_SLOTS : candidate.getSlots();
            int lowered = resourceRepository.decrementSlots(candidate.getResourceId(), slots);
            if (lowered == 0) {
                log.warn("Expired reservation {} released but resource {} could not lower active_slots by {}",
                        candidate.getId(), candidate.getResourceId(), slots);
            }
            released++;
        }
        return released;
    }

    // ── Access rules ──────────────────────────────────────────────────────────

    /**
     * Who may change the ledger itself: an ADMIN user, or a credential whose token carries
     * the {@code admin} scope. See the class Javadoc for why these two and nothing else.
     */
    private void requireResourceAdmin(String username, String action) {
        User user = resolveUser(username);
        if (user != null && user.getRole() == Role.ADMIN) {
            return;
        }
        if (requestActorResolver.currentIdentity().scopes().contains(ApiTokenService.SCOPE_ADMIN)) {
            return;
        }
        throw new AccessDeniedException("You can only " + action
                + " resources as an ADMIN user or with a token that carries the '"
                + ApiTokenService.SCOPE_ADMIN + "' scope");
    }

    /**
     * Who may hold capacity: a caller the verified credential resolves to a usable actor.
     * Archived actors are not usable, matching {@code ProjectActorService.hasAtLeast}.
     *
     * @return the actor id the reservation is recorded against
     */
    private Long requireUsableActor(String username, String action) {
        Long actorId = requestActorResolver.currentAttributionActorId(username);
        if (actorId == null) {
            throw new AccessDeniedException("You can only " + action
                    + " capacity as an actor; your credential does not name one");
        }
        Actor actor = actorRepository.findById(actorId)
                .orElseThrow(() -> new AccessDeniedException("You can only " + action
                        + " capacity as an actor; your actor no longer exists"));
        if (Actor.STATUS_ARCHIVED.equals(actor.getStatus())) {
            throw new AccessDeniedException("You can only " + action
                    + " capacity as an actor; actor '" + actor.getHandle() + "' is archived");
        }
        return actorId;
    }

    /**
     * A reservation that names a work item is work on that item's project, so it needs the
     * project's own write rule — the one {@code WorkItemService} applies: project owner,
     * ADMIN user, or an actor with at least the {@code worker} role there.
     */
    private void requireWorkItemWrite(Long workItemId, String username, Long actorId) {
        WorkItem item = workItemRepository.findById(workItemId)
                .orElseThrow(() -> new EntityNotFoundException("Work item not found with id: " + workItemId));
        Project project = projectRepository.findById(item.getProjectId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "Project not found for work item with id: " + workItemId));
        User user = resolveUser(username);
        boolean isOwner = user != null && project.getOwner() != null
                && user.getId() != null && user.getId().equals(project.getOwner().getId());
        boolean isAdmin = user != null && user.getRole() == Role.ADMIN;
        if (isOwner || isAdmin || projectActorService.hasAtLeast(project.getId(), actorId, ProjectActor.ROLE_WORKER)) {
            return;
        }
        throw new AccessDeniedException("You can only reserve capacity for a work item of a project you own,"
                + " a project you administer, or a project where your actor has at least the '"
                + ProjectActor.ROLE_WORKER + "' role");
    }

    /** The holder may release its own holding; an operator may release anybody's. */
    private void requireReleaseAccess(Reservation reservation, String username) {
        User user = resolveUser(username);
        if (user != null && user.getRole() == Role.ADMIN) {
            return;
        }
        if (requestActorResolver.currentIdentity().scopes().contains(ApiTokenService.SCOPE_ADMIN)) {
            return;
        }
        Long actorId = requestActorResolver.currentAttributionActorId(username);
        if (actorId != null && actorId.equals(reservation.getActorId())) {
            return;
        }
        throw new AccessDeniedException("You can only release your own reservations"
                + " (an ADMIN user or an admin-scope token may release any)");
    }

    // ── Validation ────────────────────────────────────────────────────────────

    /**
     * Parse the requested slot count. An absent field means the default of 1; an empty or
     * non-integer value is a 400 whose message names {@code slots}, because that is the field
     * the caller got wrong. "Not a number" and "not given" are different facts, so they are
     * answered differently.
     */
    private int parseSlots(String slotsRaw) {
        if (slotsRaw == null) {
            return Reservation.DEFAULT_SLOTS;
        }
        if (slotsRaw.isBlank()) {
            throw new BadRequestException("slots must be a positive integer (got an empty value)");
        }
        int slots;
        try {
            slots = Integer.parseInt(slotsRaw.trim());
        } catch (NumberFormatException e) {
            throw new BadRequestException("slots must be a positive integer (got '" + slotsRaw + "')");
        }
        if (slots <= 0) {
            throw new BadRequestException("slots must be a positive integer (got " + slots + ")");
        }
        return slots;
    }

    private String requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new BadRequestException("Resource key is required");
        }
        String trimmed = key.trim();
        if (trimmed.length() > 64) {
            throw new BadRequestException("Resource key must be at most 64 characters");
        }
        return trimmed;
    }

    private String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new BadRequestException("Resource name is required");
        }
        String trimmed = name.trim();
        if (trimmed.length() > 128) {
            throw new BadRequestException("Resource name must be at most 128 characters");
        }
        return trimmed;
    }

    private String normalizeKind(String kind) {
        if (kind == null || kind.isBlank()) {
            return Resource.DEFAULT_KIND;
        }
        String trimmed = kind.trim();
        if (trimmed.length() > 32) {
            throw new BadRequestException("Resource kind must be at most 32 characters");
        }
        return trimmed;
    }

    private int requireCapacity(Integer capacity, Integer fallback) {
        if (capacity == null) {
            return fallback == null ? Resource.DEFAULT_CAPACITY : fallback;
        }
        if (capacity < 1) {
            throw new BadRequestException("capacity must be at least 1");
        }
        return capacity;
    }

    private String requireReason(String reason) {
        if (reason == null) {
            return null;
        }
        if (reason.length() > 255) {
            throw new BadRequestException("reason must be at most 255 characters");
        }
        return reason;
    }

    // ── Lookups ───────────────────────────────────────────────────────────────

    private Resource requireResource(String key) {
        if (key == null || key.isBlank()) {
            throw new EntityNotFoundException("Resource not found with key: " + key);
        }
        return resourceRepository.findByKey(key.trim())
                .orElseThrow(() -> new EntityNotFoundException("Resource not found with key: " + key));
    }

    /** Null for anonymous callers — the same convention every other service here uses. */
    private User resolveUser(String username) {
        if (username == null || username.isBlank()
                || "anonymous".equals(username) || "anonymousUser".equals(username)) {
            return null;
        }
        return userRepository.findByUsername(username).orElse(null);
    }

    // ── DTO conversion (manual — no MapStruct, SPEC rule 13) ──────────────────

    private ResourceDTO toDTO(Resource resource) {
        return ResourceDTO.builder()
                .id(resource.getId())
                .key(resource.getKey())
                .name(resource.getName())
                .kind(resource.getKind())
                .capacity(resource.getCapacity())
                .activeSlots(resource.getActiveSlots())
                .enabled(resource.getEnabled())
                .metadata(resource.getMetadata())
                .availableSlots(resource.availableSlots())
                .createdAt(resource.getCreatedAt())
                .build();
    }

    private ReservationDTO toDTO(Reservation reservation, String resourceKey) {
        return ReservationDTO.builder()
                .id(reservation.getId())
                .resourceId(reservation.getResourceId())
                .resourceKey(resourceKey)
                .actorId(reservation.getActorId())
                .workItemId(reservation.getWorkItemId())
                .slots(reservation.getSlots())
                .reason(reservation.getReason())
                .expiresAt(reservation.getExpiresAt())
                .releasedAt(reservation.getReleasedAt())
                .createdAt(reservation.getCreatedAt())
                .build();
    }
}
