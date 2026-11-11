package com.wiki4ai.service;

import com.wiki4ai.config.JwtAuthenticationFilter.ApiTokenAuthenticationDetails;
import com.wiki4ai.model.Role;
import com.wiki4ai.model.User;
import com.wiki4ai.repository.UserRepository;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * One definition of "this caller may act as an administrator"
 * (WIKI4AI-197, REV-2 P0 #5).
 *
 * <p><b>The hole.</b> Every administrative check in the application asked one question:
 * does the account behind this username have the ADMIN role? That is the right question
 * about an <i>account</i> and the wrong question about a <i>request</i>, because the
 * request may have arrived with an API token, and an API token declares what it may do in
 * its own {@code scopes} column. A token defaults to {@code read} + {@code write}
 * ({@code ApiTokenService.DEFAULT_SCOPES}), so an administrator who created a token for a
 * read-only integration got a credential that could do anything an administrator can do:
 * the role was checked, the scopes were not, and the role won. Deleting a project,
 * rewriting another user's role or running the embedding backfill needed only that the
 * <i>account</i> was an admin — the token's declared scope was decoration.
 *
 * <p><b>The rule.</b> ADMIN role <b>and</b> (the caller is not an API token, or the token
 * carries the {@code admin} scope). Both halves are load-bearing:
 * <ul>
 *   <li>Drop the first and a token would outrank the account, so a token could be
 *       granted administrative power for an account that never had it.</li>
 *   <li>Drop the second and revoking a leaked administrator token would not stop it: the
 *       credential would keep acting with its owner's full authority no matter what its
 *       scopes say, which is exactly the complaint this closes.</li>
 * </ul>
 *
 * <p><b>Why one helper.</b> Four places decided this independently
 * ({@code PermissionService.isAdminRole}, {@code AdminService.verifyAdminRole},
 * {@code EmbeddingService.verifyAdminRole}, {@code MigrationService.verifyAdminRole}) and
 * each had its own wording of the same question. Four definitions drift; the drift is
 * what produced this bug in the first place. One component, one rule, one message.
 *
 * <p><b>What is deliberately not changed here.</b> {@code ResourceService} already had a
 * scope-aware check of its own ({@code requireResourceAdmin}: ADMIN role <i>or</i>
 * {@code admin} scope). It is more permissive than this rule, and it is the one place
 * that was already considering scopes, so it is left as it is rather than silently
 * tightened inside a commit whose job is to make the other four agree.
 *
 * <p><b>Deployment impact.</b> Tokens default to {@code read}+{@code write}, so an
 * existing administrator's ordinary token now gets 403 on administrative endpoints instead
 * of silently succeeding. That is the fix, not a regression — but it is a behaviour
 * change an operator has to be told about: an administrative integration must be issued a
 * token that includes the {@code admin} scope. The MCP server calls no admin, token or
 * embedding endpoint, so it is unaffected.
 */
@Component
public class EffectiveAdmin {

    private final UserRepository userRepository;

    public EffectiveAdmin(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * The verdict for one username on the current request.
     *
     * @param allowed whether the caller may act as an administrator here
     * @param message {@code null} when allowed; otherwise the reason to report, phrased
     *                so the reader knows what to do next
     */
    public record Verdict(boolean allowed, String message) {

        static Verdict allow() {
            return new Verdict(true, null);
        }
    }

    /**
     * Decide administrative authority for {@code username} using the credential the
     * current request arrived with.
     *
     * @param username the authenticated account, as resolved by the caller
     * @return the verdict; never throws, so each caller keeps its own failure shape —
     *         {@code PermissionService} needs a boolean that falls through to
     *         project-level checks, the admin endpoints need an exception
     */
    public Verdict check(String username) {
        if (username == null || username.isBlank()) {
            return new Verdict(false, "Authentication required. Please provide a valid JWT token.");
        }

        User user = userRepository.findByUsername(username).orElse(null);
        if (user == null) {
            return new Verdict(false, "Authenticated user not found in database");
        }
        if (user.getRole() != Role.ADMIN) {
            // Wording kept exactly as the three call sites had it: tests and clients read
            // "Current role: X" to decide what to do.
            return new Verdict(false, "Admin access required. Current role: " + user.getRole());
        }

        if (credentialMayCarryAdmin()) {
            // Either a signed-in session — the credential is the identity, and the
            // identity is an administrator — or a token that declares the admin scope.
            return Verdict.allow();
        }

        // Same wording requireCredentialMayCarryAdmin() throws, so the rule reads as one
        // sentence wherever it is asked.
        requireCredentialMayCarryAdmin();
        return Verdict.allow();
    }

    /**
     * Whether {@code username} may act as an administrator on the current request.
     * <p>
     * The shape {@code PermissionService} needs: a boolean, because a "no" there is not
     * an error — it means fall through to the project-level grants.
     */
    public boolean hasAuthority(String username) {
        return check(username).allowed();
    }

    /**
     * Whether the credential this request arrived with is allowed to carry administrative
     * authority at all — the second half of the rule, for a caller that has already
     * established the ADMIN role and wants only the part it cannot decide alone.
     * <p>
     * Returns true for a signed-in session (no scopes are declared, so none can be
     * missing) and for a token that carries the {@code admin} scope.
     *
     * @return true when the credential may act administratively
     */
    public boolean credentialMayCarryAdmin() {
        Optional<ApiTokenAuthenticationDetails> token = currentApiToken();
        if (token.isEmpty()) {
            return true;
        }
        List<String> scopes = token.get().scopes() != null ? token.get().scopes() : List.of();
        return scopes.contains(ApiTokenService.SCOPE_ADMIN);
    }

    /**
     * Throw when the credential may not carry administrative authority
     * ({@link #credentialMayCarryAdmin()} said no).
     * <p>
     * For the three endpoints that already verify the ADMIN role and report it in their
     * own established wording: they keep that wording — clients and tests read
     * "Admin access required. Current role: X" — and this adds the half they were
     * missing, so the message here is only ever about the credential.
     *
     * @throws SecurityException when the request carries an API token without the
     *                           {@code admin} scope
     */
    public void requireCredentialMayCarryAdmin() {
        Optional<ApiTokenAuthenticationDetails> token = currentApiToken();
        if (token.isEmpty() || credentialMayCarryAdmin()) {
            return;
        }
        List<String> scopes = token.get().scopes() != null ? token.get().scopes() : List.of();
        throw new SecurityException("Admin access required: this request arrived with API token "
                + token.get().tokenId() + ", which carries "
                + (scopes.isEmpty() ? "no scopes" : scopes.toString())
                + " and not the '" + ApiTokenService.SCOPE_ADMIN + "' scope. The account has the ADMIN role, "
                + "but a token acts only within its own scopes — issue a token that includes '"
                + ApiTokenService.SCOPE_ADMIN + "'.");
    }

    /**
     * The credential this request authenticated with, when it was a hashed token.
     * Exposed because "is the caller a token" is the same question several rules ask, and
     * it is answered in one place ({@link ApiTokenAuthenticationDetails#from}).
     */
    public Optional<ApiTokenAuthenticationDetails> currentApiToken() {
        return ApiTokenAuthenticationDetails.from(SecurityContextHolder.getContext().getAuthentication());
    }
}
