package com.wiki4ai.service;

import com.wiki4ai.dto.ActorCreateDTO;
import com.wiki4ai.dto.ActorDTO;
import com.wiki4ai.dto.ActorHeartbeatDTO;
import com.wiki4ai.dto.ActorUpdateDTO;
import com.wiki4ai.exception.BadRequestException;
import com.wiki4ai.model.Actor;
import com.wiki4ai.model.Role;
import com.wiki4ai.model.User;
import com.wiki4ai.repository.ActorRepository;
import com.wiki4ai.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for ActorService against a real database (H2, WIKI4AI-131).
 * <p>
 * Uses @SpringBootTest with the test profile: Flyway is disabled and the schema
 * is created by Hibernate from the entities, so the V15 migration itself is not
 * exercised here (the pipeline verifies it on dev against Postgres).
 */
@SpringBootTest
@ActiveProfiles("test")
class ActorServiceTest {

    private static final String ADMIN = "actor_admin";
    private static final String ALICE = "actor_alice";
    private static final String BOB = "actor_bob";

    @Autowired
    private ActorService actorService;

    @Autowired
    private ActorRepository actorRepository;

    @Autowired
    private UserRepository userRepository;

    private Long adminId;
    private Long aliceId;
    private Long bobId;

    @BeforeEach
    void setUp() {
        // Clean up state from previous tests (the H2 "testdb" is shared across contexts).
        actorRepository.deleteAll();
        for (String name : List.of(ADMIN, ALICE, BOB)) {
            userRepository.findByUsername(name).ifPresent(userRepository::delete);
        }

        adminId = saveUser(ADMIN, Role.ADMIN).getId();
        aliceId = saveUser(ALICE, Role.USER).getId();
        bobId = saveUser(BOB, Role.USER).getId();
    }

