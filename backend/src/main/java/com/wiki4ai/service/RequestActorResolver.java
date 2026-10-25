package com.wiki4ai.service;

import com.wiki4ai.config.JwtAuthenticationFilter.ApiTokenAuthenticationDetails;
import com.wiki4ai.model.Actor;
import com.wiki4ai.model.User;
import com.wiki4ai.repository.ActorRepository;
import com.wiki4ai.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Resolves WHO is acting on the current request, from the authenticated request
 * context only (WIKI4AI-134).
 * <p>
 * This is the single place the attribution columns get their value from, and the
 * whole point of the class is what it deliberately does NOT read: a request body
 * field, a query parameter and a header are all ignored, because any of them can
 * be set by the caller. "An agent cannot act as another actor" is only true if
 * the actor id travels one way — from the credential that was verified by
 * {@link com.wiki4ai.config.JwtAuthenticationFilter} into the
 * {@link SecurityContextHolder} — and nowhere else.
 * <p>
 * Two kinds of caller:
 * <ul>
 *   <li><b>agent</b> — a {@code w4a_} token bound to an actor. The token carries
 *       {@code actorId}; that id, and only that id, is used.</li>
 *   <li><b>human</b> — a legacy JWT, or a token with no actor bound. The token
 *       carries no actor, so the caller is attributed through their own
 *       {@code kind='human'} actor when they have one, and through nothing
 *       (null) when they do not. Null is a normal outcome, never an error.</li>
 * </ul>
 * A legacy JWT has {@code getDetails() == null} by design — that is the existing
 * behaviour of the filter and its regression tests — so the "no details" branch
 * is the common case, not an edge case.
 */
@Component
@RequiredArgsConstructor
public class RequestActorResolver {

    private final ActorRepository actorRepository;
    private final UserRepository userRepository;

    /**
     * What the verified credential says about the caller: the user behind the
     * token, the actor the token acts as (null when unbound) and the token's
     * scopes. Immutable, and built only from {@link Authentication#getDetails()}.
     *
     * @param userId  user id carried by the hashed API token, or null (legacy JWT)
     * @param actorId actor the token acts as, or null
     * @param scopes  token scopes; empty when the caller has no API-token details
     */
    public record CallerIdentity(Long userId, Long actorId, List<String> scopes) {

        /** The identity of a caller that carries no API-token details. */
        public static CallerIdentity none() {
            return new CallerIdentity(null, null, List.of());
        }
    }

    /**
     * The identity carried by the authenticated request, read exclusively from
     * the authentication details. Never from the body, a parameter or a header.
     *
     * @return the identity, or {@link CallerIdentity#none()} for a legacy JWT or
     *         an unauthenticated caller
     */
    public CallerIdentity currentIdentity() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return CallerIdentity.none();
        }
        Object details = authentication.getDetails();
        if (!(details instanceof ApiTokenAuthenticationDetails apiToken)) {
            // Legacy JWT (details are null) and any other authentication kind:
            // the credential simply does not name an actor. Not an error.
            return CallerIdentity.none();
        }
        return new CallerIdentity(apiToken.userId(), apiToken.actorId(),
                apiToken.scopes() != null ? List.copyOf(apiToken.scopes()) : List.of());
    }

    /**
     * The actor id the verified credential names, or null. This is the value the
     * attribution columns use for an agent.
     */
    public Long currentActorId() {
        return currentIdentity().actorId();
    }

    /**
     * The actor id to attribute a write to.
     * <p>
     * Order matters and is the security rule: the token's own actor wins,
     * unconditionally — an agent is attributed to the actor its credential names,
     * never to anything else. Only when the credential names no actor at all (a
     * human on a legacy JWT or an unbound token) does the caller's own
     * {@code kind='human'} actor apply. A human never borrows someone else's
     * actor: the lookup is restricted to actors owned by that user.
     *
     * @param username authenticated principal name ("anonymous" resolves to nobody)
     * @return the actor id to record, or null when there is nothing to record
     */
    public Long currentAttributionActorId(String username) {
        Long fromCredential = currentActorId();
        if (fromCredential != null) {
            return fromCredential;
        }
        return humanActorIdOf(username);
    }

    /**
     * The {@code kind='human'} actor belonging to this user, or null when the
     * user has none (or is anonymous). Deterministic pick when a user registered
     * several human actors: the lowest id, i.e. the first one created.
     */
    public Long humanActorIdOf(String username) {
        User user = resolveUser(username);
        if (user == null || user.getId() == null) {
            return null;
        }
        return actorRepository
                .findFirstByOwnerUserIdAndKindOrderByIdAsc(user.getId(), Actor.KIND_HUMAN)
                .map(Actor::getId)
                .orElse(null);
    }

    /**
     * Resolve the requesting user from the authenticated username. Returns null
     * for anonymous callers (test mode with security disabled), exactly like the
     * {@code resolveUser} helpers of the services this class serves.
     */
    private User resolveUser(String username) {
        if (username == null || username.isBlank()
                || "anonymous".equals(username) || "anonymousUser".equals(username)) {
            return null;
        }
        return userRepository.findByUsername(username).orElse(null);
    }
}
