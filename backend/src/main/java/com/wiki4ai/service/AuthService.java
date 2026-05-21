package com.wiki4ai.service;

import com.wiki4ai.config.JwtUtil;
import com.wiki4ai.dto.AuthResponseDTO;
import com.wiki4ai.dto.LoginRequestDTO;
import com.wiki4ai.dto.ProfileUpdateRequestDTO;
import com.wiki4ai.dto.RegisterRequestDTO;
import com.wiki4ai.dto.UserDTO;
import com.wiki4ai.model.RefreshToken;
import com.wiki4ai.model.User;
import com.wiki4ai.repository.RefreshTokenRepository;
import com.wiki4ai.repository.UserRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Service for authentication operations: user registration and login.
 * Only loaded when security.enabled=true.
 */
@Service
@ConditionalOnProperty(name = "security.enabled", havingValue = "true", matchIfMissing = true)
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public AuthService(UserRepository userRepository, RefreshTokenRepository refreshTokenRepository, JwtUtil jwtUtil) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
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

        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
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
     */
    public String generateAndPersistRefreshToken(User user) {
        String refreshToken = jwtUtil.generateRefreshToken(user.getUsername());

        // Calculate expiration (use JwtUtil's refreshExpiration setting)
        LocalDateTime expiresAt = LocalDateTime.now().plusSeconds(jwtUtil.getRefreshExpirationSeconds());

        RefreshToken tokenEntity = RefreshToken.builder()
                .user(user)
                .token(refreshToken)
                .expiresAt(expiresAt)
                .build();

        refreshTokenRepository.save(tokenEntity);

        return refreshToken;
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
        String refreshToken = generateAndPersistRefreshToken(user);

        return AuthResponseDTO.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .user(convertToUserDTO(user))
                .build();
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