    private User saveUser(String username, Role role) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@test.local");
        user.setPassword("not-a-real-hash");
        user.setRole(role);
        return userRepository.save(user);
    }

    private ActorCreateDTO createDto(String handle, String kind, String status) {
        return ActorCreateDTO.builder()
                .handle(handle)
                .kind(kind)
                .status(status)
                .build();
    }

    // ── CRUD ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Actor CRUD")
    class CrudTests {

        @Test
        @DisplayName("Creates an actor with defaults (kind=agent, status=active)")
        void shouldCreateActorWithDefaults() {
            ActorDTO created = actorService.createActor(
                    ActorCreateDTO.builder().handle("agent-coder").build(), ALICE);

            assertThat(created.getId()).isNotNull();
            assertThat(created.getHandle()).isEqualTo("agent-coder");
            assertThat(created.getKind()).isEqualTo(Actor.KIND_AGENT);
            assertThat(created.getStatus()).isEqualTo(Actor.STATUS_ACTIVE);
            assertThat(created.getOwnerUserId()).isEqualTo(aliceId);
            assertThat(created.getCreatedAt()).isNotNull();
            assertThat(created.getUpdatedAt()).isNotNull();
            assertThat(created.getLastSeenAt()).isNull();
        }

        @Test
        @DisplayName("Creates a fully specified actor and stores every field")
        void shouldCreateFullySpecifiedActor() {
            ActorDTO created = actorService.createActor(ActorCreateDTO.builder()
                    .handle("svc-backup")
                    .displayName("Nightly backup service")
                    .kind("service")
                    .systemLabel("wiki4ai-backup")
                    .capabilities("restic,s3")
                    .webhookUrl("https://example.local/hooks/backup")
                    .status("paused")
                    .build(), ALICE);

            assertThat(created.getDisplayName()).isEqualTo("Nightly backup service");
            assertThat(created.getKind()).isEqualTo(Actor.KIND_SERVICE);
            assertThat(created.getSystemLabel()).isEqualTo("wiki4ai-backup");
            assertThat(created.getCapabilities()).isEqualTo("restic,s3");
            assertThat(created.getWebhookUrl()).isEqualTo("https://example.local/hooks/backup");
            assertThat(created.getStatus()).isEqualTo(Actor.STATUS_PAUSED);

            Actor stored = actorRepository.findById(created.getId()).orElseThrow();
            assertThat(stored.getHandle()).isEqualTo("svc-backup");
            assertThat(stored.getOwnerUserId()).isEqualTo(aliceId);
        }

        @Test
        @DisplayName("Reads an actor by id")
        void shouldGetActorById() {
            ActorDTO created = actorService.createActor(createDto("agent-coder", null, null), ALICE);

            ActorDTO fetched = actorService.getActor(created.getId(), BOB);

            assertThat(fetched.getId()).isEqualTo(created.getId());
            assertThat(fetched.getHandle()).isEqualTo("agent-coder");
            // The registry is shared: another user can read it.
            assertThat(fetched.getOwnerUserId()).isEqualTo(aliceId);
        }

        @Test
        @DisplayName("Throws 404 for an unknown actor id")
        void shouldThrowForUnknownId() {
            assertThatThrownBy(() -> actorService.getActor(999_999L, ALICE))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining("999999");
        }

        @Test
        @DisplayName("Lists actors ordered by handle")
        void shouldListActorsOrderedByHandle() {
            actorService.createActor(createDto("zeta-agent", null, null), ALICE);
            actorService.createActor(createDto("alpha-agent", null, null), BOB);

            List<ActorDTO> all = actorService.listActors(null, ALICE);

            assertThat(all).extracting(ActorDTO::getHandle)
                    .containsExactly("alpha-agent", "zeta-agent");
        }

        @Test
        @DisplayName("Filters the list by status")
        void shouldFilterListByStatus() {
            actorService.createActor(createDto("active-one", null, "active"), ALICE);
            actorService.createActor(createDto("paused-one", null, "paused"), ALICE);

            List<ActorDTO> paused = actorService.listActors("PAUSED", BOB);

            assertThat(paused).extracting(ActorDTO::getHandle).containsExactly("paused-one");
        }

        @Test
        @DisplayName("Updates an actor (PATCH-like: only provided fields change)")
        void shouldUpdateOnlyProvidedFields() {
            ActorDTO created = actorService.createActor(ActorCreateDTO.builder()
                    .handle("agent-coder")
                    .displayName("Coder agent")
                    .capabilities("java")
                    .build(), ALICE);

            ActorDTO updated = actorService.updateActor(created.getId(), ActorUpdateDTO.builder()
                    .status("paused")
                    .lastSeenAt(LocalDateTime.of(2026, 9, 15, 10, 0))
                    .build(), ALICE);

            assertThat(updated.getStatus()).isEqualTo(Actor.STATUS_PAUSED);
            assertThat(updated.getLastSeenAt()).isEqualTo(LocalDateTime.of(2026, 9, 15, 10, 0));
            // Untouched fields keep their values.
            assertThat(updated.getDisplayName()).isEqualTo("Coder agent");
            assertThat(updated.getCapabilities()).isEqualTo("java");
            assertThat(updated.getKind()).isEqualTo(Actor.KIND_AGENT);
            assertThat(updated.getOwnerUserId()).isEqualTo(aliceId);
        }

        @Test
        @DisplayName("Renaming an actor to a free handle works")
        void shouldRenameActor() {
            ActorDTO created = actorService.createActor(createDto("old-handle", null, null), ALICE);

            ActorDTO updated = actorService.updateActor(created.getId(),
                    ActorUpdateDTO.builder().handle("NEW-HANDLE").build(), ALICE);

            assertThat(updated.getHandle()).isEqualTo("new-handle");
            assertThat(actorRepository.findByHandle("new-handle")).isPresent();
            assertThat(actorRepository.findByHandle("old-handle")).isEmpty();
        }

        @Test
        @DisplayName("Keeping the same handle in an update is not a conflict")
        void shouldAllowUnchangedHandleOnUpdate() {
            ActorDTO created = actorService.createActor(createDto("agent-coder", null, null), ALICE);

            ActorDTO updated = actorService.updateActor(created.getId(),
                    ActorUpdateDTO.builder().handle("agent-coder").displayName("Renamed").build(), ALICE);

            assertThat(updated.getHandle()).isEqualTo("agent-coder");
            assertThat(updated.getDisplayName()).isEqualTo("Renamed");
        }

        @Test
        @DisplayName("Deletes an actor")
        void shouldDeleteActor() {
            ActorDTO created = actorService.createActor(createDto("agent-coder", null, null), ALICE);

            actorService.deleteActor(created.getId(), ALICE);

            assertThat(actorRepository.findById(created.getId())).isEmpty();
            assertThat(actorService.listActors(null, ALICE)).isEmpty();
        }

        @Test
        @DisplayName("Deleting an unknown actor throws 404")
        void shouldThrowOnUnknownDelete() {
            assertThatThrownBy(() -> actorService.deleteActor(999_999L, ALICE))
                    .isInstanceOf(EntityNotFoundException.class);
        }
    }

    // ── Handle uniqueness (409) ───────────────────────────────────────────────

    @Nested
    @DisplayName("Handle uniqueness")
    class UniquenessTests {

        @Test
        @DisplayName("A duplicate handle is rejected with a conflict error")
        void shouldRejectDuplicateHandle() {
            actorService.createActor(createDto("agent-coder", null, null), ALICE);

            assertThatThrownBy(() -> actorService.createActor(createDto("agent-coder", null, null), BOB))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("agent-coder");

            assertThat(actorRepository.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("Handle comparison is case-insensitive (normalized to lowercase)")
        void shouldRejectDuplicateHandleIgnoringCase() {
            actorService.createActor(createDto("Agent-Coder", null, null), ALICE);

            assertThatThrownBy(() -> actorService.createActor(createDto("AGENT-CODER", null, null), BOB))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("agent-coder");

            // The stored handle is the normalized one.
            assertThat(actorRepository.findByHandle("agent-coder")).isPresent();
        }

        @Test
        @DisplayName("Updating to a handle owned by another actor is a conflict")
        void shouldRejectUpdateToTakenHandle() {
            actorService.createActor(createDto("taken-handle", null, null), BOB);
            ActorDTO mine = actorService.createActor(createDto("my-handle", null, null), ALICE);

            assertThatThrownBy(() -> actorService.updateActor(mine.getId(),
                    ActorUpdateDTO.builder().handle("taken-handle").build(), ALICE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("taken-handle");

            assertThat(actorService.getActor(mine.getId(), ALICE).getHandle()).isEqualTo("my-handle");
        }

        @Test
        @DisplayName("A deleted handle becomes available again")
        void shouldFreeHandleAfterDelete() {
            ActorDTO created = actorService.createActor(createDto("agent-coder", null, null), ALICE);
            actorService.deleteActor(created.getId(), ALICE);

            ActorDTO recreated = actorService.createActor(createDto("agent-coder", null, null), BOB);

            assertThat(recreated.getId()).isNotNull();
            assertThat(recreated.getOwnerUserId()).isEqualTo(bobId);
        }
    }

    // ── Validation (400) ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("Validation")
    class ValidationTests {

        @Test
        @DisplayName("Blank handle is rejected")
        void shouldRejectBlankHandle() {
            assertThatThrownBy(() -> actorService.createActor(createDto("   ", null, null), ALICE))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("handle");
        }

        @Test
        @DisplayName("Handle with illegal characters is rejected")
        void shouldRejectIllegalHandle() {
            assertThatThrownBy(() -> actorService.createActor(createDto("agent coder!", null, null), ALICE))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("letters, digits, dot, dash and underscore");
        }

        @Test
        @DisplayName("Unknown kind is rejected")
        void shouldRejectUnknownKind() {
            assertThatThrownBy(() -> actorService.createActor(createDto("agent-x", "robot", null), ALICE))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Invalid kind");
        }

        @Test
        @DisplayName("Unknown status is rejected on create and on update")
        void shouldRejectUnknownStatus() {
            assertThatThrownBy(() -> actorService.createActor(createDto("agent-x", null, "sleeping"), ALICE))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Invalid status");

            ActorDTO created = actorService.createActor(createDto("agent-x", null, null), ALICE);

            assertThatThrownBy(() -> actorService.updateActor(created.getId(),
                    ActorUpdateDTO.builder().status("sleeping").build(), ALICE))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Invalid status");
        }

        @Test
        @DisplayName("Unknown status filter in the list query is rejected")
        void shouldRejectUnknownStatusFilter() {
            assertThatThrownBy(() -> actorService.listActors("bogus", ALICE))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Invalid status");
        }
    }

    // ── Ownership (enforced in the service, not in SecurityConfig) ────────────

    @Nested
    @DisplayName("Ownership rules")
    class OwnershipTests {

        @Test
        @DisplayName("A non-owner cannot update a foreign actor (403)")
        void shouldRejectForeignUpdate() {
            ActorDTO created = actorService.createActor(createDto("alice-agent", null, null), ALICE);

            assertThatThrownBy(() -> actorService.updateActor(created.getId(),
                    ActorUpdateDTO.builder().status("archived").build(), BOB))
                    .isInstanceOf(AccessDeniedException.class);

            assertThat(actorService.getActor(created.getId(), ALICE).getStatus())
                    .isEqualTo(Actor.STATUS_ACTIVE);
        }

        @Test
        @DisplayName("A non-owner cannot delete a foreign actor (403)")
        void shouldRejectForeignDelete() {
            ActorDTO created = actorService.createActor(createDto("alice-agent", null, null), ALICE);

            assertThatThrownBy(() -> actorService.deleteActor(created.getId(), BOB))
                    .isInstanceOf(AccessDeniedException.class);

            assertThat(actorRepository.findById(created.getId())).isPresent();
        }

        @Test
        @DisplayName("An ADMIN may update and delete anyone's actor")
        void adminMayModifyForeignActor() {
            ActorDTO created = actorService.createActor(createDto("alice-agent", null, null), ALICE);

            ActorDTO updated = actorService.updateActor(created.getId(),
                    ActorUpdateDTO.builder().status("archived").build(), ADMIN);
            assertThat(updated.getStatus()).isEqualTo(Actor.STATUS_ARCHIVED);

            actorService.deleteActor(created.getId(), ADMIN);
            assertThat(actorRepository.findById(created.getId())).isEmpty();
        }

        @Test
        @DisplayName("An unowned system actor may only be modified by an ADMIN")
        void systemActorIsAdminOnly() {
            ActorDTO created = actorService.createActor(createDto("system-agent", "service", null), ALICE);
            // Simulate a built-in actor that has no owner (seeded outside the API).
            Actor stored = actorRepository.findById(created.getId()).orElseThrow();
            stored.setOwnerUserId(null);
            actorRepository.save(stored);

            assertThatThrownBy(() -> actorService.updateActor(created.getId(),
                    ActorUpdateDTO.builder().status("paused").build(), BOB))
                    .isInstanceOf(AccessDeniedException.class);

            assertThat(actorService.updateActor(created.getId(),
                    ActorUpdateDTO.builder().status("paused").build(), ADMIN).getStatus())
                    .isEqualTo(Actor.STATUS_PAUSED);
        }

        @Test
        @DisplayName("An anonymous caller cannot write to the registry")
        void anonymousCallerCannotWrite() {
            assertThatThrownBy(() -> actorService.createActor(createDto("anon-agent", null, null), "anonymous"))
                    .isInstanceOf(AccessDeniedException.class);
        }
    }

    // ── Entity defaults ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("Entity defaults")
    class EntityTests {

        @Test
        @DisplayName("Actor normalizes the handle and defaults kind/status on persist")
        void entityDefaults() {
            Actor actor = new Actor();
            actor.setHandle("  Mixed-Case  ");
            actorRepository.saveAndFlush(actor);

            Optional<Actor> stored = actorRepository.findById(actor.getId());
            assertThat(stored).isPresent();
            assertThat(stored.get().getHandle()).isEqualTo("mixed-case");
            assertThat(stored.get().getKind()).isEqualTo(Actor.KIND_AGENT);
            assertThat(stored.get().getStatus()).isEqualTo(Actor.STATUS_ACTIVE);
            assertThat(stored.get().getCreatedAt()).isNotNull();
            assertThat(stored.get().getUpdatedAt()).isNotNull();
            assertThat(stored.get().isSystemActor()).isTrue();
        }
    }

    // ── Presence heartbeat (WIKI4AI-133) ──────────────────────────────────────

    @Nested
    @DisplayName("Presence heartbeat")
    class HeartbeatTests {

        @Test
        @DisplayName("A new actor starts with presence 'idle' and no note")
        void newActorDefaultsToIdlePresence() {
            ActorDTO created = actorService.createActor(createDto("hb-idle", null, null), ALICE);

            assertThat(created.getPresenceStatus()).isEqualTo(Actor.PRESENCE_IDLE);
            assertThat(created.getPresenceNote()).isNull();
            assertThat(created.getLastSeenAt()).isNull();
        }

        @Test
        @DisplayName("Heartbeat sets last_seen_at, presence status and note")
        void heartbeatRecordsPresence() {
            ActorDTO created = actorService.createActor(createDto("hb-basic", null, null), ALICE);

            actorService.heartbeat(created.getId(), ActorHeartbeatDTO.builder()
                    .status("working")
                    .note("implementing WIKI4AI-133")
                    .build(), ALICE);

            Actor stored = actorRepository.findById(created.getId()).orElseThrow();
            assertThat(stored.getLastSeenAt()).isNotNull();
            assertThat(stored.getPresenceStatus()).isEqualTo(Actor.PRESENCE_WORKING);
            assertThat(stored.getPresenceNote()).isEqualTo("implementing WIKI4AI-133");
        }

        @Test
        @DisplayName("A second heartbeat inside the throttle window changes nothing")
        void secondHeartbeatIsThrottled() {
            ActorDTO created = actorService.createActor(createDto("hb-throttle", null, null), ALICE);

            actorService.heartbeat(created.getId(),
                    ActorHeartbeatDTO.builder().status("working").note("first").build(), ALICE);
            Actor afterFirst = actorRepository.findById(created.getId()).orElseThrow();
            LocalDateTime firstSeenAt = afterFirst.getLastSeenAt();
            String firstPresence = afterFirst.getPresenceStatus();
            String firstNote = afterFirst.getPresenceNote();

            // Immediately after: inside HEARTBEAT_THROTTLE_SECONDS, with a DIFFERENT
            // status and note. Nothing may be written.
            actorService.heartbeat(created.getId(),
                    ActorHeartbeatDTO.builder().status("busy").note("second").build(), ALICE);

            Actor afterSecond = actorRepository.findById(created.getId()).orElseThrow();
            assertThat(afterSecond.getLastSeenAt()).isEqualTo(firstSeenAt);
            assertThat(afterSecond.getPresenceStatus()).isEqualTo(firstPresence);
            assertThat(afterSecond.getPresenceNote()).isEqualTo(firstNote);
        }

        @Test
        @DisplayName("A heartbeat after the throttle window writes again")
        void heartbeatWritesAgainAfterThrottleWindow() {
            ActorDTO created = actorService.createActor(createDto("hb-expired", null, null), ALICE);
            LocalDateTime stale = LocalDateTime.now().minusSeconds(
                    ActorService.HEARTBEAT_THROTTLE_SECONDS + 15);
            actorService.updateActor(created.getId(),
                    ActorUpdateDTO.builder().lastSeenAt(stale).build(), ALICE);

            actorService.heartbeat(created.getId(),
                    ActorHeartbeatDTO.builder().status("busy").note("fresh").build(), ALICE);

            Actor stored = actorRepository.findById(created.getId()).orElseThrow();
            assertThat(stored.getLastSeenAt()).isAfter(stale);
            assertThat(stored.getPresenceStatus()).isEqualTo(Actor.PRESENCE_BUSY);
            assertThat(stored.getPresenceNote()).isEqualTo("fresh");
        }

        @Test
        @DisplayName("A bare heartbeat keeps the last reported presence unchanged")
        void bareHeartbeatKeepsPresence() {
            ActorDTO created = actorService.createActor(createDto("hb-bare", null, null), ALICE);
            actorService.heartbeat(created.getId(),
                    ActorHeartbeatDTO.builder().status("busy").note("keep me").build(), ALICE);

            LocalDateTime firstSeenAt = actorRepository.findById(created.getId())
                    .orElseThrow().getLastSeenAt();
            LocalDateTime stale = firstSeenAt.minusSeconds(ActorService.HEARTBEAT_THROTTLE_SECONDS + 5);
            actorService.updateActor(created.getId(),
                    ActorUpdateDTO.builder().lastSeenAt(stale).build(), ALICE);

            actorService.heartbeat(created.getId(), null, ALICE);

            Actor stored = actorRepository.findById(created.getId()).orElseThrow();
            assertThat(stored.getLastSeenAt()).isAfter(stale);
            assertThat(stored.getPresenceStatus()).isEqualTo(Actor.PRESENCE_BUSY);
            assertThat(stored.getPresenceNote()).isEqualTo("keep me");
        }

        @Test
        @DisplayName("An unknown presence status is rejected")
        void heartbeatRejectsUnknownPresenceStatus() {
            ActorDTO created = actorService.createActor(createDto("hb-badstatus", null, null), ALICE);

            assertThatThrownBy(() -> actorService.heartbeat(created.getId(),
                    ActorHeartbeatDTO.builder().status("alien").build(), ALICE))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Invalid presence status");

            Actor stored = actorRepository.findById(created.getId()).orElseThrow();
            assertThat(stored.getLastSeenAt()).isNull();
            assertThat(stored.getPresenceStatus()).isEqualTo(Actor.PRESENCE_IDLE);
        }

        @Test
        @DisplayName("A note longer than 256 characters is rejected")
        void heartbeatRejectsOverlongNote() {
            ActorDTO created = actorService.createActor(createDto("hb-longnote", null, null), ALICE);

            assertThatThrownBy(() -> actorService.heartbeat(created.getId(),
                    ActorHeartbeatDTO.builder().note("x".repeat(257)).build(), ALICE))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("at most 256 characters");

            assertThat(actorRepository.findById(created.getId()).orElseThrow().getLastSeenAt()).isNull();
        }

        @Test
        @DisplayName("An archived actor cannot send heartbeats")
        void heartbeatRejectsArchivedActor() {
            ActorDTO created = actorService.createActor(createDto("hb-archived", null, null), ALICE);
            actorService.updateActor(created.getId(),
                    ActorUpdateDTO.builder().status("archived").build(), ALICE);

            assertThatThrownBy(() -> actorService.heartbeat(created.getId(),
                    ActorHeartbeatDTO.builder().status("working").build(), ALICE))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("archived");
        }

        @Test
        @DisplayName("A heartbeat for an unknown actor throws 404")
        void heartbeatUnknownActorIsNotFound() {
            assertThatThrownBy(() -> actorService.heartbeat(999_999L, null, ALICE))
                    .isInstanceOf(EntityNotFoundException.class);
        }

        @Test
        @DisplayName("A heartbeat for a foreign actor is forbidden (403)")
        void heartbeatForeignActorIsForbidden() {
            ActorDTO created = actorService.createActor(createDto("hb-alice", null, null), ALICE);

            assertThatThrownBy(() -> actorService.heartbeat(created.getId(),
                    ActorHeartbeatDTO.builder().status("working").build(), BOB))
                    .isInstanceOf(AccessDeniedException.class);

            assertThat(actorRepository.findById(created.getId()).orElseThrow().getLastSeenAt()).isNull();
        }

        @Test
        @DisplayName("An ADMIN may heartbeat anyone's actor")
        void adminMayHeartbeatForeignActor() {
            ActorDTO created = actorService.createActor(createDto("hb-alice-admin", null, null), ALICE);

            actorService.heartbeat(created.getId(),
                    ActorHeartbeatDTO.builder().status("busy").build(), ADMIN);

            assertThat(actorRepository.findById(created.getId()).orElseThrow().getLastSeenAt()).isNotNull();
        }

        @Test
        @DisplayName("An anonymous caller cannot send a heartbeat")
        void anonymousCallerCannotHeartbeat() {
            ActorDTO created = actorService.createActor(createDto("hb-anon", null, null), ALICE);

            assertThatThrownBy(() -> actorService.heartbeat(created.getId(), null, "anonymous"))
                    .isInstanceOf(AccessDeniedException.class);
        }
    }
}
