package com.wiki4ai.service;

import com.wiki4ai.dto.ApiTokenCreatedDTO;
import com.wiki4ai.dto.TokenCreateRequestDTO;
import com.wiki4ai.exception.RateLimitExceededException;
import com.wiki4ai.model.ApiToken;
import com.wiki4ai.model.Role;
import com.wiki4ai.model.User;
import com.wiki4ai.repository.ApiTokenRepository;
import com.wiki4ai.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The rate limit of WIKI4AI-139 as seen through {@link ApiTokenService}, against a
 * real (H2) token store — with the budget deliberately set to 2 per second so the
 * breach is reached in three calls.
 * <p>
 * The three claims this has to prove, and that a mocked test cannot prove:
 * <ol>
 *   <li>the limit is <b>per token</b>: a second token of the same user is untouched
 *       while the first one is throttled;</li>
 *   <li>it is applied <b>after</b> validity: an unknown, expired or revoked token is
 *       still a 401 (an empty Optional), never a 429;</li>
 *   <li>the legacy clear-text JWT path is <b>not</b> rate limited — deliberately, so
 *       existing clients and existing tests keep their current behaviour.</li>
 * </ol>
 * A dedicated context (its own property source) keeps the low budget away from every
 * other test class, which run with the production default of 30 per second.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "wiki4ai.rate-limit.requests-per-second=2")
@DisplayName("ApiTokenService per-token rate limit (WIKI4AI-139)")
class ApiTokenServiceRateLimitTest {

    private static final String ALICE = "rl_alice";
    private static final String BOB = "rl_bob";

    /** Budget this context runs with — see @TestPropertySource. */
    private static final int LIMIT = 2;

    @Autowired
    private ApiTokenService apiTokenService;

    @Autowired
    private TokenRateLimiter tokenRateLimiter;

    @Autowired
    private ApiTokenRepository apiTokenRepository;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        apiTokenRepository.deleteAll();
        for (String name : List.of(ALICE, BOB)) {
            userRepository.findByUsername(name).ifPresent(userRepository::delete);
        }
        saveUser(ALICE, Role.USER);
        saveUser(BOB, Role.USER);

