package com.wiki4ai.service;

import java.util.Locale;
import java.util.Set;

/**
 * Account names a deployment can never hand out, because the application already uses
 * them as internal markers (WIKI4AI-197, REV-2 P0 #4).
 *
 * <p><b>The hole.</b> Roughly fifteen controllers resolve the caller like this:
 * {@code authentication != null ? authentication.getName() : "anonymous"}. The literal
 * is a <i>sentinel</i> — it means "nobody authenticated", and permission checks compare
 * against it. But {@code sub} is just a claim in a JWT, so an account literally named
 * {@code anonymous} produced an authentication whose name was the sentinel, and every
 * one of those comparisons took the unauthenticated branch for a caller who <i>had</i>
 * authenticated. Whoever registered that name got whatever an anonymous visitor gets,
 * and — more usefully for an attacker — whatever the code does when it believes nobody
 * is present.
 *
 * <p><b>Why the fix is here and not at the fifteen sites.</b> The obvious repair is to
 * replace the sentinel with {@code null} or an {@code Optional} everywhere. That is a
 * large refactor across many services and many tests, and it mixes a behaviour change
 * with a security fix, which makes a regression hard to attribute. Refusing the name is
 * one rule in two places — every write of a username, and every authentication — and it
 * makes the sentinel impossible to forge without touching those fifteen files at all.
 *
 * <p><b>Both ends are required.</b> Rejecting the name only at registration does not
 * help a row that already exists (created before this rule, or imported), and it does
 * nothing about a token minted earlier that still carries the reserved {@code sub}.
 * Rejecting only at authentication would let someone create the account and then be
 * permanently locked out of it with no explanation at creation time.
 *
 * <p>{@code anonymousUser} is on the list because it is Spring Security's own default
 * name for its anonymous authentication filter; a deployment that later enables that
 * filter would otherwise collide with a real account of that name. Matching is
 * case-insensitive, because the username uniqueness this application enforces is
 * case-sensitive and {@code Anonymous} would otherwise slip past a check written against
 * the lowercase literal.
 */
public final class ReservedUsernames {

    /**
     * The sentinel the controllers fall back to. Public because the check that matters
     * is "is this name the sentinel", and the sentinel's value is the answer.
     */
    public static final String SENTINEL = "anonymous";

    /** Lowercase forms that may never be an account name. */
    private static final Set<String> RESERVED_LOWER = Set.of("anonymous", "anonymoususer");

    /**
     * The reserved names in the lowercase form the set is keyed by. Exposed so a query
     * can filter on them without restating the literals — a second copy of the list in
     * JPQL is a list that drifts.
     */
    public static Set<String> allReservedLowercase() {
        return RESERVED_LOWER;
    }

    private ReservedUsernames() {
    }

    /**
     * Whether {@code username} collides with an internal marker.
     *
     * @param username the name to test; {@code null} and blank are not reserved, they
     *                 are simply not names, and callers that need to reject those do so
     *                 with their own validation
     * @return true when the name must not be used as an account name or trusted as a
     *         subject
     */
    public static boolean isReserved(String username) {
        if (username == null) {
            return false;
        }
        return RESERVED_LOWER.contains(username.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Reject a reserved name on the way <b>in</b> — every write of a username.
     * <p>
     * Throws {@link IllegalArgumentException}, which is what the neighbouring
     * "Username is already taken" checks throw too, so a caller sees one handler and one
     * body shape for "that name will not work here". The message names the offending
     * value and says why, because "reserved" without the name is not actionable.
     *
     * @param username the name being written
     * @throws IllegalArgumentException when the name is reserved
     */
    public static void rejectIfReserved(String username) {
        if (isReserved(username)) {
            throw new IllegalArgumentException(
                    "Username '" + username + "' is reserved: it is the internal marker this "
                            + "application uses for an unauthenticated caller, so an account with that "
                            + "name could never be told apart from nobody. Choose another name.");
        }
    }
}
