package com.wiki4ai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for updating an existing Document.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocumentUpdateDTO {

    @NotBlank(message = "Document title is required")
    private String title;

    private String content;
}
