package com.wiki4ai.service;

import com.wiki4ai.config.JwtUtil;
import com.wiki4ai.dto.AuthResponseDTO;
import com.wiki4ai.dto.LoginRequestDTO;
import com.wiki4ai.dto.ProfileUpdateRequestDTO;
import com.wiki4ai.dto.RegisterRequestDTO;
import com.wiki4ai.dto.TokenGenerationRequestDTO;
import com.wiki4ai.dto.TokenResponseDTO;
import com.wiki4ai.dto.UserDTO;
import com.wiki4ai.model.ApiToken;
import com.wiki4ai.model.RefreshToken;
import com.wiki4ai.model.User;
import com.wiki4ai.repository.ApiTokenRepository;
import com.wiki4ai.repository.RefreshTokenRepository;
import com.wiki4ai.repository.UserRepository;
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
@ConditionalOnProperty(name = "security.enabled", havingValue = "true", matchIfMissing = true)
public class AuthService {

    /**
     * WIKI4AI-68: striped locks that serialize refresh-token replacement (DELETE + INSERT)
     * per user. Two concurrent logins for the same user would otherwise both observe
     * "no existing token", both INSERT, and one violates the unique constraint on
     * refresh_tokens.user_id (DataIntegrityViolationException → intermittent 500 on login).
     * Stripes avoid unbounded lock-object growth; single-JVM deployment is a documented invariant.
     */
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

    public AuthService(UserRepository userRepository, RefreshTokenRepository refreshTokenRepository, 
                       ApiTokenRepository apiTokenRepository, EntityManager entityManager, 
                       TransactionTemplate transactionTemplate, JwtUtil jwtUtil) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.apiTokenRepository = apiTokenRepository;
        this.entityManager = entityManager;
        this.transactionTemplate = transactionTemplate;
        this.jwtUtil = jwtUtil;
        this.passwordEncoder = new BCryptPasswordEncoder();
    }

    /**
     * Register a new user with the given credentials.
     *
     * @param request the registration request containing username, email, and password
     * @return the created UserDTO (without password)
     */
    @Transactional
    public UserDTO registerUser(RegisterRequestDTO request) {
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
     * Convert User entity to UserDTO (excludes password).
     */
    private UserDTO convertToUserDTO(User user) {
        return UserDTO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .role(user.getRole())
                .createdAt(user.getCreatedAt())
                .build();
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

        userRepository.save(user);
        return convertToUserDTO(user);
    }
}
