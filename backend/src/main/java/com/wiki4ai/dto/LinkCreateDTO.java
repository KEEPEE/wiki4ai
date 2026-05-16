package com.wiki4ai.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for creating a link between documents.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LinkCreateDTO {

    @NotNull(message = "Target document ID is required")
    private Long targetDocumentId;
}
