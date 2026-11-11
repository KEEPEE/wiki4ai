package com.wiki4ai.service;

import com.wiki4ai.config.JwtUtil;
import com.wiki4ai.config.RegistrationProperties;
import com.wiki4ai.dto.AuthResponseDTO;
import com.wiki4ai.dto.LoginRequestDTO;
import com.wiki4ai.dto.ProfileUpdateRequestDTO;
import com.wiki4ai.dto.RegisterRequestDTO;
import com.wiki4ai.dto.SetupRequestDTO;
import com.wiki4ai.dto.TokenGenerationRequestDTO;
import com.wiki4ai.dto.TokenResponseDTO;
import com.wiki4ai.dto.UserDTO;
import com.wiki4ai.exception.RegistrationDisabledException;
import com.wiki4ai.exception.SetupAlreadyCompletedException;
import com.wiki4ai.model.ApiToken;
import com.wiki4ai.model.RefreshToken;
import com.wiki4ai.model.Role;
import com.wiki4ai.model.User;
import com.wiki4ai.repository.ApiTokenRepository;
import com.wiki4ai.repository.RefreshTokenRepository;
import com.wiki4ai.repository.UserRepository;
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.wiki4ai.dto.TokenGenerationRequestDTO;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for authentication operations: user registration and login.
 * Only loaded when security.enabled=true.
 */
@Slf4j
@Service
@EnableConfigurationProperties(RegistrationProperties.class)
@ConditionalOnProperty(name = "security.enabled", havingValue = "true", matchIfMissing = true)
public class AuthService {

    /**
     * WIKI4AI-68: striped locks that serialize refresh-token replacement (DELETE + INSERT)
     * per user. Two concurrent logins for the same user would otherwise both observe
     * "no existing token", both INSERT, and one violates the unique constraint on
     * refresh_tokens.user_id (DataIntegrityViolationException → intermittent 500 on login).
     * Stripes avoid unbounded lock-object growth; single-JVM deployment is a documented invariant.
     */
    /**
     * WIKI4AI-73: UI locales the WebUI ships. The profile page only offers these,
     * and {@link #updateUserProfile} rejects anything else so the database never
     * stores a language the frontend cannot render.
     */
    public static final java.util.Set<String> SUPPORTED_UI_LANGUAGES = java.util.Set.of("en", "sk");

    private static final int REFRESH_TOKEN_LOCK_STRIPES = 64;
    private final Object[] refreshTokenLocks = new Object[REFRESH_TOKEN_LOCK_STRIPES];

    {
        for (int i = 0; i < REFRESH_TOKEN_LOCK_STRIPES; i++) {
            refreshTokenLocks[i] = new Object();
        }
    }

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final ApiTokenRepository apiTokenRepository;
    private final EntityManager entityManager;
    private final TransactionTemplate transactionTemplate;
    private final BCryptPasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final RegistrationProperties registrationProperties;

    /**
     * WIKI4AI-69: serializes the first-run setup check-and-create. The deployment
     * invariant is a single backend JVM (one container), so an in-JVM lock is
     * sufficient to make "users table empty → create first ADMIN" atomic against
     * concurrent setup requests. The unique username/email constraints remain the
     * final backstop (see {@link #createInitialAdmin}).
     */
    private final Object initialAdminLock = new Object();

