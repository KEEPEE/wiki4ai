package com.wiki4ai.service;

import com.wiki4ai.dto.ApiTokenCreatedDTO;
import com.wiki4ai.dto.ApiTokenDTO;
import com.wiki4ai.dto.TokenCreateRequestDTO;
import com.wiki4ai.exception.BadRequestException;
import com.wiki4ai.exception.RateLimitExceededException;
import com.wiki4ai.model.Actor;
import com.wiki4ai.model.ApiToken;
import com.wiki4ai.model.Role;
import com.wiki4ai.model.User;
import com.wiki4ai.repository.ActorRepository;
import com.wiki4ai.repository.ApiTokenRepository;
import com.wiki4ai.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service layer for API tokens (WIKI4AI-135 / WIKI4AI-136).
 * <p>
 * A token created here is an opaque secret: {@code w4a_} + 32 base62 characters.
 * The plaintext is returned exactly once — in the creation response — and is never
 * stored, never listed and never logged. The database keeps only its SHA-256 hex
 * digest ({@code tokenHash}) and a short display prefix ({@code tokenPrefix}), so a
 * leaked table cannot hand out working credentials.
 * <p>
 * Because the plaintext is not stored, revocation is real: the auth filter looks the
 * token up by hash and refuses it when {@code revokedAt} is set or {@code expiresAt}
 * has passed. That is the whole point of the rework — the legacy clear-text JWT path
 * (verified by signature only) is left untouched and keeps working.
 * <p>
 * Ownership is enforced HERE, not in SecurityConfig: a token that belongs to someone
 * else is reported as not found (404), on read and on write alike, so the API never
 * confirms that someone else's token id exists.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class ApiTokenService {

    // ── Scopes ────────────────────────────────────────────────────────────────

    /** Read-only access. */
    public static final String SCOPE_READ = "read";

    /** Read and write access. */
    public static final String SCOPE_WRITE = "write";

    /** Administrative access. Never granted to a token bound to an agent actor. */
    public static final String SCOPE_ADMIN = "admin";

    /**
     * The scopes the API accepts. Kept here rather than in a DB CHECK constraint so
     * growing the set is a code change, not a migration. An unknown scope is a 400.
     */
    public static final Set<String> ALLOWED_SCOPES = Set.of(SCOPE_READ, SCOPE_WRITE, SCOPE_ADMIN);

    /** Scopes granted when the request does not ask for any. Never includes admin. */
    public static final List<String> DEFAULT_SCOPES = List.of(SCOPE_READ, SCOPE_WRITE);

    // ── Token format ──────────────────────────────────────────────────────────

    /** Length of the random part that follows the {@code w4a_} prefix. */
    public static final int TOKEN_BODY_LENGTH = 32;

    /** base62 alphabet: digits, lowercase, uppercase. */
    private static final String BASE62 =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

    /** Number of leading body characters stored for display ({@code tokenPrefix}). */
    private static final int PREFIX_LENGTH = 8;

    /** How many times a colliding hash is regenerated before giving up. */
    private static final int MAX_GENERATION_ATTEMPTS = 5;

    // ── Cache / throttling ────────────────────────────────────────────────────

    /**
     * How long a validated token may be served from the in-memory cache before the
     * database is consulted again (WIKI4AI-136). Revocation evicts the entry
     * immediately, so this TTL is only the worst-case propagation delay of a revoke
     * performed by another process.
     */
    public static final long CACHE_TTL_MILLIS = 60_000L;

    /**
     * Window in which {@code last_used_at} is not rewritten (same idea as the
     * WIKI4AI-133 heartbeat throttle), so authenticating a request does not add a
     * database write on every call.
     */
    public static final long LAST_USED_THROTTLE_SECONDS = 30L;

    private final ApiTokenRepository apiTokenRepository;
    private final ActorRepository actorRepository;
    private final UserRepository userRepository;

    /**
     * Per-token sliding-window rate limiter (WIKI4AI-139). Consulted only for hashed
     * ({@code w4a_}) tokens and only after the token has already been proven valid,
     * so an invalid token still gets 401 and the legacy JWT path is untouched.
     */
    private final TokenRateLimiter tokenRateLimiter;

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * In-memory validation cache keyed by token hash. Deliberately a plain
     * {@link ConcurrentHashMap} — no new dependency, no eviction daemon: entries are
     * small, bounded by the number of live tokens, and replaced on their TTL.
     */
    private final Map<String, CachedToken> validationCache = new ConcurrentHashMap<>();

    /**
     * What a validated hashed token resolves to. Immutable on purpose: it is handed
     * to the auth filter (and through it to the SecurityContext) outside the
     * persistence session, so it must not carry a managed entity.
     *
     * @param tokenId  id of the api_tokens row
     * @param userId   id of the owning user
     * @param username username of the owning user — becomes the SecurityContext principal
     * @param actorId  actor the token acts as, or null
     * @param scopes   effective scopes (admin already removed for agent-bound tokens)
     */
    public record ApiTokenPrincipal(Long tokenId, Long userId, String username, Long actorId, List<String> scopes) {}

    /** A cached validation result plus the moment it was written. */
    private record CachedToken(ApiTokenPrincipal principal, long writtenAtMillis) {}

    // ── Current-user resolution ───────────────────────────────────────────────

    /**
     * Resolve the requesting user from the authenticated username. Returns null for
     * anonymous callers (test mode with security disabled).
     */
    private User resolveUser(String username) {
        if (username == null || username.isBlank()
                || "anonymous".equals(username) || "anonymousUser".equals(username)) {
            return null;
        }
        return userRepository.findByUsername(username).orElse(null);
    }

    /**
     * Resolve the requesting user or reject the request (403) when no authenticated
     * user can be resolved. Token creation needs a concrete owner.
     */
    private User requireUser(String username) {
        User user = resolveUser(username);
        if (user == null) {
            throw new AccessDeniedException("Authentication required");
        }
        return user;
    }

    // ── Create ────────────────────────────────────────────────────────────────

    /**
     * Create a hashed API token for the authenticated caller.
     * <p>
     * Validation (all 400 Bad Request): missing name, an expiry in the past, an
     * unknown scope, an actor that does not exist or that the caller may not use,
     * and asking for the {@code admin} scope on a token bound to an agent actor.
     *
     * @return the token metadata plus the plaintext — the only place the plaintext
     *         is ever returned
     */
    @Transactional
    public ApiTokenCreatedDTO createToken(TokenCreateRequestDTO dto, String username) {
        User user = requireUser(username);

        if (dto == null || dto.getName() == null || dto.getName().isBlank()) {
            throw new BadRequestException("Token name is required");
        }

        LocalDateTime expiresAt = dto.getExpiresAt();
        if (expiresAt != null && expiresAt.isBefore(LocalDateTime.now())) {
            throw new BadRequestException("Token expiration must not be in the past");
        }

        List<String> scopes = normalizeScopes(dto.getScopes());

        Long actorId = dto.getActorId();
        if (actorId != null) {
            Actor actor = requireUsableActor(actorId, user);
            // An agent may not be handed administrative power through its token.
            if (Actor.KIND_AGENT.equals(actor.getKind()) && scopes.contains(SCOPE_ADMIN)) {
                throw new BadRequestException(
                        "An agent token cannot be granted the '" + SCOPE_ADMIN + "' scope");
            }
        }

        String name = dto.getName().trim();

        GeneratedToken generated = generateAndSave(user, name, scopes, actorId, expiresAt);

        // The plaintext exists only here and in the response body below.
        return ApiTokenCreatedDTO.builder()
                .token(generated.plaintext())
                .metadata(convertToDTO(generated.token()))
                .build();
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    /**
     * All tokens of the authenticated caller, newest first. Legacy rows are included
     * so the UI can show (and deprecate) them; no plaintext and no hash is exposed.
     */
    public List<ApiTokenDTO> listTokens(String username) {
        User user = requireUser(username);
        return apiTokenRepository.findByUserIdOrderByCreatedAtDesc(user.getId()).stream()
                .map(this::convertToDTO)
                .toList();
    }

    /**
     * One token of the authenticated caller. Someone else's token is reported as
     * missing (404), the same pattern the rest of the project uses for private
     * resources.
     */
    public ApiTokenDTO getToken(Long id, String username) {
        User user = requireUser(username);
        return convertToDTO(requireOwnedToken(id, user));
    }

    // ── Revoke ────────────────────────────────────────────────────────────────

    /**
     * Soft-revoke a token: stamp {@code revokedAt} and evict its cache entry so the
     * next request carrying it is rejected immediately instead of after the TTL.
     * The row is kept for audit. Someone else's token is a 404.
     *
     * @return true when this call changed the row, false when it was already revoked
     */
    @Transactional
    public boolean revokeToken(Long id, String username) {
        User user = requireUser(username);
        ApiToken token = requireOwnedToken(id, user);

        boolean changed = token.revoke(LocalDateTime.now());
        // Evict unconditionally: even a no-op revoke must not leave a live entry
        // behind if some other path validated this token a moment ago.
        evict(token.getTokenHash());
        return changed;
    }

    // ── Rotation (WIKI4AI-137) ────────────────────────────────────────────────

    /**
     * Rotate a hashed API token in ONE transaction: the old row is soft-revoked and
     * its replacement is created and flushed before the transaction commits, so no
     * observer can ever see "old still works, new not stored yet" or the reverse —
     * both rows change atomically when the transaction commits, and a failure of
     * either leaves the other unwritten.
     * <p>
     * The replacement inherits the old token's {@code name}, {@code scopes},
     * {@code actorId} and {@code expiresAt}: rotation is a secret swap, not a
     * re-issue with new policy. The plaintext of the new token is returned once, in
     * the same shape {@code createToken} returns.
     * <p>
     * Rejections: someone else's token → 404 (read and write alike); a legacy
     * clear-text JWT row → 400, because its plaintext is not reissuable (the row
     * stores the JWT itself, and the filter accepts it by signature, so there is no
     * secret to hand out a second time); an already revoked token → 409.
     *
     * @return the new token's plaintext plus its metadata
     */
    @Transactional
    public ApiTokenCreatedDTO rotateToken(Long id, String username) {
        User user = requireUser(username);
        ApiToken old = requireOwnedToken(id, user);

        if (!old.isHashed()) {
            throw new BadRequestException(
                    "Legacy API tokens cannot be rotated: their clear-text value is not reissuable. "
                            + "Create a new hashed token instead.");
        }

        if (old.isRevoked()) {
            throw new IllegalArgumentException(
                    "API token " + id + " is already revoked and cannot be rotated");
        }

        // Evict before the write: while this transaction is still open, a concurrent
        // request must not be able to re-populate the cache from the pre-revoke row.
        evict(old.getTokenHash());

        old.revoke(LocalDateTime.now());
        apiTokenRepository.saveAndFlush(old);

        GeneratedToken fresh = generateAndSave(user, old.getName(), storedScopes(old),
                old.getActorId(), old.getExpiresAt());

        // Evict again after the flush. The old row is revoked in the database now, so
        // an entry another thread wrote in the meantime is stale and must not live on
        // to the TTL: the next request carrying the old secret goes back to the row
        // and is refused with 401.
        evict(old.getTokenHash());

        return ApiTokenCreatedDTO.builder()
                .token(fresh.plaintext())
                .metadata(convertToDTO(fresh.token()))
                .build();
    }

    // ── Actor kill switch (WIKI4AI-137) ───────────────────────────────────────

    /**
     * Revoke every token bound to an actor at once, and evict the validation cache of
     * each of them — that last part is the point: a token that is sitting in the
     * cache right now must stop authenticating on its next request, not after the
     * TTL.
     * <p>
     * Ownership follows the rule {@code ApiTokenService} applies to {@code actorId}
     * when a token is created: the actor's owner, or an ADMIN for any actor. Anyone
     * else gets 404 — the API does not confirm that the actor exists.
     * <p>
     * Idempotent: a second call revokes nothing new and still succeeds. Already
     * revoked rows keep their original {@code revokedAt}.
     *
     * @return how many rows this call actually changed (0 on a repeat call)
     */
    @Transactional
    public int revokeActorTokens(Long actorId, String username) {
        User user = requireUser(username);
        requireOwnedActor(actorId, user);

        List<ApiToken> tokens = apiTokenRepository.findByActorId(actorId);
        LocalDateTime now = LocalDateTime.now();

        int changed = 0;
        for (ApiToken token : tokens) {
            if (token.revoke(now)) {
                changed++;
            }
            // Unconditional, per token — same rule as revokeToken(): a no-op revoke
            // must still drop a live cache entry.
            evict(token.getTokenHash());
        }

        if (!tokens.isEmpty()) {
            apiTokenRepository.flush();
        }
        return changed;
    }

    /**
     * Drop every cached validation result. Exposed so a caller that changes tokens
     * outside this service (or a test) can force the next request back to the
     * database.
     */
    public void clearValidationCache() {
        validationCache.clear();
    }

    /**
     * Number of live cache entries — for tests and for observability.
     */
    public int validationCacheSize() {
        return validationCache.size();
    }

    // ── Validation (used by JwtAuthenticationFilter) ──────────────────────────

    /**
     * Resolve a {@code w4a_} token to its principal, or empty when it must not
     * authenticate (unknown hash, revoked, expired).
     * <p>
     * Read-write transaction on purpose: a successful validation may refresh
     * {@code last_used_at}. The refresh is throttled twice — the in-memory entry
     * skips the call entirely inside the window, and the UPDATE's WHERE clause
     * refuses the write again so two instances cannot fight over the row.
     */
    @Transactional
    public Optional<ApiTokenPrincipal> authenticate(String rawToken) {
        if (rawToken == null || !rawToken.startsWith(ApiToken.HASHED_TOKEN_PREFIX)) {
            return Optional.empty();
        }

        String hash = sha256Hex(rawToken);
        long nowMillis = System.currentTimeMillis();

        CachedToken cached = validationCache.get(hash);
        if (cached != null && nowMillis - cached.writtenAtMillis() < CACHE_TTL_MILLIS) {
            // Inside the throttle window: no database work at all for this request.
            // WIKI4AI-139: the rate limit is enforced on cached validations too. A
            // cache hit is still a request, and skipping it here would make the limit
            // unenforceable for exactly the tokens that are used the most.
            enforceRateLimit(hash);
            if (nowMillis - cached.writtenAtMillis() >= LAST_USED_THROTTLE_SECONDS * 1000L) {
                touchLastUsed(cached.principal().tokenId(), LocalDateTime.now());
                validationCache.put(hash, new CachedToken(cached.principal(), nowMillis));
            }
            return Optional.of(cached.principal());
        }

        Optional<ApiToken> found = apiTokenRepository.findByTokenHash(hash);
        if (found.isEmpty()) {
            // Unknown token: no negative caching, so a token created a moment later
            // is accepted at once.
            validationCache.remove(hash);
            return Optional.empty();
        }

        ApiToken token = found.get();
        if (token.isRevoked() || token.isExpired()) {
            validationCache.remove(hash);
            return Optional.empty();
        }

        User owner = token.getUser();
        if (owner == null || owner.getId() == null || owner.getUsername() == null) {
            validationCache.remove(hash);
            return Optional.empty();
        }

        // WIKI4AI-139: the limit is applied only to a token that has already passed
        // every validity check. An unknown, revoked or expired token is still a 401,
        // never a 429 — throttling must not become a way to probe token existence.
        enforceRateLimit(hash);

        ApiTokenPrincipal principal = new ApiTokenPrincipal(
                token.getId(),
                owner.getId(),
                owner.getUsername(),
                token.getActorId(),
                effectiveScopes(token));

        touchLastUsed(token.getId(), LocalDateTime.now());
        validationCache.put(hash, new CachedToken(principal, nowMillis));
        return Optional.of(principal);
    }

    /**
     * SHA-256 of a plaintext token as 64 lowercase hex characters — the form stored
     * in {@code token_hash} and used for the lookup.
     */
    public static String sha256Hex(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every JVM; this cannot happen in practice.
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    /**
     * Generate {@code w4a_} + 32 base62 characters from a {@link SecureRandom}.
     * base64 is not used for the body: '+' and '/' would make the token ambiguous
     * when copied, and base62 keeps it a single shell- and URL-safe word.
     */
    private String generateTokenValue() {
        StringBuilder body = new StringBuilder(TOKEN_BODY_LENGTH);
        for (int i = 0; i < TOKEN_BODY_LENGTH; i++) {
            body.append(BASE62.charAt(secureRandom.nextInt(BASE62.length())));
        }
        return ApiToken.HASHED_TOKEN_PREFIX + body;
    }

    /**
     * A freshly generated hashed token: the plaintext (returned to the caller once)
     * and the row that was stored for it. Used by both create and rotate so the two
     * paths cannot drift apart.
     */
    private record GeneratedToken(String plaintext, ApiToken token) {}

    /**
     * Generate a unique secret and store its row. Shared by {@code createToken} and
     * {@code rotateToken}.
     * <p>
     * A hash collision between two freshly generated tokens is on the order of
     * 2^-128, but checking before saving keeps the transaction clean: a constraint
     * violation would mark it rollback-only and the retry below could not run. The
     * pre-check is the cheap way to make the retry real.
     */
    private GeneratedToken generateAndSave(User user, String name, List<String> scopes,
                                           Long actorId, LocalDateTime expiresAt) {
        for (int attempt = 0; attempt < MAX_GENERATION_ATTEMPTS; attempt++) {
            String plaintext = generateTokenValue();
            String hash = sha256Hex(plaintext);

            if (apiTokenRepository.findByTokenHash(hash).isPresent()) {
                log.warn("Generated API token hash collided, retrying (attempt {})", attempt + 1);
                continue;
            }

            ApiToken token = ApiToken.builder()
                    .user(user)
                    .name(name)
                    .tokenValue(null)          // hashed tokens never store the plaintext
                    .tokenHash(hash)
                    .tokenPrefix(plaintext.substring(ApiToken.HASHED_TOKEN_PREFIX.length(),
                            ApiToken.HASHED_TOKEN_PREFIX.length() + PREFIX_LENGTH))
                    .scopes(String.join(" ", scopes))
                    .actorId(actorId)
                    .expiresAt(expiresAt)
                    .build();

            return new GeneratedToken(plaintext, apiTokenRepository.saveAndFlush(token));
        }

        throw new IllegalStateException("Could not generate a unique API token");
    }

    /**
     * Validate and normalize the requested scopes. Null or empty means the defaults.
     * Unknown scope → 400. Duplicates are collapsed, order is preserved.
     */
    private List<String> normalizeScopes(List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return new ArrayList<>(DEFAULT_SCOPES);
        }

        Set<String> normalized = new LinkedHashSet<>();
        for (String raw : requested) {
            if (raw == null || raw.isBlank()) {
                throw new BadRequestException("Scope must not be blank");
            }
            String scope = raw.trim().toLowerCase(Locale.ROOT);
            if (!ALLOWED_SCOPES.contains(scope)) {
                throw new BadRequestException("Unknown scope: " + raw);
            }
            normalized.add(scope);
        }
        return new ArrayList<>(normalized);
    }

    /**
     * Scopes stored on a token, as a list. A token with no stored scope list gets
     * the defaults, so a row written before the rework still means something.
     */
    private List<String> storedScopes(ApiToken token) {
        if (token.getScopes() == null || token.getScopes().isBlank()) {
            return new ArrayList<>(DEFAULT_SCOPES);
        }
        List<String> scopes = new ArrayList<>();
        for (String part : token.getScopes().split("\\s+")) {
            String scope = part.trim().toLowerCase(Locale.ROOT);
            if (!scope.isEmpty() && ALLOWED_SCOPES.contains(scope)) {
                scopes.add(scope);
            }
        }
        return scopes.isEmpty() ? new ArrayList<>(DEFAULT_SCOPES) : scopes;
    }

    /**
     * Scopes a token actually gets on a request. The creation-time check is not the
     * only line of defence: if a row somehow pairs an agent actor with the admin
     * scope, the admin scope is dropped here as well.
     */
    private List<String> effectiveScopes(ApiToken token) {
        List<String> scopes = storedScopes(token);
        if (token.getActorId() != null && scopes.contains(SCOPE_ADMIN)) {
            Actor actor = actorRepository.findById(token.getActorId()).orElse(null);
            if (actor != null && Actor.KIND_AGENT.equals(actor.getKind())) {
                List<String> withoutAdmin = new ArrayList<>(scopes);
                withoutAdmin.remove(SCOPE_ADMIN);
                return withoutAdmin;
            }
        }
        return scopes;
    }

    /**
     * Throttled {@code last_used_at} write. The database decides whether the row is
     * old enough to be refreshed, so this is at most one write per throttle window.
     */
    private void touchLastUsed(Long tokenId, LocalDateTime now) {
        if (tokenId == null) {
            return;
        }
        apiTokenRepository.touchLastUsedThrottled(tokenId, now, now.minusSeconds(LAST_USED_THROTTLE_SECONDS));
    }

    /**
     * Load a token and reject it (404) when it belongs to another user.
     */
    private ApiToken requireOwnedToken(Long id, User user) {
        if (id == null) {
            throw new EntityNotFoundException("API token not found");
        }
        ApiToken token = apiTokenRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("API token not found"));
        if (token.getUser() == null || !user.getId().equals(token.getUser().getId())) {
            // Same answer as "does not exist": the API never confirms that another
            // user owns this id.
            throw new EntityNotFoundException("API token not found");
        }
        return token;
    }

    /**
     * Load an actor the caller wants to bind a token to. 400 when it does not exist
     * or when the caller may not act as it: only the actor's owner, or an ADMIN for
     * any actor including system-owned ones.
     */
    private Actor requireUsableActor(Long actorId, User user) {
        Actor actor = actorRepository.findById(actorId)
                .orElseThrow(() -> new BadRequestException("Actor not found: " + actorId));

        boolean isAdmin = user.getRole() == Role.ADMIN;
        boolean isOwner = actor.getOwnerUserId() != null && actor.getOwnerUserId().equals(user.getId());
        if (!isAdmin && !isOwner) {
            throw new BadRequestException("Actor " + actorId + " does not belong to you");
        }
        return actor;
    }

    /**
     * Load an actor the caller wants to act on as a whole (the kill switch). Same
     * ownership rule as {@link #requireUsableActor} — owner or ADMIN — but a
     * different answer: 404, not 400. A caller who may not touch an actor is told the
     * actor does not exist, so the endpoint never confirms that someone else owns it.
     */
    private Actor requireOwnedActor(Long actorId, User user) {
        if (actorId == null) {
            throw new EntityNotFoundException("Actor not found");
        }
        Actor actor = actorRepository.findById(actorId)
                .orElseThrow(() -> new EntityNotFoundException("Actor not found: " + actorId));

        boolean isAdmin = user.getRole() == Role.ADMIN;
        boolean isOwner = actor.getOwnerUserId() != null && actor.getOwnerUserId().equals(user.getId());
        if (!isAdmin && !isOwner) {
            // Same answer as "does not exist".
            throw new EntityNotFoundException("Actor not found: " + actorId);
        }
        return actor;
    }

    /**
     * Per-token rate limit gate (WIKI4AI-139).
     * <p>
     * The key is the token's SHA-256 hash — the same identity the token row is looked
     * up by — so two tokens of the same user have two independent budgets and one
     * noisy token cannot freeze its owner's other credentials. The limiter itself is
     * in-memory and dependency-free; this method only decides when it is consulted:
     * for hashed tokens that are already known to be valid.
     *
     * @throws RateLimitExceededException when this token's window is full
     */
    private void enforceRateLimit(String tokenHash) {
        if (tokenRateLimiter == null || tokenHash == null) {
            return;
        }
        if (!tokenRateLimiter.tryAcquire(tokenHash)) {
            throw new RateLimitExceededException(
                    "API token request limit exceeded. Try again in "
                            + tokenRateLimiter.retryAfterSeconds() + " second(s).",
                    tokenRateLimiter.retryAfterSeconds());
        }
    }

    /**
     * Manual entity → DTO conversion (this project does not use MapStruct). Never
     * copies {@code tokenValue} or {@code tokenHash}.
     */
    private ApiTokenDTO convertToDTO(ApiToken token) {
        return ApiTokenDTO.builder()
                .id(token.getId())
                .name(token.getName())
                .prefix(token.getTokenPrefix())
                .scopes(token.isHashed() ? storedScopes(token) : null)
                .actorId(token.getActorId())
                .expiresAt(token.getExpiresAt())
                .revokedAt(token.getRevokedAt())
                .lastUsedAt(token.getLastUsedAt())
                .createdAt(token.getCreatedAt())
                .hashed(token.isHashed())
                .build();
    }

    /**
     * Evict one cached validation result. Null-safe.
     */
    private void evict(String tokenHash) {
        if (tokenHash != null) {
            validationCache.remove(tokenHash);
        }
    }

    /**
     * Whether a string is a well-formed hashed token (prefix + 32 base62 characters).
     */
    public static boolean isHashedTokenFormat(String value) {
        if (value == null || value.length() != ApiToken.HASHED_TOKEN_PREFIX.length() + TOKEN_BODY_LENGTH) {
            return false;
        }
        if (!value.startsWith(ApiToken.HASHED_TOKEN_PREFIX)) {
            return false;
        }
        for (int i = ApiToken.HASHED_TOKEN_PREFIX.length(); i < value.length(); i++) {
            if (BASE62.indexOf(value.charAt(i)) < 0) {
                return false;
            }
        }
        return true;
    }
}
