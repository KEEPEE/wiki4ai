package com.wiki4ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Node of the project hierarchy tree returned by GET /api/v1/projects/{slug}/tree (WIKI4AI-30).
 * The root node is the requested project; children are nested recursively (max depth 5).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Node of the project hierarchy tree")
public class ProjectTreeNodeDTO {

    @Schema(description = "Project id", example = "1")
    private Long id;

    @Schema(description = "Project name", example = "My Wiki")
    private String name;

    @Schema(description = "URL-friendly project slug", example = "my-wiki")
    private String slug;

    @Schema(description = "Slug of the parent project, null for the root of the tree", example = "parent-wiki")
    private String parentSlug;

    @Schema(description = "Hierarchy depth (root = 1, max 5)", example = "2")
    private int depth;

    @Schema(description = "Whether the project has at least one subproject", example = "true")
    private boolean hasChildren;

    @Schema(description = "Number of documents directly in this project", example = "12")
    private int documentCount;

    @Builder.Default
    @Schema(description = "Nested subprojects")
    private List<ProjectTreeNodeDTO> children = new ArrayList<>();
}