    public AuthService(UserRepository userRepository, RefreshTokenRepository refreshTokenRepository, 
                       ApiTokenRepository apiTokenRepository, EntityManager entityManager, 
                       TransactionTemplate transactionTemplate, JwtUtil jwtUtil,
                       RegistrationProperties registrationProperties) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.apiTokenRepository = apiTokenRepository;
        this.entityManager = entityManager;
        this.transactionTemplate = transactionTemplate;
        this.jwtUtil = jwtUtil;
        this.registrationProperties = registrationProperties;
        this.passwordEncoder = new BCryptPasswordEncoder();
    }

    /**
     * Register a new user with the given credentials.
     * <p>
     * WIKI4AI-70: public self-registration is policy-controlled — see
     * {@link #isRegistrationOpen()}. When registration is closed, this throws
     * {@link RegistrationDisabledException} (mapped to HTTP 403).
     *
     * @param request the registration request containing username, email, and password
     * @return the created UserDTO (without password)
     */
    @Transactional
    public UserDTO registerUser(RegisterRequestDTO request) {
        if (!isRegistrationOpen()) {
            throw new RegistrationDisabledException("Registration is disabled on this instance");
        }

        // WIKI4AI-197 (REV-2 P0 #4): the name must not be an internal marker. Checked
        // BEFORE the uniqueness query so the reason a name is unusable is the real one —
        // 'anonymous' is usually free, and "already taken" would be a misleading answer.
        ReservedUsernames.rejectIfReserved(request.getUsername());

        // Validate uniqueness of username
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new IllegalArgumentException("Username is already taken");
        }

        // Validate uniqueness of email
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new IllegalArgumentException("Email is already registered");
        }

        // Hash the password and create user entity
        String hashedPassword = passwordEncoder.encode(request.getPassword());

        User user = User.builder()
                .username(request.getUsername())
                .email(request.getEmail())
                .password(hashedPassword)
                .build();

        userRepository.save(user);

        return convertToUserDTO(user);
    }

    /**
     * WIKI4AI-69: create the first account of an uninitialized instance through
     * the first-run setup flow ({@code POST /api/v1/auth/setup}).
     * <p>
     * Only accepted while the users table is empty; afterwards it throws
     * {@link SetupAlreadyCompletedException} (mapped to HTTP 403). The created
     * account always receives the ADMIN role — this is the only non-admin path
     * that can initialize a fresh instance. When no email is supplied,
     * {@code {username}@localhost} is derived (same convention as the
     * InitialAdminBootstrap seed), keeping setup-created and bootstrap-created
     * accounts consistent.
     * <p>
     * The check-and-create is serialized on an in-JVM lock (single-JVM
     * deployment invariant) so two concurrent first-run requests cannot create
     * two admins; the unique username/email constraints are the final backstop.
     *
     * @param request the setup request containing username, password and optional email
     * @return the created ADMIN UserDTO (without password)
     */
    @Transactional
    public UserDTO createInitialAdmin(SetupRequestDTO request) {
        synchronized (initialAdminLock) {
            if (userRepository.count() > 0) {
                log.warn("Setup rejected: instance is already initialized");
                throw new SetupAlreadyCompletedException("Setup already completed");
            }

            String email = (request.getEmail() != null && !request.getEmail().isBlank())
                    ? request.getEmail().trim()
                    : request.getUsername() + "@localhost";

            // WIKI4AI-197: the first-run setup flow is a username write like any other.
            // An instance whose very first account were 'anonymous' would start out with
            // an ADMIN that the permission layer reads as an unauthenticated visitor.
            ReservedUsernames.rejectIfReserved(request.getUsername());

            if (userRepository.existsByEmail(email)) {
                throw new IllegalArgumentException("Email is already registered");
            }

            User user = User.builder()
                    .username(request.getUsername())
                    .email(email)
                    .password(passwordEncoder.encode(request.getPassword()))
                    .role(Role.ADMIN)
                    .build();

            try {
                userRepository.save(user);
            } catch (DataIntegrityViolationException e) {
                // Lost a race on the unique username/email constraint — treat as
                // already initialized rather than leaking which value collided.
                throw new SetupAlreadyCompletedException("Setup already completed");
            }

            log.info("First-run setup completed: initial ADMIN account '{}' created", user.getUsername());
            return convertToUserDTO(user);
        }
    }

    /**
     * WIKI4AI-69: whether the instance has been initialized, i.e. the users table
     * is non-empty. Exposed by {@code GET /api/v1/auth/status} as a plain boolean —
     * no user details are ever returned.
     */
    public boolean isInstanceInitialized() {
        return userRepository.count() > 0;
    }

    /**
     * WIKI4AI-70: whether public self-registration ({@code POST /api/v1/auth/register})
     * is currently accepted. Policy (property {@code auth.registration.open}):
     * <ul>
     *   <li>unset (default) — open only while the users table is empty (first-run),
     *       closed automatically once the first account exists;</li>
     *   <li>{@code true} — always open (explicit opt-in for self-hosters);</li>
     *   <li>{@code false} — never open.</li>
     * </ul>
     */
    public boolean isRegistrationOpen() {
        Boolean open = registrationProperties == null ? null : registrationProperties.open();
        if (open != null) {
            return open;
        }
        return userRepository.count() == 0;
    }

    /**
     * Authenticate a user with username and password.
     * Persists the refresh token so it survives across deploys.
     *
     * @param request the login request containing username and password
     * @return AuthResponseDTO with tokens and user info, or null if credentials are invalid
     */
    public AuthResponseDTO loginUser(LoginRequestDTO request) {
        User user = userRepository.findByUsername(request.getUsername())
                .orElse(null);

        if (user == null) {
            // WIKI4AI-68: server-side diagnostics for login failures. The client still
            // receives the generic "Invalid username or password" 401 — never the reason.
            log.warn("Login failed: no user found for username='{}'", request.getUsername());
            return null;
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            log.warn("Login failed: password mismatch for username='{}'", request.getUsername());
            return null; // Invalid credentials
        }

        String accessToken = generateAccessToken(user.getId(), user.getUsername());
        String refreshToken = generateAndPersistRefreshToken(user);

        return AuthResponseDTO.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .user(convertToUserDTO(user))
                .build();
    }

    /**
     * Generate a real JWT access token for the user.
     */
    public String generateAccessToken(Long userId, String username) {
        return jwtUtil.generateToken(username);
    }

    /**
     * Generate a real JWT refresh token and persist it in the database.
     * The persisted token survives across deploys so users don't lose their tokens.
     * Deletes any existing refresh token for the user first to avoid unique constraint violations.
     *
     * @param user the user entity
     * @return the generated refresh token string
     */
    public String generateAndPersistRefreshToken(User user) {
        return generateAndPersistRefreshToken(user, null);
    }

    /**
     * Generate a real JWT refresh token and persist it in the database with custom expiration.
     * The persisted token survives across deploys so users don't lose their tokens.
     * Deletes any existing refresh token for the user first to avoid unique constraint violations.
     *
     * @param user        the user entity
     * @param expiresAt   optional expiration date; null means infinite lifetime (never expires)
     * @return the generated refresh token string
     */
    public String generateAndPersistRefreshToken(User user, LocalDateTime expiresAt) {
        // WIKI4AI-68: serialize the DELETE + INSERT per user so concurrent logins (or
        // /token calls) for the same user cannot interleave between the delete and the
        // insert. Without this, both transactions see "no existing token", both insert,
        // and one fails with a duplicate key on refresh_tokens.user_id → intermittent
        // 500 on POST /api/v1/auth/login under concurrent load.
        synchronized (refreshTokenLock(user.getId())) {
            return doGenerateAndPersistRefreshToken(user, expiresAt);
        }
    }

    private Object refreshTokenLock(Long userId) {
        return refreshTokenLocks[Math.floorMod(userId.hashCode(), REFRESH_TOKEN_LOCK_STRIPES)];
    }

    private String doGenerateAndPersistRefreshToken(User user, LocalDateTime expiresAt) {
        // Use TransactionTemplate for explicit transaction management to ensure
        // UPDATE/DELETE operations have an active transaction context
        return transactionTemplate.execute(status -> {
            // Delete any existing refresh token for this user using native query
            entityManager.createNativeQuery(
                    "DELETE FROM refresh_tokens WHERE user_id = :userId")
                    .setParameter("userId", user.getId())
                    .executeUpdate();

            String refreshToken = jwtUtil.generateRefreshToken(user.getUsername());

            LocalDateTime expiresAtValue;
            if (expiresAt != null) {
                // Use the provided expiration date
                expiresAtValue = expiresAt;
            } else {
                // Null means infinite lifetime — token never expires
                expiresAtValue = null;
            }

            RefreshToken tokenEntity = RefreshToken.builder()
                    .user(user)
                    .token(refreshToken)
                    .expiresAt(expiresAtValue)
                    .build();

            refreshTokenRepository.save(tokenEntity);

            return refreshToken;
        });
    }

    /**
     * Generate a real JWT refresh token for the user (without persistence).
     * Kept for backward compatibility with tests.
     */
    public String generateRefreshToken(Long userId) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            throw new IllegalArgumentException("User not found");
        }
        return jwtUtil.generateRefreshToken(user.getUsername());
    }

    /**
     * Exchange a refresh token for a new access token (WIKI4AI-197, plan commit 2b).
     * <p>
     * <b>Why this endpoint had to be written at all.</b> The frontend has always called
     * {@code POST /auth/refresh} ({@code apiClient.ts:43}, {@code AuthContext.tsx:240})
     * and {@code AuthController} never had the mapping, so the call has been hitting a
     * 404 since the day it was written. Nothing noticed because a refresh token also
     * used to pass the access-token check — it is a signed JWT with a subject — so the
     * session quietly survived on a 7-day token pretending to be a 1-day token. Once
     * the filter started requiring {@code type == ACCESS}, that stopped working, and
     * without this endpoint the session would simply die at 24 hours with the client
     * calling into nothing. This commit is what makes the previous one an improvement
     * rather than a regression.
     * <p>
     * <b>Two proofs, not one.</b> A refresh token is not only a JWT. It is also a row in
     * {@code refresh_tokens}, and both halves are checked:
     * <ol>
     *   <li>the token parses as a REFRESH token — signature, exact type, an {@code exp}
     *       that exists and lies ahead, a non-blank subject;</li>
     *   <li>a row with that exact token value exists, is not expired by its own
     *       {@code expires_at}, and belongs to the very account the token names.</li>
     * </ol>
     * Proof 1 alone would let a refresh token that was rotated away, or one whose DB
     * row was deleted, keep minting access tokens until its JWT expiry. Proof 2 alone
     * would skip signature verification. The account match is not redundant either: it
     * is what stops a row belonging to one user being presented under a subject that
     * names another.
     * <p>
     * <b>The refresh token is not rotated here.</b> The schema allows one live row per
     * user ({@code refresh_tokens.user_id} is unique), the caller already holds this
     * value, and rotating it would mean a write on every silent token renewal plus a
     * race between two tabs that both hold the old one. Rotation is a deliberate act
     * that belongs to login and to {@code POST /auth/token}, which already replace the
     * row. The response echoes the same refresh token so the client's stored value
     * stays correct either way.
     * <p>
     * Returns {@code null} for every rejection — missing, malformed, wrong type,
     * expired, unknown row, account mismatch — so the controller answers 401 with one
     * indistinguishable message. Telling the caller WHICH check failed would let
     * someone probe which refresh tokens exist. The reason is logged server-side.
     *
     * @param refreshToken the value the client stored at login
     * @return access token + the same refresh token + user info, or null to mean 401
     */
    @Transactional(readOnly = true)
    public AuthResponseDTO refreshAccessToken(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return null;
        }

        var claims = jwtUtil.parseRefreshToken(refreshToken);
        if (claims.isEmpty()) {
            // Includes the common mistake of sending the ACCESS token here: the type
            // check refuses it, which is the whole point of having two types.
            log.debug("Refresh rejected: token is not a well-formed REFRESH token");
            return null;
        }
        String subject = claims.get().getSubject();

        RefreshToken row = refreshTokenRepository.findByToken(refreshToken).orElse(null);
        if (row == null) {
            // Signed correctly, right type, but never persisted or since rotated away.
            log.debug("Refresh rejected for account '{}': no matching refresh_tokens row", subject);
            return null;
        }
        if (row.isExpired()) {
            log.debug("Refresh rejected for account '{}': stored row expired at {}", subject, row.getExpiresAt());
            return null;
        }

        User user = userRepository.findByUsername(subject).orElse(null);
        if (user == null) {
            // The account the token names no longer exists. The row may outlive it
            // briefly, but an access token for nobody is not worth minting.
            log.debug("Refresh rejected: account '{}' in the token no longer exists", subject);
            return null;
        }
        if (!user.getId().equals(row.getUser().getId())) {
            // A row for one account presented under a subject naming another.
            log.warn("Refresh rejected: refresh_tokens row {} belongs to user {}, token names '{}'",
                    row.getId(), row.getUser().getId(), subject);
            return null;
        }

        String accessToken = generateAccessToken(user.getId(), user.getUsername());

        return AuthResponseDTO.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .user(convertToUserDTO(user))
                .build();
    }

    /**
     * Convert User entity to UserDTO (excludes password).
     */
    private UserDTO convertToUserDTO(User user) {
        return UserDTO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .role(user.getRole())
                // WIKI4AI-73: always expose a non-null language (defensive default for
                // rows that could not have been backfilled — V12 makes the column NOT NULL).
                .language(normalizeUiLanguage(user.getLanguage()))
                .createdAt(user.getCreatedAt())
                .build();
    }

    /**
     * WIKI4AI-73: normalize a stored UI language to a supported value, falling back
     * to the default "en" when absent or unrecognized.
     */
    private String normalizeUiLanguage(String language) {
        if (language == null || language.isBlank()) {
            return "en";
        }
        String normalized = language.trim().toLowerCase(java.util.Locale.ROOT);
        return SUPPORTED_UI_LANGUAGES.contains(normalized) ? normalized : "en";
    }

    /**
     * Get the profile (UserDTO) for a user by username.
     * Used by the GET /me endpoint to return the authenticated user's info.
     *
     * @param username the username of the authenticated user
     * @return UserDTO with id, username, email, role, createdAt — or null if not found
     */
    public UserDTO getProfileByUsername(String username) {
        return userRepository.findByUsername(username)
                .map(this::convertToUserDTO)
                .orElse(null);
    }

    /**
     * Generate a new access token and refresh token for an already authenticated user.
     * Persists the refresh token so it survives across deploys.
     * Used by the POST /token endpoint so users can generate fresh tokens (e.g., for API integrations).
     * Same logic as loginUser but without password verification — the user is already authenticated via JWT.
     * Uses default expiration from JwtUtil configuration when no custom expiresAt is provided.
     *
     * @param username the username of the authenticated user (from SecurityContext)
     * @return AuthResponseDTO with new accessToken, refreshToken and user info — or null if user not found
     */
    public AuthResponseDTO generateNewTokenForUser(String username) {
        User user = userRepository.findByUsername(username).orElse(null);

        if (user == null) {
            return null; // User not found
        }

        String accessToken = generateAccessToken(user.getId(), user.getUsername());
        String refreshToken = generateAndPersistRefreshToken(user, null);

        return AuthResponseDTO.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .user(convertToUserDTO(user))
                .build();
    }

    /**
     * Generate a new access token and refresh token for an already authenticated user.
     * Persists the refresh token so it survives across deploys.
     * Used by the POST /token endpoint so users can generate fresh tokens (e.g., for API integrations).
     * Same logic as loginUser but without password verification — the user is already authenticated via JWT.
     * Accepts a TokenGenerationRequestDTO with optional expiration date.
     * If expiresAt is null or not provided, the refresh token will never expire (infinite lifetime).
     *
     * @param username    the username of the authenticated user (from SecurityContext)
     * @param request     token generation request with optional expiresAt field; null means infinite lifetime
     * @return AuthResponseDTO with new accessToken, refreshToken and user info — or null if user not found
     */
    public AuthResponseDTO generateNewTokenForUser(String username, TokenGenerationRequestDTO request) {
        LocalDateTime expiresAt = (request != null && request.getExpiresAt() != null) 
                ? request.getExpiresAt() : null;
        
        User user = userRepository.findByUsername(username).orElse(null);

        if (user == null) {
            return null; // User not found
        }

        String accessToken = generateAccessToken(user.getId(), user.getUsername());
        String refreshToken = generateAndPersistRefreshToken(user, expiresAt);

        return AuthResponseDTO.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .user(convertToUserDTO(user))
                .build();
    }

    /**
     * Generate a named API token (JWT access token) for the authenticated user.
     * The token is stored in the database with a custom name and optional expiration date.
     * If expiresAt is null, the JWT token never expires (infinite lifetime).
     *
     * @param username    the username of the authenticated user (from SecurityContext)
     * @param tokenName   a custom name for this token (for user identification)
     * @param expiresAt   optional expiration date; null means infinite lifetime
     * @return TokenResponseDTO with the generated access token and metadata
     */
    public TokenResponseDTO generateNamedApiToken(String username, String tokenName, LocalDateTime expiresAt) {
        User user = userRepository.findByUsername(username).orElse(null);

        if (user == null) {
            throw new IllegalArgumentException("User not found");
        }

        // Generate a unique JWT access token with the correct expiration date.
        // When expiresAt is null, use infinite-expiry token (no 'exp' claim).
        String accessToken;
        if (expiresAt != null) {
            Date jwtExpiryDate = Date.from(expiresAt.atZone(java.time.ZoneOffset.UTC).toInstant());
            accessToken = jwtUtil.generateToken(user.getUsername(), "ACCESS", new java.util.HashMap<>(), jwtExpiryDate);
        } else {
            // No expiration specified — generate a token that never expires
            accessToken = jwtUtil.generateInfiniteToken(user.getUsername());
        }

        // Use TransactionTemplate to ensure proper transaction participation
        return transactionTemplate.execute(status -> {
            ApiToken apiToken = ApiToken.builder()
                    .user(user)
                    .name(tokenName)
                    .tokenValue(accessToken)
                    .expiresAt(expiresAt)  // null means infinite lifetime
                    .build();

            apiTokenRepository.save(apiToken);

            return TokenResponseDTO.builder()
                    .accessToken(accessToken)
                    .name(tokenName)
                    .expiresAt(expiresAt)
                    .createdAt(apiToken.getCreatedAt())
                    .tokenId(apiToken.getId())
                    .build();
        });
    }

    /**
     * List all API tokens for the authenticated user.
     *
     * @param userId the user ID to list tokens for
     * @return list of TokenResponseDTO (without the actual token value)
     */
    public List<TokenResponseDTO> listApiTokens(Long userId) {
        return apiTokenRepository.findByUserId(userId).stream()
                .map(token -> TokenResponseDTO.builder()
                        .accessToken(null)  // Don't expose the actual token value in listing
                        .name(token.getName())
                        .expiresAt(token.getExpiresAt())
                        .createdAt(token.getCreatedAt())
                        .tokenId(token.getId())
                        .build())
                .toList();
    }

    /**
     * Delete an API token by ID (only if it belongs to the specified user).
     *
     * @param tokenId  the ID of the token to delete
     * @param userId   the ID of the authenticated user (for authorization)
     * @return true if deleted, false if not found or unauthorized
     */
    public boolean deleteApiToken(Long tokenId, Long userId) {
        Optional<ApiToken> tokenOpt = apiTokenRepository.findById(tokenId);
        if (tokenOpt.isEmpty()) {
            return false;
        }

        ApiToken token = tokenOpt.get();
        if (!token.getUser().getId().equals(userId)) {
            throw new IllegalArgumentException("Cannot delete token belonging to another user");
        }

        apiTokenRepository.deleteById(tokenId);
        return true;
    }

    /**
     * Find a user by username.
     */
    public Optional<User> findByUsername(String username) {
        return userRepository.findByUsername(username);
    }

    /**
     * Encode a password using BCrypt.
     */
    public String encodePassword(String rawPassword) {
        return passwordEncoder.encode(rawPassword);
    }

    /**
     * Verify a password against a hashed value.
     */
    public boolean verifyPassword(String rawPassword, String hashedPassword) {
        return passwordEncoder.matches(rawPassword, hashedPassword);
    }

    /**
     * Update the profile of an existing user by username.
     * Only non-null fields in the request are applied.
     *
     * @param username the username of the authenticated user (from SecurityContext)
     * @param request  the profile update request with optional fields
     * @return the updated UserDTO
     * @throws IllegalArgumentException if username/email already taken by another user, or currentPassword is wrong
     */
    @Transactional
    public UserDTO updateUserProfile(String username, ProfileUpdateRequestDTO request) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        // Update username if provided and different
        if (request.getUsername() != null && !request.getUsername().isBlank()) {
            if (!request.getUsername().equals(user.getUsername())) {
                // WIKI4AI-197: PUT /auth/me can rename, so it is a username write too.
                // Without this the registration rule is a turnstile someone can walk
                // around by renaming into the reserved name afterwards.
                ReservedUsernames.rejectIfReserved(request.getUsername());

                if (userRepository.existsByUsername(request.getUsername())) {
                    throw new IllegalArgumentException("Username is already taken");
                }
                user.setUsername(request.getUsername());
            }
        }

        // Update email if provided and different
        if (request.getEmail() != null && !request.getEmail().isBlank()) {
            if (!request.getEmail().equals(user.getEmail())) {
                if (userRepository.existsByEmail(request.getEmail())) {
                    throw new IllegalArgumentException("Email is already registered");
                }
                user.setEmail(request.getEmail());
            }
        }

        // Update password if newPassword is provided
        if (request.getNewPassword() != null && !request.getNewPassword().isBlank()) {
            // Verify current password if changing password
            if (request.getCurrentPassword() == null || request.getCurrentPassword().isBlank()) {
                throw new IllegalArgumentException("Current password is required to change password");
            }
            if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPassword())) {
                throw new IllegalArgumentException("Current password is incorrect");
            }
            String hashedNewPassword = passwordEncoder.encode(request.getNewPassword());
            user.setPassword(hashedNewPassword);
        }

        // WIKI4AI-73: update UI language if provided (only supported locales accepted)
        if (request.getLanguage() != null && !request.getLanguage().isBlank()) {
            String normalized = request.getLanguage().trim().toLowerCase(java.util.Locale.ROOT);
            if (!SUPPORTED_UI_LANGUAGES.contains(normalized)) {
                throw new IllegalArgumentException(
                        "Unsupported language: must be one of " + SUPPORTED_UI_LANGUAGES);
            }
            user.setLanguage(normalized);
        }

        userRepository.save(user);
        return convertToUserDTO(user);
    }
}
