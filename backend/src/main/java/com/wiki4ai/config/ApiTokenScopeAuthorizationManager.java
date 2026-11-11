package com.wiki4ai.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.http.HttpMethod;

import java.util.List;
import java.util.function.Supplier;

/**
 * Enforces the scopes of a hashed API token on the request it authenticates
 * (WIKI4AI-197, REV-2 P0 #5).
 *
 * <p><b>The hole.</b> A {@code w4a_} token row carries a {@code scopes} column, the
 * filter turns each scope into a {@code SCOPE_*} authority, and the API lets a caller
 * choose those scopes when creating a token — so a token could be created with
 * {@code ["read"]} and the operator had every reason to believe it was a read-only
 * credential. But only two methods in the whole application read the
 * {@code SCOPE_*} authorities, both in {@code ResourceService}. Everything else asked
 * only "is the caller authenticated?", which every scope answers the same way. A
 * read-only token could therefore {@code DELETE} a document, and the scope that was
 * supposed to prevent it was decoration.
 *
 * <p><b>Why an {@code AuthorizationManager} and not per-endpoint annotations.</b> The
 * rule is uniform — verb against scope — and it must hold for endpoints that exist today
 * and for endpoints added next week. Annotations are opt-in, and an endpoint someone
 * forgets to annotate is a hole that looks like it is covered. One manager on
 * {@code anyRequest()} cannot be forgotten.
 *
 * <p><b>Web sessions are deliberately untouched.</b> A signed-in user's JWT carries no
 * scopes at all: the session <i>is</i> the identity, and the frontend would break if a
 * browser session suddenly needed a {@code write} scope to save a page. So the manager
 * only constrains requests whose authentication carries
 * {@link JwtAuthenticationFilter.ApiTokenAuthenticationDetails}, and allows everything
 * else through. That asymmetry is the whole design: scopes are a property of the
 * credential, and only the credential that declares them is bound by them.
 *
 * <p><b>The rule.</b> {@code read} answers GET and HEAD. {@code write} is required for
 * every mutating method. {@code admin} satisfies both, because it is the top of the
 * ladder and a token granted only {@code admin} would otherwise be unable to act at all.
 * A token with no scopes can read nothing and change nothing — an empty scope list is
 * read as "nothing granted", never as "everything".
 *
 * <p><b>Denials carry a reason.</b> {@link AuthorizationDecision} has no field for one,
 * and a bare 403 tells an agent nothing about which scope to ask for, so the manager
 * leaves the reason on the request under {@link #DENIAL_REASON_ATTRIBUTE} and the access
 * denied handler configured in {@code SecurityConfig} puts it in the JSON body.
 */
public class ApiTokenScopeAuthorizationManager
        implements AuthorizationManager<RequestAuthorizationContext> {

    /** Request attribute holding the human-readable reason for the last scope denial. */
    public static final String DENIAL_REASON_ATTRIBUTE = "wiki4ai.scopeDenialReason";

    /**
     * Methods that change nothing. Everything not listed here is treated as mutating,
     * which is the safe direction for a list someone may not update: an unfamiliar verb
     * demands {@code write} rather than getting in for free.
     * <p>
     * {@code OPTIONS} never reaches here — CORS preflight is permitted earlier in the
     * chain, before any authentication exists.
     */
    private static boolean isSafeMethod(HttpMethod method) {
        return method == HttpMethod.GET || method == HttpMethod.HEAD;
    }

    @Override
    public AuthorizationDecision check(Supplier<Authentication> authenticationSupplier,
                                       RequestAuthorizationContext context) {
        Authentication authentication = authenticationSupplier.get();

        // Replicates what anyRequest().authenticated() meant, so replacing that rule with
        // this one does not quietly admit an unauthenticated caller. Anonymous is
        // rejected explicitly because Spring marks its anonymous token authenticated.
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return new AuthorizationDecision(false);
        }

        var details = JwtAuthenticationFilter.ApiTokenAuthenticationDetails.from(authentication);
        if (details.isEmpty()) {
            // A web session: no scopes declared, nothing to enforce.
            return new AuthorizationDecision(true);
        }

        List<String> scopes = details.get().scopes() != null
                ? details.get().scopes()
                : List.of();
        HttpMethod method = HttpMethod.valueOf(context.getRequest().getMethod());

        if (scopes.contains(com.wiki4ai.service.ApiTokenService.SCOPE_ADMIN)) {
            return new AuthorizationDecision(true);
        }

        if (isSafeMethod(method)) {
            if (scopes.contains(com.wiki4ai.service.ApiTokenService.SCOPE_READ)) {
                return new AuthorizationDecision(true);
            }
            return deny(context.getRequest(), method,
                    com.wiki4ai.service.ApiTokenService.SCOPE_READ, scopes);
        }

        if (scopes.contains(com.wiki4ai.service.ApiTokenService.SCOPE_WRITE)) {
            return new AuthorizationDecision(true);
        }
        return deny(context.getRequest(), method,
                com.wiki4ai.service.ApiTokenService.SCOPE_WRITE, scopes);
    }

    /**
     * Record why this request is being refused and refuse it.
     * <p>
     * The message names the scope that is missing and lists the scopes the token does
     * have, because the actionable next step for an agent is to be issued a token with a
     * different scope — and the only way it can know that is if the answer says so.
     */
    private AuthorizationDecision deny(HttpServletRequest request,
                                       HttpMethod method,
                                       String requiredScope,
                                       List<String> heldScopes) {
        request.setAttribute(DENIAL_REASON_ATTRIBUTE,
                "This API token lacks the '" + requiredScope + "' scope required for " + method
                        + ". The token carries " + (heldScopes.isEmpty() ? "no scopes" : heldScopes)
                        + "; create a token that includes '" + requiredScope + "'.");
        return new AuthorizationDecision(false);
    }
}
