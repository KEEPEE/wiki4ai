package com.wiki4ai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for creating a new Document.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocumentCreateDTO {

    @NotBlank(message = "Document title is required")
    private String title;

    private String content;

    /**
     * Optional visibility: 'public' or 'private' (WIKI4AI-99). Null/absent =
     * 'public' (backward compatible — clients that never send it keep working).
     * Any other value is rejected with 400. The owner is always the authenticated
     * creator and cannot be set from the client.
     */
    private String visibility;
}
