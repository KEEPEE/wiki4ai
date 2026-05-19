package com.wiki4ai.service;

import com.wiki4ai.dto.AuthResponseDTO;
import com.wiki4ai.dto.LoginRequestDTO;
import com.wiki4ai.dto.RegisterRequestDTO;
import com.wiki4ai.dto.UserDTO;
import com.wiki4ai.model.User;
import com.wiki4ai.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Service for authentication operations: user registration and login.
 */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    public AuthService(UserRepository userRepository) {
        this.userRepository = userRepository;
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
     * Generate a simple JWT-style access token (placeholder implementation).
     * The full JWT filter will be implemented in a separate story.
     */
    public String generateAccessToken(Long userId, String username) {
        // Simple placeholder: use UUID for now; real JWT will be added later
        return "eyJAI_access_token_" + userId + "_" + username + "_" + UUID.randomUUID();
    }

    /**
     * Generate a simple refresh token (placeholder implementation).
     */
    public String generateRefreshToken(Long userId) {
        return "refresh_" + userId + "_" + UUID.randomUUID();
    }

    /**
     * Convert User entity to UserDTO (excludes password).
     */
    private UserDTO convertToUserDTO(User user) {
        return UserDTO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .createdAt(user.getCreatedAt())
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
}
