package com.wiki4ai.service;

import com.wiki4ai.dto.ApiTokenCreatedDTO;
import com.wiki4ai.dto.ApiTokenDTO;
import com.wiki4ai.dto.TokenCreateRequestDTO;
import com.wiki4ai.exception.BadRequestException;
import com.wiki4ai.model.Actor;
import com.wiki4ai.model.ApiToken;
import com.wiki4ai.model.Role;
import com.wiki4ai.model.User;
import com.wiki4ai.repository.ActorRepository;
import com.wiki4ai.repository.ApiTokenRepository;
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
 * Integration tests for {@link ApiTokenService} against a real database (H2,
 * WIKI4AI-135 / WIKI4AI-136).
 * <p>
 * @SpringBootTest with the test profile: Flyway is disabled and the schema is
 * created by Hibernate from the entities, so the V18 migration itself is not
 * exercised here — the pipeline verifies it on dev against Postgres.
 * <p>
 * The assertions that matter most are the two halves of the story: a created token
 * stores ONLY its hash (never the plaintext), and a revoked token stops
 * authenticating immediately — including when it had already been validated and
 * cached.
 */
@SpringBootTest
@ActiveProfiles("test")
class ApiTokenServiceTest {

    private static final String ADMIN = "token_admin";
    private static final String ALICE = "token_alice";
    private static final String BOB = "token_bob";

    @Autowired
    private ApiTokenService apiTokenService;

    @Autowired
    private ApiTokenRepository apiTokenRepository;

    @Autowired
    private ActorRepository actorRepository;

    @Autowired
    private UserRepository userRepository;

    private Long aliceId;
    private Long bobId;

    @BeforeEach
    void setUp() {
        // The H2 "testdb" is shared across Spring contexts, so start from a clean
        // slate for the tables this story owns.
        apiTokenRepository.deleteAll();
        actorRepository.deleteAll();
        for (String name : List.of(ADMIN, ALICE, BOB)) {
            userRepository.findByUsername(name).ifPresent(userRepository::delete);
        }

        saveUser(ADMIN, Role.ADMIN);
        aliceId = saveUser(ALICE, Role.USER).getId();
        bobId = saveUser(BOB, Role.USER).getId();

        // The validation cache lives in the service singleton; never let one test
        // inherit another test's cached token.
        apiTokenService.clearValidationCache();
    }

