package com.wiki4ai.controller;

import com.wiki4ai.dto.TokenCreateRequestDTO;
import com.wiki4ai.model.Actor;
import com.wiki4ai.model.Role;
import com.wiki4ai.model.User;
import com.wiki4ai.repository.ActorRepository;
import com.wiki4ai.repository.ApiTokenRepository;
import com.wiki4ai.repository.UserRepository;
import com.wiki4ai.service.ApiTokenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end tests for {@code GET /api/v1/actors/whoami} (WIKI4AI-134) with the
 * real security filter chain, the real {@link JwtAuthenticationFilter} and the real
 * {@code ActorService} on H2.
 * <p>
 * Unlike the MockMvc slice in {@code ActorControllerTest}, nothing is mocked here:
 * a token is created through {@link ApiTokenService}, sent as
 * {@code Authorization: Bearer …}, and the answer is whatever the whole stack makes
 * of it. That is what proves the actor reported by whoami is the actor the credential
 * names — the only actor a caller can ever be attributed to.
 * <p>
 * {@code security.enabled=true} puts {@code /api/v1/actors/**} behind
 * {@code anyRequest().authenticated()} in the untouched SecurityConfig.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "security.enabled=true")
// NOT @DirtiesContext, deliberately: every test context in this suite points at the
// same named in-memory database (jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1) and runs with
// ddl-auto=create-drop, so closing one context DROPS THE SCHEMA that the other, still
// open contexts are using — they then fail with "Table ... not found (this database is
// empty)". Verified: adding DirtiesContext here broke ApiTokenServiceTest and
// DocumentDeleteUnscheduleRegressionTest. Isolation is therefore done at the row level
// in cleanState() below, which is order-independent and touches nobody else's schema.
class ActorWhoamiIntegrationTest {

    private static final String ALICE = "whoami_alice";
    private static final String BOB = "whoami_bob";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ActorRepository actorRepository;

    @Autowired
    private ApiTokenRepository apiTokenRepository;

    @Autowired
    private ApiTokenService apiTokenService;

    @Autowired
    private com.wiki4ai.config.JwtUtil jwtUtil;

    private Long aliceAgentActorId;
    private Long bobAgentActorId;

    @BeforeEach
    void setUp() {
        cleanState();
        apiTokenService.clearValidationCache();

        User alice = saveUser(ALICE);
        User bob = saveUser(BOB);
        aliceAgentActorId = saveActor("whoami-alice-agent", alice.getId()).getId();
        bobAgentActorId = saveActor("whoami-bob-agent", bob.getId()).getId();
    }

    /**
     * The class writes users, actors and API tokens into the H2 database the whole
     * test JVM shares ({@code jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1}). Left behind,
     * those rows cannot be removed by anybody else — a user referenced by a token or
     * an actor is undeletable — and that breaks tests whose premise is "this instance
     * has no accounts yet", {@code AuthFirstRunFlowTest} above all.
     * <p>
     * Cleaning up after every test, children first, makes the outcome independent of
     * surefire's run order — which is a filesystem order, and a fresh CI checkout does
     * not list files in the order a local working tree created them.
     */
    @AfterEach
    void cleanUp() {
        cleanState();
    }

    private void cleanState() {
        apiTokenRepository.deleteAll();
        actorRepository.deleteAll();
        for (String name : List.of(ALICE, BOB)) {
            userRepository.findByUsername(name).ifPresent(user -> {
                actorRepository.findByOwnerUserIdOrderByHandleAsc(user.getId())
                        .forEach(actorRepository::delete);
                userRepository.delete(user);
            });
        }
    }

    private User saveUser(String username) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@test.local");
        user.setPassword("not-a-real-hash");
        user.setRole(Role.USER);
        return userRepository.save(user);
    }

    private Actor saveActor(String handle, Long ownerUserId) {
        Actor actor = new Actor();
        actor.setHandle(handle);
        actor.setKind(Actor.KIND_AGENT);
        actor.setOwnerUserId(ownerUserId);
        return actorRepository.save(actor);
    }

    /** Issue a real w4a_ token through the real service and return its plaintext. */
    private String issueToken(String username, Long actorId) {
        return apiTokenService.createToken(
                TokenCreateRequestDTO.builder()
                        .name("whoami test token")
                        .actorId(actorId)
                        .build(),
                username)
                .getToken();
    }

    @Nested
    @DisplayName("GET /api/v1/actors/whoami")
    class Whoami {

        @Test
        @DisplayName("An agent token answers with its own actor, handle and scopes")
        void agentTokenAnswersWithItsActor() throws Exception {
            String token = issueToken(ALICE, aliceAgentActorId);

            mockMvc.perform(get("/api/v1/actors/whoami")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.username").value(ALICE))
                    .andExpect(jsonPath("$.userId").value(userRepository.findByUsername(ALICE).get().getId()))
                    .andExpect(jsonPath("$.actorId").value(aliceAgentActorId))
                    .andExpect(jsonPath("$.handle").value("whoami-alice-agent"))
                    .andExpect(jsonPath("$.kind").value("agent"))
                    .andExpect(jsonPath("$.scopes").isArray())
                    .andExpect(jsonPath("$.scopes[0]").isString());
        }

        @Test
        @DisplayName("A token with no actor answers 200 with actorId and handle null")
        void tokenWithoutActorAnswersWithoutActor() throws Exception {
            String token = issueToken(ALICE, null);

            mockMvc.perform(get("/api/v1/actors/whoami")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.username").value(ALICE))
                    .andExpect(jsonPath("$.actorId", org.hamcrest.Matchers.nullValue()))
                    .andExpect(jsonPath("$.handle", org.hamcrest.Matchers.nullValue()));
        }

        @Test
        @DisplayName("A legacy JWT answers 200 with no actor — never 400, never 500")
        void legacyJwtAnswersWithoutActor() throws Exception {
            String legacyJwt = jwtUtil.generateInfiniteToken(ALICE);

            mockMvc.perform(get("/api/v1/actors/whoami")
                            .header("Authorization", "Bearer " + legacyJwt))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.username").value(ALICE))
                    .andExpect(jsonPath("$.userId").value(userRepository.findByUsername(ALICE).get().getId()))
                    .andExpect(jsonPath("$.actorId", org.hamcrest.Matchers.nullValue()))
                    .andExpect(jsonPath("$.handle", org.hamcrest.Matchers.nullValue()))
                    .andExpect(jsonPath("$.kind", org.hamcrest.Matchers.nullValue()));
        }

        @Test
        @DisplayName("Each caller is told their own actor, never another user's")
        void eachCallerSeesTheirOwnActor() throws Exception {
            String aliceToken = issueToken(ALICE, aliceAgentActorId);
            String bobToken = issueToken(BOB, bobAgentActorId);

            mockMvc.perform(get("/api/v1/actors/whoami")
                            .header("Authorization", "Bearer " + aliceToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.actorId").value(aliceAgentActorId))
                    .andExpect(jsonPath("$.handle").value("whoami-alice-agent"));

            mockMvc.perform(get("/api/v1/actors/whoami")
                            .header("Authorization", "Bearer " + bobToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.actorId").value(bobAgentActorId))
                    .andExpect(jsonPath("$.handle").value("whoami-bob-agent"));
        }

        @Test
        @DisplayName("A body, a query parameter or a header cannot rename the caller")
        void requestInputCannotChangeTheIdentity() throws Exception {
            String token = issueToken(ALICE, aliceAgentActorId);

            mockMvc.perform(get("/api/v1/actors/whoami")
                            .header("Authorization", "Bearer " + token)
                            .header("X-Actor-Id", String.valueOf(bobAgentActorId))
                            .param("actorId", String.valueOf(bobAgentActorId))
                            .param("handle", "whoami-bob-agent"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.actorId").value(aliceAgentActorId))
                    .andExpect(jsonPath("$.handle").value("whoami-alice-agent"));
        }

        @Test
        @DisplayName("whoami is not swallowed by GET /api/v1/actors/{id}")
        void whoamiIsNotMatchedAsAnId() throws Exception {
            String token = issueToken(ALICE, aliceAgentActorId);

            // 'whoami' resolves to the identity endpoint, not to a Long id.
            mockMvc.perform(get("/api/v1/actors/whoami")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.username").value(ALICE));

            // The id route still works for a real id…
            mockMvc.perform(get("/api/v1/actors/" + aliceAgentActorId)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(aliceAgentActorId))
                    .andExpect(jsonPath("$.handle").value("whoami-alice-agent"));

            // …and a non-numeric path segment is still a 400, which is exactly the
            // failure 'whoami' would have produced had the literal path lost.
            mockMvc.perform(get("/api/v1/actors/whoami-ish")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("An unauthenticated caller gets 401")
        void unauthenticatedCallerIsRejected() throws Exception {
            mockMvc.perform(get("/api/v1/actors/whoami"))
                    .andExpect(status().isUnauthorized());
        }
    }
}
