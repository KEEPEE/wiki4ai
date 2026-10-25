package com.wiki4ai.service;

import com.wiki4ai.config.JwtAuthenticationFilter.ApiTokenAuthenticationDetails;
import com.wiki4ai.model.Actor;
import com.wiki4ai.model.Role;
import com.wiki4ai.model.User;
import com.wiki4ai.repository.ActorRepository;
import com.wiki4ai.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RequestActorResolver} (WIKI4AI-134).
 * <p>
 * The class has exactly one job: say which actor the current request acts as,
 * and say it <b>only</b> from the verified authentication details. These tests
 * pin both halves of that promise — what the resolver reads, and what it must
 * never read.
 */
class RequestActorResolverTest {

    private ActorRepository actorRepository;
    private UserRepository userRepository;
    private RequestActorResolver resolver;

    @BeforeEach
    void setUp() {
        actorRepository = mock(ActorRepository.class);
        userRepository = mock(UserRepository.class);
        resolver = new RequestActorResolver(actorRepository, userRepository);
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private void authenticate(String username, Object details) {
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                username, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        authentication.setDetails(details);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private User user(Long id, String username) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setEmail(username + "@test.local");
        user.setPassword("not-a-real-hash");
        user.setRole(Role.USER);
        return user;
    }

    private Actor actor(Long id, String handle, String kind, Long ownerUserId) {
        Actor actor = new Actor();
        actor.setId(id);
        actor.setHandle(handle);
        actor.setKind(kind);
        actor.setOwnerUserId(ownerUserId);
        return actor;
    }

    // ── What the resolver reads ───────────────────────────────────────────────

    @Nested
    @DisplayName("currentIdentity / currentActorId — the credential is the only source")
    class CredentialIsTheOnlySource {

        @Test
        @DisplayName("An API token that names an actor yields exactly that actor id")
        void agentTokenCarriesItsOwnActorId() {
            authenticate("agent-user",
                    new ApiTokenAuthenticationDetails(11L, 7L, 42L, List.of("read", "write")));

            assertThat(resolver.currentActorId()).isEqualTo(42L);
            assertThat(resolver.currentIdentity().userId()).isEqualTo(7L);
            assertThat(resolver.currentIdentity().scopes()).containsExactly("read", "write");
        }

        @Test
        @DisplayName("A hashed token with no actor bound yields no actor")
        void unboundTokenYieldsNoActor() {
            authenticate("human-user",
                    new ApiTokenAuthenticationDetails(12L, 7L, null, List.of("read")));

            assertThat(resolver.currentActorId()).isNull();
            assertThat(resolver.currentIdentity().userId()).isEqualTo(7L);
        }

        @Test
        @DisplayName("Legacy JWT (getDetails() == null) yields no actor — and no exception")
        void legacyJwtHasNoActor() {
            authenticate("legacy-user", null);

            RequestActorResolver.CallerIdentity identity = resolver.currentIdentity();
            assertThat(identity.actorId()).isNull();
            assertThat(identity.userId()).isNull();
            assertThat(identity.scopes()).isEmpty();
        }

        @Test
        @DisplayName("A details object of another type is ignored, not an error")
        void foreignDetailsTypeIsIgnored() {
            authenticate("other-user", new WebAuthenticationDetailsSource().buildDetails(
                    new MockHttpServletRequest()));

            assertThat(resolver.currentActorId()).isNull();
            assertThat(resolver.currentIdentity().scopes()).isEmpty();
        }

        @Test
        @DisplayName("An unauthenticated caller yields no actor")
        void noAuthenticationYieldsNoActor() {
            assertThat(resolver.currentIdentity().actorId()).isNull();
            assertThat(resolver.currentActorId()).isNull();
        }
    }

    // ── What the resolver must never read ─────────────────────────────────────

    @Nested
    @DisplayName("Nothing on the request can name an actor")
    class RequestInputIsNeverRead {

        @Test
        @DisplayName("A request attribute and a header naming an actor are ignored")
        void requestAttributeAndHeaderCannotInjectAnActor() {
            // The same attribute name the auth filter uses, and a plausible header —
            // both carry an actor id the credential does NOT carry.
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setAttribute(ApiTokenAuthenticationDetails.REQUEST_ATTRIBUTE,
                    new ApiTokenAuthenticationDetails(1L, 2L, 999L, List.of("admin")));
            request.setAttribute("createdByActorId", 999L);
            request.addHeader("X-Actor-Id", "999");
            request.setParameter("createdByActorId", "999");
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

            // Legacy JWT: authenticated, but the credential names no actor.
            authenticate("legacy-user", null);
            when(userRepository.findByUsername("legacy-user")).thenReturn(Optional.empty());

            assertThat(resolver.currentActorId())
                    .as("the resolver must not read the request attribute")
                    .isNull();
            assertThat(resolver.currentAttributionActorId("legacy-user"))
                    .as("the resolver must not read headers or query parameters")
                    .isNull();
        }

        @Test
        @DisplayName("The credential's actor wins over anything the request claims")
        void credentialActorWinsOverRequestClaims() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setAttribute(ApiTokenAuthenticationDetails.REQUEST_ATTRIBUTE,
                    new ApiTokenAuthenticationDetails(1L, 2L, 999L, List.of("admin")));
            request.addHeader("X-Actor-Id", "999");
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

            authenticate("agent-user",
                    new ApiTokenAuthenticationDetails(11L, 7L, 42L, List.of("read", "write")));

            assertThat(resolver.currentAttributionActorId("agent-user")).isEqualTo(42L);
        }
    }

