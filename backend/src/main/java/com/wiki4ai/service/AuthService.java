package com.wiki4ai.service;

import com.wiki4ai.config.JwtUtil;
import com.wiki4ai.dto.AuthResponseDTO;
import com.wiki4ai.dto.LoginRequestDTO;
import com.wiki4ai.dto.RegisterRequestDTO;
import com.wiki4ai.dto.UserDTO;
import com.wiki4ai.model.User;
import com.wiki4ai.repository.UserRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Service for authentication operations: user registration and login.
 * Only loaded when security.enabled=true.
 */
@Service
@ConditionalOnProperty(name = "security.enabled", havingValue = "true", matchIfMissing = true)
public class AuthService {

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public AuthService(UserRepository userRepository, JwtUtil jwtUtil) {
        this.userRepository = userRepository;
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
        String refreshToken = generateRefreshToken(user.getId());

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
     * Generate a real JWT refresh token for the user.
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
}
