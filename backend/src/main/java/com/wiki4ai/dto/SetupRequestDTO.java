package com.wiki4ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for the first-run setup request (WIKI4AI-69).
 * <p>
 * Used by {@code POST /api/v1/auth/setup}, which is only accepted while the
 * users table is empty. The created account always receives the ADMIN role.
 * <p>
 * Email is optional: when omitted, the backend derives {@code {username}@localhost} —
 * the same convention used by the InitialAdminBootstrap seed, so setup-created
 * accounts stay consistent with bootstrap-provisioned ones.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SetupRequestDTO {

    @NotBlank(message = "Username is required")
    @Size(min = 2, max = 50, message = "Username must be between 2 and 50 characters")
    private String username;

    /** Optional. When blank/null the backend derives {@code {username}@localhost}. */
    private String email;

    @NotBlank(message = "Password is required")
    @Size(min = 8, message = "Password must be at least 8 characters")
    private String password;
}
