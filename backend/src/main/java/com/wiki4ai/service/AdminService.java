package com.wiki4ai.service;

import com.wiki4ai.dto.CreateUserRequestDTO;
import com.wiki4ai.dto.UserDTO;
import com.wiki4ai.model.Role;
import com.wiki4ai.model.User;
import com.wiki4ai.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for admin operations: managing users, roles, and system-level actions.
 * All methods verify that the caller has ADMIN role before proceeding.
 */
@Service
public class AdminService {

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    public AdminService(UserRepository userRepository) {
        this.userRepository = userRepository;
        this.passwordEncoder = new BCryptPasswordEncoder();
    }

    /**
     * List all users with pagination and optional search filter.
     * Only accessible by users with ADMIN role.
     *
     * @param pageable pagination information (page, size, sort)
     * @param search   optional search term to filter by username or email (case-insensitive)
     * @return a page of UserDTO objects
     * @throws SecurityException if the current user does not have ADMIN role
     */
    @Transactional(readOnly = true)
    public Page<UserDTO> listUsers(Pageable pageable, String search) {
        verifyAdminRole();

        Page<User> users;
        if (search != null && !search.isBlank()) {
            users = userRepository.findByUsernameContainingIgnoreCaseOrEmailContainingIgnoreCase(
                    search, search, pageable);
        } else {
            users = userRepository.findAll(pageable);
        }

        return users.map(this::convertToUserDTO);
    }

    /**
     * Create a new user with the specified credentials and role.
     * Only accessible by users with ADMIN role.
     *
     * @param request the creation request containing username, email, password, and role
     * @return the created UserDTO (without password)
     * @throws SecurityException if the current user does not have ADMIN role
     * @throws IllegalArgumentException if username or email is already taken
     */
    @Transactional
    public UserDTO createUser(CreateUserRequestDTO request) {
        verifyAdminRole();

        // Validate uniqueness of username
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new IllegalArgumentException("Username '" + request.getUsername() + "' is already taken");
        }

        // Validate uniqueness of email
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new IllegalArgumentException("Email '" + request.getEmail() + "' is already registered");
        }

        // Hash the password and create user entity
        String hashedPassword = passwordEncoder.encode(request.getPassword());

        User user = User.builder()
                .username(request.getUsername())
                .email(request.getEmail())
                .password(hashedPassword)
                .role(request.getRole())
                .build();

        userRepository.save(user);

        return convertToUserDTO(user);
    }

    /**
     * Verify that the currently authenticated user has ADMIN role.
     * Throws SecurityException if not admin or not authenticated.
     */
    protected void verifyAdminRole() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        // Check if there is any authentication at all
        if (authentication == null || authentication.getName() == null
                || "anonymousUser".equals(authentication.getName())) {
            throw new SecurityException("Authentication required. Please provide a valid JWT token.");
        }

        String username = authentication.getName();
        User currentUser = userRepository.findByUsername(username)
                .orElseThrow(() -> new SecurityException("Authenticated user not found in database"));

        if (currentUser.getRole() != Role.ADMIN) {
            throw new SecurityException("Admin access required. Current role: " + currentUser.getRole());
        }
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
}