    private User saveUser(String username, Role role) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@test.local");
        user.setPassword("not-a-real-hash");
        user.setRole(role);
        return userRepository.save(user);
    }

    private Actor saveActor(String handle, String kind, Long ownerUserId) {
        Actor actor = new Actor();
        actor.setHandle(handle);
        actor.setKind(kind);
        actor.setStatus(Actor.STATUS_ACTIVE);
        actor.setOwnerUserId(ownerUserId);
        return actorRepository.save(actor);
    }

    private ApiTokenCreatedDTO create(String name, String username) {
        return apiTokenService.createToken(TokenCreateRequestDTO.builder().name(name).build(), username);
    }

    // ── Creation and storage shape ────────────────────────────────────────────

    @Nested
    @DisplayName("POST-side creation: hashed storage (WIKI4AI-135)")
    class CreationTests {

        @Test
        @DisplayName("Returns a w4a_ token of 4 + 32 base62 characters")
        void shouldReturnWellFormedToken() {
            ApiTokenCreatedDTO created = create("ci", ALICE);

            assertThat(created.getToken()).startsWith(ApiToken.HASHED_TOKEN_PREFIX);
            assertThat(created.getToken()).hasSize(ApiToken.HASHED_TOKEN_PREFIX.length() + ApiTokenService.TOKEN_BODY_LENGTH);
            assertThat(ApiTokenService.isHashedTokenFormat(created.getToken())).isTrue();
        }

        @Test
        @DisplayName("Stores only the hash: token_value is null and token_hash is 64 hex characters")
        void shouldStoreHashNotPlaintext() {
            ApiTokenCreatedDTO created = create("ci", ALICE);

            ApiToken row = apiTokenRepository.findById(created.getMetadata().getId()).orElseThrow();

            // The plaintext is never persisted anywhere in the row.
            assertThat(row.getTokenValue()).isNull();
            assertThat(row.getTokenHash())
                    .isNotNull()
                    .hasSize(64)
                    .matches("[0-9a-f]{64}");
            assertThat(row.getTokenHash()).isEqualTo(ApiTokenService.sha256Hex(created.getToken()));
            assertThat(row.toString()).doesNotContain(created.getToken());
        }

        @Test
        @DisplayName("Stores an 8-character display prefix taken from the token body")
        void shouldStoreEightCharacterPrefix() {
            ApiTokenCreatedDTO created = create("ci", ALICE);

            String prefix = created.getMetadata().getPrefix();
            assertThat(prefix).hasSize(8);

            ApiToken row = apiTokenRepository.findById(created.getMetadata().getId()).orElseThrow();
            assertThat(row.getTokenPrefix()).isEqualTo(prefix);
            // The prefix is the body's first 8 characters, i.e. what follows "w4a_".
            assertThat(created.getToken()).startsWith(ApiToken.HASHED_TOKEN_PREFIX + prefix);
            // A prefix is not the token: it cannot be used to authenticate.
            assertThat(ApiTokenService.isHashedTokenFormat(prefix)).isFalse();
        }

        @Test
        @DisplayName("Two created tokens are different secrets with different hashes")
        void shouldGenerateDistinctSecrets() {
            ApiTokenCreatedDTO first = create("ci-1", ALICE);
            ApiTokenCreatedDTO second = create("ci-2", ALICE);

            assertThat(first.getToken()).isNotEqualTo(second.getToken());
            assertThat(first.getMetadata().getPrefix()).isNotEqualTo(second.getMetadata().getPrefix());
        }

        @Test
        @DisplayName("Defaults to read+write scopes and never to admin")
        void shouldDefaultToNonAdminScopes() {
            ApiTokenDTO metadata = create("ci", ALICE).getMetadata();

            assertThat(metadata.getScopes())
                    .containsExactlyInAnyOrder(ApiTokenService.SCOPE_READ, ApiTokenService.SCOPE_WRITE);
            assertThat(metadata.getScopes()).doesNotContain(ApiTokenService.SCOPE_ADMIN);
        }

        @Test
        @DisplayName("Keeps an explicit scope list and an explicit expiry")
        void shouldKeepRequestedScopesAndExpiry() {
            LocalDateTime expiresAt = LocalDateTime.now().plusDays(7);

            ApiTokenDTO metadata = apiTokenService.createToken(TokenCreateRequestDTO.builder()
                    .name("scoped")
                    .expiresAt(expiresAt)
                    .scopes(List.of(ApiTokenService.SCOPE_READ))
                    .build(), ALICE).getMetadata();

            assertThat(metadata.getScopes()).containsExactly(ApiTokenService.SCOPE_READ);
            assertThat(metadata.getExpiresAt()).isNotNull();

            ApiToken row = apiTokenRepository.findById(metadata.getId()).orElseThrow();
            assertThat(row.getExpiresAt()).isBetween(LocalDateTime.now(), LocalDateTime.now().plusDays(8));
        }

        @Test
        @DisplayName("A token with no expiry is an infinite-expiry token")
        void shouldAllowInfiniteExpiry() {
            ApiTokenDTO metadata = create("forever", ALICE).getMetadata();

            assertThat(metadata.getExpiresAt()).isNull();
            ApiToken row = apiTokenRepository.findById(metadata.getId()).orElseThrow();
            assertThat(row.isInfinite()).isTrue();
            assertThat(row.isExpired()).isFalse();
        }

        @Test
        @DisplayName("Anonymous caller cannot create a token (403)")
        void shouldRejectAnonymousCreation() {
            assertThatThrownBy(() -> create("ci", "anonymous"))
                    .isInstanceOf(AccessDeniedException.class);
        }
    }

    // ── Validation ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Validation (400 Bad Request)")
    class ValidationTests {

        @Test
        @DisplayName("Missing name is rejected")
        void shouldRejectMissingName() {
            assertThatThrownBy(() -> apiTokenService.createToken(
                    TokenCreateRequestDTO.builder().name("   ").build(), ALICE))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("name");
        }

        @Test
        @DisplayName("Unknown scope is rejected")
        void shouldRejectUnknownScope() {
            assertThatThrownBy(() -> apiTokenService.createToken(TokenCreateRequestDTO.builder()
                    .name("ci")
                    .scopes(List.of("read", "superuser"))
                    .build(), ALICE))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("superuser");
        }

        @Test
        @DisplayName("Blank scope is rejected")
        void shouldRejectBlankScope() {
            assertThatThrownBy(() -> apiTokenService.createToken(TokenCreateRequestDTO.builder()
                    .name("ci")
                    .scopes(List.of(" "))
                    .build(), ALICE))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        @DisplayName("An expiry in the past is rejected")
        void shouldRejectPastExpiry() {
            assertThatThrownBy(() -> apiTokenService.createToken(TokenCreateRequestDTO.builder()
                    .name("ci")
                    .expiresAt(LocalDateTime.now().minusMinutes(5))
                    .build(), ALICE))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("past");
        }

        @Test
        @DisplayName("An actor that does not exist is rejected")
        void shouldRejectUnknownActor() {
            assertThatThrownBy(() -> apiTokenService.createToken(TokenCreateRequestDTO.builder()
                    .name("ci")
                    .actorId(999_999L)
                    .build(), ALICE))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        @DisplayName("An actor belonging to another user is rejected")
        void shouldRejectForeignActor() {
            Actor bobsAgent = saveActor("bob-agent", Actor.KIND_AGENT, bobId);

            assertThatThrownBy(() -> apiTokenService.createToken(TokenCreateRequestDTO.builder()
                    .name("ci")
                    .actorId(bobsAgent.getId())
                    .build(), ALICE))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        @DisplayName("An agent token cannot be granted the admin scope")
        void shouldRejectAdminScopeForAgentToken() {
            Actor alicesAgent = saveActor("alice-agent", Actor.KIND_AGENT, aliceId);

            assertThatThrownBy(() -> apiTokenService.createToken(TokenCreateRequestDTO.builder()
                    .name("agent-token")
                    .actorId(alicesAgent.getId())
                    .scopes(List.of(ApiTokenService.SCOPE_READ, ApiTokenService.SCOPE_ADMIN))
                    .build(), ALICE))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("agent");
        }

        @Test
        @DisplayName("A human actor may be granted the admin scope")
        void shouldAllowAdminScopeForHumanActor() {
            Actor human = saveActor("alice-human", Actor.KIND_HUMAN, aliceId);

            ApiTokenDTO metadata = apiTokenService.createToken(TokenCreateRequestDTO.builder()
                    .name("human-token")
                    .actorId(human.getId())
                    .scopes(List.of(ApiTokenService.SCOPE_ADMIN))
                    .build(), ALICE).getMetadata();

            assertThat(metadata.getScopes()).containsExactly(ApiTokenService.SCOPE_ADMIN);
            assertThat(metadata.getActorId()).isEqualTo(human.getId());
        }

        @Test
        @DisplayName("A service actor may be granted the admin scope")
        void shouldAllowAdminScopeForServiceActor() {
            Actor service = saveActor("svc-backup", Actor.KIND_SERVICE, aliceId);

            ApiTokenDTO metadata = apiTokenService.createToken(TokenCreateRequestDTO.builder()
                    .name("service-token")
                    .actorId(service.getId())
                    .scopes(List.of(ApiTokenService.SCOPE_ADMIN))
                    .build(), ALICE).getMetadata();

            assertThat(metadata.getScopes()).containsExactly(ApiTokenService.SCOPE_ADMIN);
        }

        @Test
        @DisplayName("An ADMIN may bind a token to a system actor")
        void shouldAllowAdminToBindSystemActor() {
            Actor system = saveActor("wiki4ai-mcp", Actor.KIND_SERVICE, null);

            ApiTokenDTO metadata = apiTokenService.createToken(TokenCreateRequestDTO.builder()
                    .name("mcp-token")
                    .actorId(system.getId())
                    .build(), ADMIN).getMetadata();

            assertThat(metadata.getActorId()).isEqualTo(system.getId());
        }
    }

    // ── Ownership ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Ownership: a foreign token is 404 on read and on write")
    class OwnershipTests {

        @Test
        @DisplayName("Listing returns only the caller's tokens, newest first, without secrets")
        void shouldListOnlyOwnTokensNewestFirst() {
            LocalDateTime older = LocalDateTime.now().minusDays(2);
            LocalDateTime newer = LocalDateTime.now().minusDays(1);

            ApiToken aliceOld = hashedRow(ALICE, "alice-old", older);
            ApiToken aliceNew = hashedRow(ALICE, "alice-new", newer);
            hashedRow(BOB, "bob-token", newer);

            List<ApiTokenDTO> list = apiTokenService.listTokens(ALICE);

            assertThat(list).hasSize(2);
            assertThat(list).extracting(ApiTokenDTO::getId)
                    .containsExactly(aliceNew.getId(), aliceOld.getId());
            assertThat(list).extracting(ApiTokenDTO::getName)
                    .containsExactly("alice-new", "alice-old");
        }

        @Test
        @DisplayName("A listed token carries no plaintext and no hash")
        void shouldNotExposeSecretsInListing() {
            ApiTokenCreatedDTO created = create("ci", ALICE);

            List<ApiTokenDTO> list = apiTokenService.listTokens(ALICE);
            assertThat(list).hasSize(1);

            ApiTokenDTO dto = list.get(0);
            assertThat(dto.getId()).isEqualTo(created.getMetadata().getId());
            assertThat(dto.getName()).isEqualTo("ci");
            assertThat(dto.getPrefix()).isEqualTo(created.getMetadata().getPrefix());
            assertThat(dto.getCreatedAt()).isNotNull();
            assertThat(dto.isHashed()).isTrue();

            // The DTO has no field that could carry a secret — and the plaintext is
            // not reachable through any of its values either.
            assertThat(dto.toString()).doesNotContain(created.getToken());
            ApiToken row = apiTokenRepository.findById(dto.getId()).orElseThrow();
            assertThat(row.getTokenValue()).isNull();
            assertThat(dto.toString()).doesNotContain(row.getTokenHash());
        }

        @Test
        @DisplayName("Reading someone else's token is a 404, not a 403")
        void shouldHideForeignTokenOnRead() {
            ApiTokenDTO bobs = create("bob-private", BOB).getMetadata();

            assertThatThrownBy(() -> apiTokenService.getToken(bobs.getId(), ALICE))
                    .isInstanceOf(EntityNotFoundException.class);
        }

        @Test
        @DisplayName("Revoking someone else's token is a 404 and leaves it usable")
        void shouldHideForeignTokenOnRevoke() {
            ApiTokenCreatedDTO bobs = create("bob-private", BOB);

            assertThatThrownBy(() -> apiTokenService.revokeToken(bobs.getMetadata().getId(), ALICE))
                    .isInstanceOf(EntityNotFoundException.class);

            assertThat(apiTokenRepository.findById(bobs.getMetadata().getId()))
                    .get()
                    .matches(t -> t.getRevokedAt() == null);
            assertThat(apiTokenService.authenticate(bobs.getToken())).isPresent();
        }

        @Test
        @DisplayName("An unknown token id is a 404")
        void shouldReturn404ForUnknownToken() {
            assertThatThrownBy(() -> apiTokenService.getToken(999_999L, ALICE))
                    .isInstanceOf(EntityNotFoundException.class);
        }

        @Test
        @DisplayName("A legacy clear-text row is listed as legacy, with no prefix and no scopes")
        void shouldListLegacyRows() {
            ApiToken legacy = ApiToken.builder()
                    .user(userRepository.findByUsername(ALICE).orElseThrow())
                    .name("legacy-jwt")
                    .tokenValue("a.legacy.jwt")
                    .createdAt(LocalDateTime.now())
                    .build();
            apiTokenRepository.saveAndFlush(legacy);

            List<ApiTokenDTO> list = apiTokenService.listTokens(ALICE);

            assertThat(list).hasSize(1);
            assertThat(list.get(0).isHashed()).isFalse();
            assertThat(list.get(0).getPrefix()).isNull();
            assertThat(list.get(0).getScopes()).isNull();
            assertThat(list.get(0).toString()).doesNotContain("a.legacy.jwt");
        }
    }

    // ── Revocation and cache (WIKI4AI-136) ────────────────────────────────────

    @Nested
    @DisplayName("Revocation is real: authenticate() refuses the token (WIKI4AI-136)")
    class RevocationTests {

        @Test
        @DisplayName("A valid hashed token authenticates to its owner")
        void shouldAuthenticateValidToken() {
            ApiTokenCreatedDTO created = create("ci", ALICE);

            Optional<ApiTokenService.ApiTokenPrincipal> principal =
                    apiTokenService.authenticate(created.getToken());

            assertThat(principal).isPresent();
            assertThat(principal.get().username()).isEqualTo(ALICE);
            assertThat(principal.get().userId()).isEqualTo(aliceId);
            assertThat(principal.get().tokenId()).isEqualTo(created.getMetadata().getId());
            assertThat(principal.get().actorId()).isNull();
            assertThat(principal.get().scopes())
                    .containsExactlyInAnyOrder(ApiTokenService.SCOPE_READ, ApiTokenService.SCOPE_WRITE);
        }

        @Test
        @DisplayName("A revoked token stops authenticating, and its cache entry is evicted")
        void shouldRefuseRevokedTokenEvenWhenCached() {
            ApiTokenCreatedDTO created = create("ci", ALICE);

            // Warm the cache: the token is now a live entry in the service.
            assertThat(apiTokenService.authenticate(created.getToken())).isPresent();
            assertThat(apiTokenService.validationCacheSize()).isEqualTo(1);

            apiTokenService.revokeToken(created.getMetadata().getId(), ALICE);

            // The proof of WIKI4AI-136: the very next request with the same token is
            // refused, without waiting for the cache TTL.
            assertThat(apiTokenService.validationCacheSize()).isZero();
            assertThat(apiTokenService.authenticate(created.getToken())).isEmpty();
        }

        @Test
        @DisplayName("Revoke is a soft revoke: the row stays and carries revoked_at")
        void shouldSoftRevokeAndKeepTheRow() {
            ApiTokenCreatedDTO created = create("ci", ALICE);

            apiTokenService.revokeToken(created.getMetadata().getId(), ALICE);

            ApiToken row = apiTokenRepository.findById(created.getMetadata().getId()).orElseThrow();
            assertThat(row.getRevokedAt()).isNotNull();
            assertThat(row.isRevoked()).isTrue();
            assertThat(row.getTokenHash()).isNotNull();
            assertThat(row.getTokenValue()).isNull();
        }

        @Test
        @DisplayName("A revoked token is refused straight from the database too")
        void shouldRefuseRevokedTokenWithoutCache() {
            ApiTokenCreatedDTO created = create("ci", ALICE);
            apiTokenService.revokeToken(created.getMetadata().getId(), ALICE);
            apiTokenService.clearValidationCache();

            assertThat(apiTokenService.authenticate(created.getToken())).isEmpty();
        }

        @Test
        @DisplayName("Revoking twice is idempotent and keeps the first revoked_at")
        void shouldRevokeIdempotently() {
            ApiTokenCreatedDTO created = create("ci", ALICE);

            apiTokenService.revokeToken(created.getMetadata().getId(), ALICE);
            LocalDateTime first = apiTokenRepository.findById(created.getMetadata().getId())
                    .orElseThrow().getRevokedAt();

            boolean changedAgain = apiTokenService.revokeToken(created.getMetadata().getId(), ALICE);

            assertThat(changedAgain).isFalse();
            assertThat(apiTokenRepository.findById(created.getMetadata().getId())
                    .orElseThrow().getRevokedAt()).isEqualTo(first);
        }

        @Test
        @DisplayName("An unknown token hash is refused")
        void shouldRefuseUnknownToken() {
            String neverIssued = ApiToken.HASHED_TOKEN_PREFIX + "a".repeat(ApiTokenService.TOKEN_BODY_LENGTH);

            assertThat(apiTokenService.authenticate(neverIssued)).isEmpty();
        }

        @Test
        @DisplayName("An expired token is refused")
        void shouldRefuseExpiredToken() {
            // An expiry in the past cannot be created through the service (400), so
            // the row is written directly to model a token that simply aged out.
            String plaintext = ApiToken.HASHED_TOKEN_PREFIX + "f".repeat(ApiTokenService.TOKEN_BODY_LENGTH);
            ApiToken row = ApiToken.builder()
                    .user(userRepository.findByUsername(ALICE).orElseThrow())
                    .name("short-lived")
                    .tokenHash(ApiTokenService.sha256Hex(plaintext))
                    .tokenPrefix("ffffffff")
                    .expiresAt(LocalDateTime.now().minusMinutes(1))
                    .createdAt(LocalDateTime.now().minusDays(1))
                    .build();
            apiTokenRepository.saveAndFlush(row);

            assertThat(apiTokenService.authenticate(plaintext)).isEmpty();
        }

        @Test
        @DisplayName("An expired token is refused even when it was cached while still valid")
        void shouldRefuseTokenThatExpiredAfterCaching() {
            ApiTokenCreatedDTO created = apiTokenService.createToken(TokenCreateRequestDTO.builder()
                    .name("expires-now")
                    .expiresAt(LocalDateTime.now().plusDays(1))
                    .build(), ALICE);

            assertThat(apiTokenService.authenticate(created.getToken())).isPresent();

            // Replace the row by the same token with an expiry already in the past,
            // then clear the cache the way a TTL expiry would: the next validation
            // consults the row and refuses it.
            ApiToken live = apiTokenRepository.findById(created.getMetadata().getId()).orElseThrow();
            String hash = live.getTokenHash();
            apiTokenRepository.delete(live);
            apiTokenRepository.saveAndFlush(ApiToken.builder()
                    .user(userRepository.findByUsername(ALICE).orElseThrow())
                    .name("expires-now")
                    .tokenHash(hash)
                    .tokenPrefix("ffffffff")
                    .expiresAt(LocalDateTime.now().minusSeconds(1))
                    .createdAt(LocalDateTime.now().minusDays(1))
                    .build());
            apiTokenService.clearValidationCache();

            assertThat(apiTokenService.authenticate(created.getToken())).isEmpty();
        }

        @Test
        @DisplayName("A token expiring in the future authenticates")
        void shouldAcceptNotYetExpiredToken() {
            ApiTokenCreatedDTO created = apiTokenService.createToken(TokenCreateRequestDTO.builder()
                    .name("valid-until-tomorrow")
                    .expiresAt(LocalDateTime.now().plusDays(1))
                    .build(), ALICE);

            assertThat(apiTokenService.authenticate(created.getToken())).isPresent();
        }

        @Test
        @DisplayName("A non-w4a_ value is not this service's business (legacy JWT stays with JwtUtil)")
        void shouldIgnoreLegacyJwt() {
            ApiToken legacy = ApiToken.builder()
                    .user(userRepository.findByUsername(ALICE).orElseThrow())
                    .name("legacy-jwt")
                    .tokenValue("header.payload.signature")
                    .createdAt(LocalDateTime.now())
                    .build();
            apiTokenRepository.saveAndFlush(legacy);

            assertThat(apiTokenService.authenticate("header.payload.signature")).isEmpty();
            assertThat(apiTokenService.authenticate(null)).isEmpty();
            assertThat(apiTokenService.authenticate("w4a_short")).isEmpty();
        }

        @Test
        @DisplayName("A legacy token cannot be authenticated by hash even if its text is hashed")
        void shouldNotLookUpLegacyRowByHash() {
            String legacyValue = "header.payload.signature";
            ApiToken legacy = ApiToken.builder()
                    .user(userRepository.findByUsername(ALICE).orElseThrow())
                    .name("legacy-jwt")
                    .tokenValue(legacyValue)
                    .tokenHash(ApiTokenService.sha256Hex(legacyValue))
                    .createdAt(LocalDateTime.now())
                    .build();
            apiTokenRepository.saveAndFlush(legacy);

            // The filter only hashes values that carry the w4a_ prefix, so a legacy
            // JWT never reaches the hash lookup at all.
            assertThat(apiTokenService.authenticate(legacyValue)).isEmpty();
        }
    }

    // ── Cache and throttling ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Cache TTL and throttled last_used_at")
    class CacheAndThrottleTests {

        @Test
        @DisplayName("The second validation inside the throttle window performs no write")
        void shouldThrottleLastUsedAt() {
            ApiTokenCreatedDTO created = create("ci", ALICE);

            apiTokenService.authenticate(created.getToken());
            LocalDateTime firstTouch = apiTokenRepository.findById(created.getMetadata().getId())
                    .orElseThrow().getLastUsedAt();
            assertThat(firstTouch).isNotNull();

            // Same token, immediately again: served from the cache, no new write.
            apiTokenService.authenticate(created.getToken());
            LocalDateTime secondTouch = apiTokenRepository.findById(created.getMetadata().getId())
                    .orElseThrow().getLastUsedAt();

            assertThat(secondTouch).isEqualTo(firstTouch);
        }

        @Test
        @DisplayName("last_used_at is written on the first validation")
        void shouldRecordLastUsedAt() {
            ApiTokenCreatedDTO created = create("ci", ALICE);

            assertThat(apiTokenRepository.findById(created.getMetadata().getId())
                    .orElseThrow().getLastUsedAt()).isNull();

            apiTokenService.authenticate(created.getToken());

            ApiToken row = apiTokenRepository.findById(created.getMetadata().getId()).orElseThrow();
            assertThat(row.getLastUsedAt()).isNotNull();
            assertThat(row.getLastUsedAt()).isAfterOrEqualTo(row.getCreatedAt());
        }

        @Test
        @DisplayName("A revoked-then-revalidated token is not resurrected by the cache")
        void shouldNotCacheNegativeLookups() {
            String neverIssued = ApiToken.HASHED_TOKEN_PREFIX + "b".repeat(ApiTokenService.TOKEN_BODY_LENGTH);

            assertThat(apiTokenService.authenticate(neverIssued)).isEmpty();
            assertThat(apiTokenService.validationCacheSize()).isZero();

            // A token created after that failed lookup is accepted immediately.
            ApiTokenCreatedDTO created = apiTokenService.createToken(TokenCreateRequestDTO.builder()
                    .name("late").build(), ALICE);
            assertThat(apiTokenService.authenticate(created.getToken())).isPresent();
        }

        @Test
        @DisplayName("clearValidationCache forces the next validation back to the database")
        void shouldClearCache() {
            ApiTokenCreatedDTO created = create("ci", ALICE);
            apiTokenService.authenticate(created.getToken());
            assertThat(apiTokenService.validationCacheSize()).isEqualTo(1);

            apiTokenService.clearValidationCache();

            assertThat(apiTokenService.validationCacheSize()).isZero();
            assertThat(apiTokenService.authenticate(created.getToken())).isPresent();
        }
    }

    // ── Scopes on the effective principal ─────────────────────────────────────

    @Nested
    @DisplayName("Agent tokens never get administrative scope")
    class ScopeEnforcementTests {

        @Test
        @DisplayName("An agent-bound row that already carries admin loses it on validation")
        void shouldDropAdminScopeForAgentBoundRow() {
            Actor agent = saveActor("rogue-agent", Actor.KIND_AGENT, aliceId);
            String plaintext = ApiToken.HASHED_TOKEN_PREFIX + "c".repeat(ApiTokenService.TOKEN_BODY_LENGTH);

            // Written straight to the table, bypassing the creation-time check.
            ApiToken row = ApiToken.builder()
                    .user(userRepository.findByUsername(ALICE).orElseThrow())
                    .name("rogue")
                    .tokenHash(ApiTokenService.sha256Hex(plaintext))
                    .tokenPrefix("cccccccc")
                    .scopes(ApiTokenService.SCOPE_READ + " " + ApiTokenService.SCOPE_ADMIN)
                    .actorId(agent.getId())
                    .createdAt(LocalDateTime.now())
                    .build();
            apiTokenRepository.saveAndFlush(row);

            Optional<ApiTokenService.ApiTokenPrincipal> principal = apiTokenService.authenticate(plaintext);

            assertThat(principal).isPresent();
            assertThat(principal.get().scopes()).contains(ApiTokenService.SCOPE_READ);
            assertThat(principal.get().scopes()).doesNotContain(ApiTokenService.SCOPE_ADMIN);
            assertThat(principal.get().actorId()).isEqualTo(agent.getId());
        }

        @Test
        @DisplayName("A human-bound row keeps its admin scope")
        void shouldKeepAdminScopeForHumanBoundRow() {
            Actor human = saveActor("human-holder", Actor.KIND_HUMAN, aliceId);
            String plaintext = ApiToken.HASHED_TOKEN_PREFIX + "d".repeat(ApiTokenService.TOKEN_BODY_LENGTH);

            ApiToken row = ApiToken.builder()
                    .user(userRepository.findByUsername(ALICE).orElseThrow())
                    .name("human")
                    .tokenHash(ApiTokenService.sha256Hex(plaintext))
                    .tokenPrefix("dddddddd")
                    .scopes(ApiTokenService.SCOPE_ADMIN)
                    .actorId(human.getId())
                    .createdAt(LocalDateTime.now())
                    .build();
            apiTokenRepository.saveAndFlush(row);

            assertThat(apiTokenService.authenticate(plaintext))
                    .get()
                    .matches(p -> p.scopes().contains(ApiTokenService.SCOPE_ADMIN));
        }

        @Test
        @DisplayName("A row with no stored scope list falls back to the defaults")
        void shouldFallBackToDefaultScopes() {
            String plaintext = ApiToken.HASHED_TOKEN_PREFIX + "e".repeat(ApiTokenService.TOKEN_BODY_LENGTH);

            ApiToken row = ApiToken.builder()
                    .user(userRepository.findByUsername(ALICE).orElseThrow())
                    .name("no-scopes")
                    .tokenHash(ApiTokenService.sha256Hex(plaintext))
                    .tokenPrefix("eeeeeeee")
                    .createdAt(LocalDateTime.now())
                    .build();
            apiTokenRepository.saveAndFlush(row);

            assertThat(apiTokenService.authenticate(plaintext))
                    .get()
                    .matches(p -> p.scopes().containsAll(
                            List.of(ApiTokenService.SCOPE_READ, ApiTokenService.SCOPE_WRITE)));
        }
    }

    // ── Rotation (WIKI4AI-137) ────────────────────────────────────────────────

    /**
     * Rotation is one call: the old secret dies and the new one is born in the same
     * transaction. The assertions that matter are the inheritance (name, scopes,
     * actor, expiry) and the immediate death of the old plaintext — including when it
     * was sitting in the validation cache.
     */
    @Nested
    @DisplayName("Rotation: one call revokes the old and issues the new (WIKI4AI-137)")
    class RotationTests {

        @Test
        @DisplayName("The new token inherits name, scopes, actorId and expiresAt")
        void shouldRotateInOneCallAndInheritPolicy() {
            Actor serviceActor = saveActor("rotate-agent", Actor.KIND_SERVICE, aliceId);
            LocalDateTime expiresAt = LocalDateTime.now().plusDays(30);

            ApiTokenCreatedDTO original = apiTokenService.createToken(TokenCreateRequestDTO.builder()
                    .name("ci-rotate")
                    .scopes(List.of(ApiTokenService.SCOPE_READ))
                    .actorId(serviceActor.getId())
                    .expiresAt(expiresAt)
                    .build(), ALICE);

            ApiTokenCreatedDTO rotated = apiTokenService.rotateToken(
                    original.getMetadata().getId(), ALICE);

            // A new secret in a new row — the old plaintext is not reissued.
            assertThat(rotated.getToken()).isNotEqualTo(original.getToken());
            assertThat(rotated.getMetadata().getId()).isNotEqualTo(original.getMetadata().getId());
            assertThat(rotated.getToken()).startsWith(ApiToken.HASHED_TOKEN_PREFIX);
            assertThat(ApiTokenService.isHashedTokenFormat(rotated.getToken())).isTrue();
            assertThat(rotated.getMetadata().isHashed()).isTrue();

            // Same policy, different credential.
            assertThat(rotated.getMetadata().getName()).isEqualTo("ci-rotate");
            assertThat(rotated.getMetadata().getScopes()).containsExactly(ApiTokenService.SCOPE_READ);
            assertThat(rotated.getMetadata().getActorId()).isEqualTo(serviceActor.getId());
            assertThat(rotated.getMetadata().getExpiresAt()).isNotNull();

            // Both rows are stored: the old one revoked, the new one live. Compared as
            // stored values, so the assertion is about the rows, not about rounding.
            ApiToken oldRow = apiTokenRepository.findById(original.getMetadata().getId()).orElseThrow();
            ApiToken newRow = apiTokenRepository.findById(rotated.getMetadata().getId()).orElseThrow();

            assertThat(oldRow.getRevokedAt()).isNotNull();
            assertThat(newRow.getRevokedAt()).isNull();
            assertThat(newRow.getName()).isEqualTo(oldRow.getName());
            assertThat(newRow.getScopes()).isEqualTo(oldRow.getScopes());
            assertThat(newRow.getActorId()).isEqualTo(oldRow.getActorId());
            assertThat(newRow.getExpiresAt()).isEqualTo(oldRow.getExpiresAt());
            // The replacement is hashed too: no plaintext anywhere in the new row.
            assertThat(newRow.getTokenValue()).isNull();
            assertThat(newRow.getTokenHash()).isEqualTo(ApiTokenService.sha256Hex(rotated.getToken()));
        }

        @Test
        @DisplayName("The rotated secret stops working immediately, even though it was cached")
        void rotatedOldToken_shouldStopAuthenticating() {
            ApiTokenCreatedDTO original = create("ci-cache", ALICE);

            // Warm the cache: the old token is a live entry in the service.
            assertThat(apiTokenService.authenticate(original.getToken())).isPresent();
            assertThat(apiTokenService.validationCacheSize()).isEqualTo(1);

            ApiTokenCreatedDTO rotated = apiTokenService.rotateToken(
                    original.getMetadata().getId(), ALICE);

            // The cache entry of the old token is gone — no TTL to wait for...
            assertThat(apiTokenService.validationCacheSize()).isZero();

            // ...the very next request carrying the old plaintext is refused (the filter
            // turns this into a 401), and the new plaintext authenticates.
            assertThat(apiTokenService.authenticate(original.getToken())).isEmpty();
            assertThat(apiTokenService.authenticate(rotated.getToken())).isPresent();
        }

        @Test
        @DisplayName("A legacy clear-text row is not rotatable (400)")
        void legacyRowIsNotRotatable() {
            ApiToken legacy = ApiToken.builder()
                    .user(userRepository.findByUsername(ALICE).orElseThrow())
                    .name("legacy-jwt")
                    .tokenValue("header.payload.signature")
                    .createdAt(LocalDateTime.now())
                    .build();
            apiTokenRepository.saveAndFlush(legacy);
            assertThat(legacy.isHashed()).isFalse();

            assertThatThrownBy(() -> apiTokenService.rotateToken(legacy.getId(), ALICE))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Legacy");

            // Nothing was revoked and nothing new was created.
            assertThat(apiTokenRepository.findById(legacy.getId()))
                    .get()
                    .matches(row -> row.getRevokedAt() == null);
            assertThat(apiTokenRepository.findByUserId(aliceId)).hasSize(1);
        }

        @Test
        @DisplayName("An already revoked token is not rotatable (409)")
        void revokedTokenIsNotRotatable() {
            ApiTokenCreatedDTO created = create("ci", ALICE);
            apiTokenService.revokeToken(created.getMetadata().getId(), ALICE);

            assertThatThrownBy(() -> apiTokenService.rotateToken(created.getMetadata().getId(), ALICE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("revoked");

            // A refused rotation issues no replacement token.
            assertThat(apiTokenRepository.findByUserId(aliceId)).hasSize(1);
        }

        @Test
        @DisplayName("Rotating someone else's token is a 404 and leaves it working")
        void foreignTokenIsHidden() {
            ApiTokenCreatedDTO bobs = create("bob-private", BOB);

            assertThatThrownBy(() -> apiTokenService.rotateToken(bobs.getMetadata().getId(), ALICE))
                    .isInstanceOf(EntityNotFoundException.class);

            assertThat(apiTokenRepository.findById(bobs.getMetadata().getId()))
                    .get()
                    .matches(row -> row.getRevokedAt() == null);
            assertThat(apiTokenService.authenticate(bobs.getToken())).isPresent();
            assertThat(apiTokenRepository.findByUserId(bobId)).hasSize(1);
            assertThat(apiTokenRepository.findByUserId(aliceId)).isEmpty();
        }

        @Test
        @DisplayName("An unknown id is a 404; an anonymous caller is refused")
        void unknownIdAndAnonymousCaller() {
            assertThatThrownBy(() -> apiTokenService.rotateToken(999_999L, ALICE))
                    .isInstanceOf(EntityNotFoundException.class);
            assertThatThrownBy(() -> apiTokenService.rotateToken(1L, "anonymous"))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("Rotation chains: every intermediate secret is dead")
        void rotationChains() {
            ApiTokenCreatedDTO first = create("chain", ALICE);
            ApiTokenCreatedDTO second = apiTokenService.rotateToken(first.getMetadata().getId(), ALICE);
            ApiTokenCreatedDTO third = apiTokenService.rotateToken(second.getMetadata().getId(), ALICE);

            assertThat(apiTokenService.authenticate(first.getToken())).isEmpty();
            assertThat(apiTokenService.authenticate(second.getToken())).isEmpty();
            assertThat(apiTokenService.authenticate(third.getToken())).isPresent();
            assertThat(apiTokenRepository.findByUserId(aliceId)).hasSize(3);
        }
    }

    // ── Actor kill switch (WIKI4AI-137) ───────────────────────────────────────

    /**
     * The AC wording is "disable every token of the actor, including the cached ones".
     * These tests warm the validation cache first, then pull the switch, and assert
     * that the cache holds nothing and every token of that actor is refused — while
     * tokens of other actors, and unbound tokens, keep working.
     */
    @Nested
    @DisplayName("Actor kill switch: every token of an actor dies at once (WIKI4AI-137)")
    class ActorKillSwitchTests {

        private String createForActor(String name, Long actorId) {
            return apiTokenService.createToken(TokenCreateRequestDTO.builder()
                    .name(name)
                    .actorId(actorId)
                    .build(), ALICE).getToken();
        }

        @Test
        @DisplayName("All tokens of the actor stop authenticating, cached ones included")
        void killSwitchRevokesEveryTokenIncludingCachedOnes() {
            Actor target = saveActor("kill-target", Actor.KIND_AGENT, aliceId);
            Actor other = saveActor("kill-other", Actor.KIND_AGENT, aliceId);

            String first = createForActor("kill-1", target.getId());
            String second = createForActor("kill-2", target.getId());
            String third = createForActor("kill-3", target.getId());
            String boundToOtherActor = createForActor("kill-other-token", other.getId());
            String unbound = create("kill-unbound", ALICE).getToken();

            // Warm the cache for all five: the kill switch has to beat the cache.
            for (String token : List.of(first, second, third, boundToOtherActor, unbound)) {
                assertThat(apiTokenService.authenticate(token)).isPresent();
            }
            assertThat(apiTokenService.validationCacheSize()).isEqualTo(5);

            int changed = apiTokenService.revokeActorTokens(target.getId(), ALICE);

            assertThat(changed).isEqualTo(3);
            // Exactly the three affected entries were evicted. The two tokens that do
            // not belong to this actor keep their cache entries — the kill switch does
            // not disturb anything outside the actor.
            assertThat(apiTokenService.validationCacheSize()).isEqualTo(2);

            assertThat(apiTokenService.authenticate(first)).isEmpty();
            assertThat(apiTokenService.authenticate(second)).isEmpty();
            assertThat(apiTokenService.authenticate(third)).isEmpty();

            // A refused token is never cached again: the cache still holds only the two
            // unaffected tokens, so the three dead ones really are gone from it.
            assertThat(apiTokenService.validationCacheSize()).isEqualTo(2);

            // Nothing outside this actor was touched.
            assertThat(apiTokenService.authenticate(boundToOtherActor)).isPresent();
            assertThat(apiTokenService.authenticate(unbound)).isPresent();
        }

        @Test
        @DisplayName("Every affected row carries revoked_at and stays for audit")
        void killSwitchIsASoftRevoke() {
            Actor target = saveActor("kill-audit", Actor.KIND_AGENT, aliceId);
            createForActor("kill-audit-1", target.getId());
            createForActor("kill-audit-2", target.getId());

            apiTokenService.revokeActorTokens(target.getId(), ALICE);

            List<ApiToken> rows = apiTokenRepository.findByActorId(target.getId());
            assertThat(rows).hasSize(2);
            assertThat(rows).allMatch(ApiToken::isRevoked);
            assertThat(rows).extracting(ApiToken::getRevokedAt).doesNotContainNull();
        }

        @Test
        @DisplayName("A second call changes nothing and still succeeds")
        void killSwitchIsIdempotent() {
            Actor target = saveActor("kill-idempotent", Actor.KIND_AGENT, aliceId);
            String token = createForActor("kill-idem-1", target.getId());

            int firstCall = apiTokenService.revokeActorTokens(target.getId(), ALICE);
            LocalDateTime firstStamp = apiTokenRepository.findByActorId(target.getId()).get(0).getRevokedAt();

            int secondCall = apiTokenService.revokeActorTokens(target.getId(), ALICE);
            LocalDateTime stampAfterSecondCall =
                    apiTokenRepository.findByActorId(target.getId()).get(0).getRevokedAt();

            assertThat(firstCall).isEqualTo(1);
            assertThat(secondCall).isZero();
            // The original revocation moment survives a repeat call.
            assertThat(stampAfterSecondCall).isEqualTo(firstStamp);
            assertThat(apiTokenService.authenticate(token)).isEmpty();
        }

        @Test
        @DisplayName("Someone else's actor is a 404 and its tokens keep working")
        void foreignActorIsHidden() {
            Actor bobsAgent = saveActor("bob-kill-agent", Actor.KIND_AGENT, bobId);
            String bobsToken = apiTokenService.createToken(TokenCreateRequestDTO.builder()
                    .name("bob-agent-token")
                    .actorId(bobsAgent.getId())
                    .build(), BOB).getToken();

            assertThat(apiTokenService.authenticate(bobsToken)).isPresent();
            assertThat(apiTokenService.validationCacheSize()).isEqualTo(1);

            assertThatThrownBy(() -> apiTokenService.revokeActorTokens(bobsAgent.getId(), ALICE))
                    .isInstanceOf(EntityNotFoundException.class);

            // Refused call evicted nothing and revoked nothing.
            assertThat(apiTokenService.validationCacheSize()).isEqualTo(1);
            assertThat(apiTokenService.authenticate(bobsToken)).isPresent();
            assertThat(apiTokenRepository.findByActorId(bobsAgent.getId()))
                    .allMatch(row -> row.getRevokedAt() == null);
        }

        @Test
        @DisplayName("An unknown actor is a 404")
        void unknownActorIs404() {
            assertThatThrownBy(() -> apiTokenService.revokeActorTokens(999_999L, ALICE))
                    .isInstanceOf(EntityNotFoundException.class);
        }

        @Test
        @DisplayName("An ADMIN may kill any actor, including a system-owned one")
        void adminMayRevokeAnyActor() {
            Actor system = saveActor("kill-system", Actor.KIND_SERVICE, null);
            // The token is issued by an ADMIN because binding a token to an actor that
            // nobody owns is an ADMIN-only move (requireUsableActor on create).
            String token = apiTokenService.createToken(TokenCreateRequestDTO.builder()
                    .name("admin-system-token")
                    .actorId(system.getId())
                    .build(), ADMIN).getToken();

            assertThat(apiTokenService.authenticate(token)).isPresent();

            int changed = apiTokenService.revokeActorTokens(system.getId(), ADMIN);

            assertThat(changed).isEqualTo(1);
            assertThat(apiTokenService.authenticate(token)).isEmpty();
        }

        @Test
        @DisplayName("An actor with no tokens is a no-op, not an error")
        void actorWithoutTokensIsNoOp() {
            Actor lonely = saveActor("kill-lonely", Actor.KIND_AGENT, aliceId);

            assertThat(apiTokenService.revokeActorTokens(lonely.getId(), ALICE)).isZero();
            assertThat(apiTokenService.revokeActorTokens(lonely.getId(), ALICE)).isZero();
        }

        @Test
        @DisplayName("An anonymous caller cannot pull the switch")
        void anonymousCallerIsRefused() {
            Actor target = saveActor("kill-anon", Actor.KIND_AGENT, aliceId);

            assertThatThrownBy(() -> apiTokenService.revokeActorTokens(target.getId(), "anonymous"))
                    .isInstanceOf(AccessDeniedException.class);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Insert a hashed token row directly, with a controlled createdAt, so ordering
     * and ownership can be tested without depending on wall-clock timing.
     */
    private ApiToken hashedRow(String username, String name, LocalDateTime createdAt) {
        String plaintext = ApiToken.HASHED_TOKEN_PREFIX
                + name.toLowerCase().replaceAll("[^a-z0-9]", "")
                + "0".repeat(ApiTokenService.TOKEN_BODY_LENGTH);
        plaintext = plaintext.substring(0, ApiToken.HASHED_TOKEN_PREFIX.length() + ApiTokenService.TOKEN_BODY_LENGTH);

        ApiToken row = ApiToken.builder()
                .user(userRepository.findByUsername(username).orElseThrow())
                .name(name)
                .tokenHash(ApiTokenService.sha256Hex(plaintext))
                .tokenPrefix(plaintext.substring(ApiToken.HASHED_TOKEN_PREFIX.length(),
                        ApiToken.HASHED_TOKEN_PREFIX.length() + 8))
                .scopes(String.join(" ", ApiTokenService.DEFAULT_SCOPES))
                .createdAt(createdAt)
                .build();
        return apiTokenRepository.saveAndFlush(row);
    }
}
