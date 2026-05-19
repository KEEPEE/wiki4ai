package com.wiki4ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for copying a document to another project (or the same project).
 * If targetProjectSlug is null, the copy is created in the source project.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CopyRequestDTO {

    @Schema(description = "Slug of the target project. If omitted, copies within the same project.", example = "other-project")
    private String targetProjectSlug;
}