        // Both in-memory stores are per-context singletons: never inherit a previous
        // test's cached validation or charged window.
        apiTokenService.clearValidationCache();
        tokenRateLimiter.reset();
    }

    private User saveUser(String username, Role role) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@test.local");
        user.setPassword("not-a-real-hash");
        user.setRole(role);
        userRepository.save(user);
        return user;
    }

    private String createToken(String name, String username) {
        return apiTokenService.createToken(
                TokenCreateRequestDTO.builder().name(name).build(), username).getToken();
    }

    /** The configured budget really is the low one — the test is not silently testing 30. */
    @Test
    @DisplayName("The context really runs with the low budget")
    void budgetIsTheConfiguredOne() {
        assertThat(tokenRateLimiter.getRequestsPerWindow()).isEqualTo(LIMIT);
        assertThat(tokenRateLimiter.getWindowMillis()).isEqualTo(1_000L);
    }

    @Test
    @DisplayName("The request over the token's budget throws RateLimitExceededException")
    void shouldRefuseRequestsOverBudget() {
        String token = createToken("rl-1", ALICE);

        assertThat(apiTokenService.authenticate(token)).isPresent();
        assertThat(apiTokenService.authenticate(token)).isPresent();

        assertThatThrownBy(() -> apiTokenService.authenticate(token))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessageContaining("limit exceeded");
    }

    @Test
    @DisplayName("Cached validations are charged too — a cache hit is still a request")
    void cacheHitsShouldConsumeBudgetToo() {
        String token = createToken("rl-cache", ALICE);

        // First call goes to the database, the rest are served from the validation
        // cache. If the cache path skipped the limiter, the limit would be unenforceable
        // for exactly the tokens that are used the most.
        apiTokenService.authenticate(token);
        assertThat(apiTokenService.validationCacheSize()).isEqualTo(1);

        assertThat(apiTokenService.authenticate(token)).isPresent();
        assertThatThrownBy(() -> apiTokenService.authenticate(token))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    @DisplayName("Another token of the same user is not affected by the breach")
    void otherTokenOfSameUserShouldBeUnaffected() {
        String noisy = createToken("rl-noisy", ALICE);
        String quiet = createToken("rl-quiet", ALICE);

        apiTokenService.authenticate(noisy);
        apiTokenService.authenticate(noisy);
        assertThatThrownBy(() -> apiTokenService.authenticate(noisy))
                .isInstanceOf(RateLimitExceededException.class);

        // Same user, same moment, different credential: full budget still available.
        assertThat(apiTokenService.authenticate(quiet)).isPresent();
        assertThat(apiTokenService.authenticate(quiet)).isPresent();

        // And the throttled token is still throttled — the other one did not pay for it.
        assertThatThrownBy(() -> apiTokenService.authenticate(noisy))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    @DisplayName("Another user's token is not affected either")
    void otherUserShouldBeUnaffected() {
        String alices = createToken("rl-alice", ALICE);
        String bobs = createToken("rl-bob", BOB);

        apiTokenService.authenticate(alices);
        apiTokenService.authenticate(alices);
        assertThatThrownBy(() -> apiTokenService.authenticate(alices))
                .isInstanceOf(RateLimitExceededException.class);

        Optional<ApiTokenService.ApiTokenPrincipal> principal = apiTokenService.authenticate(bobs);
        assertThat(principal).isPresent();
        assertThat(principal.get().username()).isEqualTo(BOB);
    }

    @Test
    @DisplayName("An invalid token is refused (401 path), never throttled (429)")
    void invalidTokensShouldNotBeRateLimited() {
        String neverIssued = ApiToken.HASHED_TOKEN_PREFIX + "a".repeat(ApiTokenService.TOKEN_BODY_LENGTH);

        // Far more attempts than the budget allows — each is simply an empty Optional.
        for (int i = 0; i < LIMIT + 5; i++) {
            assertThat(apiTokenService.authenticate(neverIssued)).isEmpty();
        }

        // No budget is charged for a token that is not valid, so the throttle cannot be
        // used to probe which tokens exist.
        assertThat(tokenRateLimiter.trackedKeys()).isZero();

        // A real token of the same shape still authenticates right afterwards.
        assertThat(apiTokenService.authenticate(createToken("rl-after-probe", ALICE))).isPresent();
    }

    @Test
    @DisplayName("A revoked token is refused, not throttled")
    void revokedTokenShouldNotBeRateLimited() {
        ApiTokenCreatedDTO created = apiTokenService.createToken(
                TokenCreateRequestDTO.builder().name("rl-revoked").build(), ALICE);
        apiTokenService.revokeToken(created.getMetadata().getId(), ALICE);

        assertThatCode(() -> {
            for (int i = 0; i < LIMIT + 5; i++) {
                assertThat(apiTokenService.authenticate(created.getToken())).isEmpty();
            }
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("A legacy clear-text JWT value is never rate limited")
    void legacyJwtShouldNeverBeRateLimited() {
        // The legacy path is signature-verified in JwtAuthenticationFilter and never
        // reaches ApiTokenService at all. Left unthrottled on purpose: throttling it
        // would change behaviour existing clients and tests depend on.
        String legacyValue = "header.payload.signature";
        apiTokenRepository.saveAndFlush(ApiToken.builder()
                .user(userRepository.findByUsername(ALICE).orElseThrow())
                .name("legacy-jwt")
                .tokenValue(legacyValue)
                .createdAt(java.time.LocalDateTime.now())
                .build());

        assertThatCode(() -> {
            for (int i = 0; i < LIMIT + 10; i++) {
                assertThat(apiTokenService.authenticate(legacyValue)).isEmpty();
            }
        }).doesNotThrowAnyException();

        assertThat(tokenRateLimiter.trackedKeys()).isZero();
    }

    @Test
    @DisplayName("A throttled request leaves no half-done state: the token is still valid and its row untouched")
    void throttlingShouldNotDamageTheToken() {
        String token = createToken("rl-damage", ALICE);
        apiTokenService.authenticate(token);
        apiTokenService.authenticate(token);
        assertThatThrownBy(() -> apiTokenService.authenticate(token))
                .isInstanceOf(RateLimitExceededException.class);

        ApiToken row = apiTokenRepository.findByTokenHash(ApiTokenService.sha256Hex(token)).orElseThrow();
        assertThat(row.getRevokedAt()).isNull();
        assertThat(row.isExpired()).isFalse();
        assertThat(row.getLastUsedAt()).isNotNull();

        // The budget is a window, not a penalty: once it slides, the same token works.
        long waited = 0L;
        Optional<ApiTokenService.ApiTokenPrincipal> principal = Optional.empty();
        while (waited < 5_000L && principal.isEmpty()) {
            try {
                principal = apiTokenService.authenticate(token);
            } catch (RateLimitExceededException stillLimited) {
                try {
                    Thread.sleep(250L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
                waited += 250L;
            }
        }

        assertThat(principal).as("the same token works again after its window slid").isPresent();
    }
}
