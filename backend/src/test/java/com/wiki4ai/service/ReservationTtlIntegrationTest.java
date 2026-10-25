package com.wiki4ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wiki4ai.config.JwtAuthenticationFilter.ApiTokenAuthenticationDetails;
import com.wiki4ai.dto.ResourceRequestDTO;
import com.wiki4ai.dto.ReservationDTO;
import com.wiki4ai.dto.ReservationRequestDTO;
import com.wiki4ai.model.Actor;
import com.wiki4ai.model.Reservation;
import com.wiki4ai.model.Role;
import com.wiki4ai.model.User;
import com.wiki4ai.repository.ActorRepository;
import com.wiki4ai.repository.ApiTokenRepository;
import com.wiki4ai.repository.ProjectActorRepository;
import com.wiki4ai.repository.ProjectPermissionRepository;
import com.wiki4ai.repository.ProjectRepository;
import com.wiki4ai.repository.ReservationRepository;
import com.wiki4ai.repository.ResourceRepository;
import com.wiki4ai.repository.UserRepository;
import com.wiki4ai.repository.WorkItemRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC of WIKI4AI-157 — TTL release and manual release — against the real H2 database, the
 * real {@link ReservationReaper}, the real {@link ResourceService} and the real
 * {@link ReservationRepository#release}.
 *
 * <h2>Idempotency is a row guard, not a pre-check</h2>
 * {@code release} carries {@code releasedAt IS NULL} in its WHERE clause. A reservation that
 * is already released matches nothing, so the statement updates 0 rows and the sweep — or
 * the second manual call — decrements nothing. That is why a second release is a 204 that
 * leaves {@code active_slots} exactly where it was, and why two instances sweeping the same
 * expired row cannot both free its slots.
 *
 * <h2>The timer is off, the operation is not</h2>
 * {@code wiki4ai.resources.scheduling-enabled=false} in the test profile keeps
 * {@code @EnableScheduling} out of the test contexts, so no background thread can release a
 * reservation between two assertions in a suite that shares one named in-memory database.
 * The tests call {@link ReservationReaper#releaseExpired()} — the very method the
 * {@code @Scheduled} trigger calls — and assert both halves of the arrangement: that no
 * scheduled-annotation processor bean exists, and that the trigger really is wired to the
 * tested method.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
// NOT @DirtiesContext (SPEC rule 5b).
@DisplayName("WIKI4AI-157: TTL release, manual release, and a reaper that cannot double-count")
class ReservationTtlIntegrationTest {

    private static final String ADMIN = "res157_admin";
    private static final String OWNER = "res157_owner";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private ResourceService resourceService;

    @Autowired
    private ReservationReaper reservationReaper;

    @Autowired
    private ResourceRepository resourceRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private WorkItemRepository workItemRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private ProjectActorRepository projectActorRepository;

    @Autowired
    private ProjectPermissionRepository projectPermissionRepository;

    @Autowired
    private ApiTokenRepository apiTokenRepository;

    @Autowired
    private ActorRepository actorRepository;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        cleanState();

        saveUser(ADMIN, Role.ADMIN);
        saveUser(OWNER, Role.USER);
        saveActor("res157-admin-agent", Actor.KIND_AGENT, ADMIN);
        saveActor("res157-agent", Actor.KIND_AGENT, OWNER);

        authenticate(ADMIN, actorRepository.findByHandle("res157-admin-agent").orElseThrow().getId());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        cleanState();
    }

    /** Children before parents (SPEC rule 5b). */
    private void cleanState() {
        jdbcTemplate.execute("DELETE FROM reservations");
        jdbcTemplate.execute("DELETE FROM work_item_comments");
        jdbcTemplate.execute("DELETE FROM work_item_events");
        workItemRepository.deleteAll();
        projectActorRepository.deleteAll();
        projectPermissionRepository.deleteAll();
        apiTokenRepository.deleteAll();
        actorRepository.deleteAll();
        jdbcTemplate.execute("DELETE FROM resources");
        projectRepository.deleteAll();
        for (String username : List.of(ADMIN, OWNER)) {
            userRepository.findByUsername(username).ifPresent(userRepository::delete);
        }
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private User saveUser(String username, Role role) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@test.local");
        user.setPassword("not-a-real-hash");
        user.setRole(role);
        return userRepository.save(user);
    }

    private Actor saveActor(String handle, String kind, String ownerUsername) {
        Actor actor = new Actor();
        actor.setHandle(handle);
        actor.setKind(kind);
        actor.setOwnerUserId(userRepository.findByUsername(ownerUsername).orElseThrow().getId());
        return actorRepository.save(actor);
    }

    private void authenticate(String username, Long actorId) {
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                username, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        authentication.setDetails(new ApiTokenAuthenticationDetails(1L,
                userRepository.findByUsername(username).orElseThrow().getId(), actorId,
                List.of("read", "write")));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private void createResource(String key, int capacity) throws Exception {
        mockMvc.perform(post("/api/v1/resources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ResourceRequestDTO.builder()
                                .key(key).name("Resource " + key).capacity(capacity).build())))
                .andExpect(status().isCreated());
    }

    private ReservationDTO reserve(String key, int slots) {
        return resourceService.reserve(key, ReservationRequestDTO.builder()
                .slots(String.valueOf(slots)).build(), ADMIN);
    }

    private int activeSlots(String key) {
        return jdbcTemplate.queryForObject(
                "SELECT active_slots FROM resources WHERE \"key\" = ?", Integer.class, key);
    }

    /** Push a reservation's expiry into the past — the state a TTL actually reaches. */
    private void expire(Long reservationId) {
        jdbcTemplate.update("UPDATE reservations SET expires_at = ? WHERE id = ?",
                LocalDateTime.now().minusMinutes(10), reservationId);
    }

    private LocalDateTime releasedAt(Long reservationId) {
        return jdbcTemplate.queryForObject(
                "SELECT released_at FROM reservations WHERE id = ?", LocalDateTime.class, reservationId);
    }

    // ── AC: after the TTL the capacity is free again ──────────────────────────

    @Nested
    @DisplayName("TTL release")
    class Ttl {

        @Test
        void anExpiredReservationIsReleasedAndItsCapacityIsFreeAgain() throws Exception {
            createResource("ttl-seat", 1);
            ReservationDTO reservation = reserve("ttl-seat", 1);
            assertThat(activeSlots("ttl-seat")).isEqualTo(1);

            expire(reservation.getId());
            int released = reservationReaper.releaseExpired();

            assertThat(released).isEqualTo(1);
            assertThat(releasedAt(reservation.getId())).isNotNull();
            assertThat(activeSlots("ttl-seat")).isZero();

            // The freed seat is reservable again — that is what "capacity is free" means.
            mockMvc.perform(post("/api/v1/resources/ttl-seat/reserve")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"slots\":1}"))
                    .andExpect(status().isCreated());
            assertThat(activeSlots("ttl-seat")).isEqualTo(1);
        }

        @Test
        void theSweepLowersActiveSlotsByExactlyTheSlotsOfTheReleasedReservations() throws Exception {
            createResource("ttl-mixed", 10);
            ReservationDTO two = reserve("ttl-mixed", 2);
            ReservationDTO three = reserve("ttl-mixed", 3);
            ReservationDTO live = reserve("ttl-mixed", 4);
            assertThat(activeSlots("ttl-mixed")).isEqualTo(9);

            expire(two.getId());
            expire(three.getId());
            // 'live' is deliberately NOT expired: a sweep must not touch a holding that is
            // still in time, and it must not lower the counter for it either.

            int released = reservationReaper.releaseExpired();

            assertThat(released).isEqualTo(2);
            // 9 - (2 + 3) = 4, i.e. exactly what the one live reservation still holds.
            assertThat(activeSlots("ttl-mixed")).isEqualTo(4);
            assertThat(releasedAt(two.getId())).isNotNull();
            assertThat(releasedAt(three.getId())).isNotNull();
            assertThat(releasedAt(live.getId())).isNull();
        }

        @Test
        void aSecondSweepReleasesNothingAndLowersNothing() throws Exception {
            createResource("ttl-twice", 3);
            ReservationDTO reservation = reserve("ttl-twice", 3);
            expire(reservation.getId());

            assertThat(reservationReaper.releaseExpired()).isEqualTo(1);
            assertThat(activeSlots("ttl-twice")).isZero();

            // Idempotent: the write removed the very condition that made the row a candidate.
            assertThat(reservationReaper.releaseExpired()).isZero();
            assertThat(activeSlots("ttl-twice")).isZero();
        }

        @Test
        void aReservationReleasedByHandIsNotSweptAgain() throws Exception {
            createResource("ttl-manual-then-sweep", 2);
            ReservationDTO reservation = reserve("ttl-manual-then-sweep", 2);
            expire(reservation.getId());

            mockMvc.perform(post("/api/v1/reservations/" + reservation.getId() + "/release"))
                    .andExpect(status().isNoContent());
            assertThat(activeSlots("ttl-manual-then-sweep")).isZero();

            assertThat(reservationReaper.releaseExpired()).isZero();
            assertThat(activeSlots("ttl-manual-then-sweep")).isZero();
        }

        @Test
        void aLiveReservationIsNeverSwept() throws Exception {
            createResource("ttl-live", 2);
            ReservationDTO reservation = reserve("ttl-live", 2);
            assertThat(reservationReaper.releaseExpired()).isZero();
            assertThat(activeSlots("ttl-live")).isEqualTo(2);
            assertThat(releasedAt(reservation.getId())).isNull();
        }

        @Test
        void theSweepReleasesExpiredReservationsOfEveryResourceIndependently() throws Exception {
            createResource("ttl-a", 2);
            createResource("ttl-b", 2);
            ReservationDTO a = reserve("ttl-a", 1);
            ReservationDTO b = reserve("ttl-b", 2);
            expire(a.getId());
            expire(b.getId());

            assertThat(reservationReaper.releaseExpired()).isEqualTo(2);
            assertThat(activeSlots("ttl-a")).isZero();
            assertThat(activeSlots("ttl-b")).isZero();
        }
    }

    // ── AC: manual release is idempotent ──────────────────────────────────────

    @Nested
    @DisplayName("Manual release")
    class Manual {

        @Test
        void releaseIs204AndAFollowUpReleaseIs204WithoutChangingTheCounter() throws Exception {
            createResource("manual-seat", 2);
            ReservationDTO reservation = reserve("manual-seat", 2);
            assertThat(activeSlots("manual-seat")).isEqualTo(2);

            mockMvc.perform(post("/api/v1/reservations/" + reservation.getId() + "/release"))
                    .andExpect(status().isNoContent());
            assertThat(activeSlots("manual-seat")).isZero();
            assertThat(releasedAt(reservation.getId())).isNotNull();

            // Idempotent: 204 again, and active_slots moved 0 times.
            mockMvc.perform(post("/api/v1/reservations/" + reservation.getId() + "/release"))
                    .andExpect(status().isNoContent());
            assertThat(activeSlots("manual-seat")).isZero();
        }

        @Test
        void releasingTwiceFreesTheSeatExactlyOnceForTheNextHolder() throws Exception {
            createResource("manual-next", 1);
            ReservationDTO reservation = reserve("manual-next", 1);

            mockMvc.perform(post("/api/v1/reservations/" + reservation.getId() + "/release"))
                    .andExpect(status().isNoContent());
            mockMvc.perform(post("/api/v1/reservations/" + reservation.getId() + "/release"))
                    .andExpect(status().isNoContent());

            mockMvc.perform(post("/api/v1/resources/manual-next/reserve")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"slots\":1}"))
                    .andExpect(status().isCreated());
            assertThat(activeSlots("manual-next")).isEqualTo(1);
            // Two releases, one seat: the second one must not have handed out the seat twice.
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM reservations WHERE released_at IS NULL", Long.class)).isEqualTo(1);
        }

        @Test
        void releasingAnUnknownReservationIs404NotAQuiet204() throws Exception {
            mockMvc.perform(post("/api/v1/reservations/999999/release"))
                    .andExpect(status().isNotFound());
        }

        @Test
        void theHolderMayReleaseItsOwnReservation() throws Exception {
            createResource("own-release", 2);
            Long ownerAgentId = actorRepository.findByHandle("res157-agent").orElseThrow().getId();
            authenticate(OWNER, ownerAgentId);
            ReservationDTO reservation = resourceService.reserve("own-release",
                    ReservationRequestDTO.builder().slots("1").build(), OWNER);

            mockMvc.perform(post("/api/v1/reservations/" + reservation.getId() + "/release"))
                    .andExpect(status().isNoContent());
            assertThat(activeSlots("own-release")).isZero();
        }

        @Test
        void anotherActorsReservationCannotBeReleasedBySomebodyElse() throws Exception {
            createResource("foreign-release", 2);
            ReservationDTO reservation = reserve("foreign-release", 1);

            Long strangerAgentId = saveActor("res157-stranger-agent", Actor.KIND_AGENT, OWNER).getId();
            authenticate(OWNER, strangerAgentId);
            mockMvc.perform(post("/api/v1/reservations/" + reservation.getId() + "/release"))
                    .andExpect(status().isForbidden());
            assertThat(activeSlots("foreign-release")).isEqualTo(1);
        }

        @Test
        void theReservationRowKeepsItsHistoryAfterARelease() throws Exception {
            createResource("history-row", 1);
            ReservationDTO reservation = reserve("history-row", 1);
            mockMvc.perform(post("/api/v1/reservations/" + reservation.getId() + "/release"))
                    .andExpect(status().isNoContent());

            Reservation stored = reservationRepository.findById(reservation.getId()).orElseThrow();
            assertThat(stored.getReleasedAt()).isNotNull();
            assertThat(stored.getExpiresAt()).isNotNull();
            assertThat(stored.getSlots()).isEqualTo(1);
            // Released rows are kept: the ledger has to be able to say what it held and when it ended.
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM reservations WHERE id = ?", Long.class, reservation.getId()))
                    .isEqualTo(1L);
        }
    }

    // ── The timer is off in the test profile, the operation is not ────────────

    @Nested
    @DisplayName("Scheduling wiring")
    class Scheduling {

        /**
         * The proof that no {@code @Scheduled} method was registered in this context:
         * Spring registers {@code internalScheduledAnnotationProcessor} only when
         * {@code @EnableScheduling} is active, and both reaper switches are false in the test
         * profile. Without this, a background sweep could move a row between two assertions
         * of a suite that shares one named H2 database — the nondeterminism SPEC rule 5b
         * exists to prevent.
         */
        @Test
        void noScheduledAnnotationProcessorBeanExistsInTheTestContext() {
            assertThat(applicationContext
                    .containsBean("org.springframework.context.annotation.internalScheduledAnnotationProcessor"))
                    .as("wiki4ai.resources.scheduling-enabled=false must keep @EnableScheduling out of the test context")
                    .isFalse();
        }

        /**
         * The other half: the reaper itself is present, its trigger is annotated, and the
         * trigger calls the very method these tests call. So the wiring cannot rot silently
         * while the timer is off.
         */
        @Test
        void theReaperBeanIsPresentAndItsScheduledTriggerCallsTheTestedOperation() throws NoSuchMethodException {
            assertThat(applicationContext.getBean(ReservationReaper.class)).isSameAs(reservationReaper);

            Method trigger = ReservationReaper.class.getMethod("sweepExpiredReservations");
            assertThat(trigger.isAnnotationPresent(Scheduled.class))
                    .as("the trigger must stay annotated, or production silently stops sweeping")
                    .isTrue();
            assertThat(trigger.getAnnotation(Scheduled.class).fixedDelayString())
                    .isEqualTo("${wiki4ai.resources.reaper-interval-ms:60000}");

            Method operation = ReservationReaper.class.getMethod("releaseExpired");
            assertThat(operation.getReturnType()).isEqualTo(int.class);
        }

        @Test
        void theOperationIsAvailableAndTestedEvenThoughTheTimerIsOff() throws Exception {
            createResource("sweep-available", 1);
            ReservationDTO reservation = reserve("sweep-available", 1);
            expire(reservation.getId());

            assertThat(reservationReaper.releaseExpired()).isEqualTo(1);
            assertThat(activeSlots("sweep-available")).isZero();
        }
    }
}
