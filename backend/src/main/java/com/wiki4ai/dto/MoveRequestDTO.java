package com.wiki4ai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for moving a document to another project.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MoveRequestDTO {

    @NotBlank(message = "Target project slug is required")
    private String targetProjectSlug;
}
