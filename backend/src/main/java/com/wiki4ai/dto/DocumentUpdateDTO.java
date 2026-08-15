package com.wiki4ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for updating an existing Document.
 * Title and content are both optional, but at least one must be provided
 * (enforced in DocumentService). Omitted fields remain unchanged.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocumentUpdateDTO {

    private String title;

    private String content;
}