    // ── Rule E: a human acts as their own human actor ─────────────────────────

    @Nested
    @DisplayName("currentAttributionActorId — human branch")
    class HumanAttribution {

        @Test
        @DisplayName("A user with their own human actor is attributed to it")
        void humanIsAttributedToTheirOwnHumanActor() {
            authenticate("alice", null);
            when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user(7L, "alice")));
            when(actorRepository.findFirstByOwnerUserIdAndKindOrderByIdAsc(7L, Actor.KIND_HUMAN))
                    .thenReturn(Optional.of(actor(55L, "alice-human", Actor.KIND_HUMAN, 7L)));

            assertThat(resolver.currentAttributionActorId("alice")).isEqualTo(55L);
        }

        @Test
        @DisplayName("A user without a human actor is attributed to nobody (null, not an error)")
        void humanWithoutHumanActorStaysNull() {
            authenticate("bob", null);
            when(userRepository.findByUsername("bob")).thenReturn(Optional.of(user(8L, "bob")));
            when(actorRepository.findFirstByOwnerUserIdAndKindOrderByIdAsc(8L, Actor.KIND_HUMAN))
                    .thenReturn(Optional.empty());

            assertThat(resolver.currentAttributionActorId("bob")).isNull();
        }

        @Test
        @DisplayName("An agent's own actor beats the user's human actor")
        void agentActorBeatsHumanActorFallback() {
            authenticate("alice",
                    new ApiTokenAuthenticationDetails(11L, 7L, 42L, List.of("read", "write")));
            when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user(7L, "alice")));
            when(actorRepository.findFirstByOwnerUserIdAndKindOrderByIdAsc(7L, Actor.KIND_HUMAN))
                    .thenReturn(Optional.of(actor(55L, "alice-human", Actor.KIND_HUMAN, 7L)));

            // The lookup for the human actor must not even decide the answer:
            // the credential's actor is the attribution.
            assertThat(resolver.currentAttributionActorId("alice")).isEqualTo(42L);
        }

        @Test
        @DisplayName("Anonymous callers resolve to no user and no actor")
        void anonymousCallerHasNoActor() {
            assertThat(resolver.currentAttributionActorId("anonymous")).isNull();
            assertThat(resolver.currentAttributionActorId(null)).isNull();
        }
    }
}
