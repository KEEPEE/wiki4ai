package com.wiki4ai.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for profile update requests.
 * All fields are optional — only provided fields will be updated.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProfileUpdateRequestDTO {

    private String username;

    @Email(message = "Invalid email format")
    private String email;

    private String currentPassword;

    @Size(min = 3, message = "New password must be at least 3 characters long")
    private String newPassword;
}
