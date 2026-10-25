package com.wiki4ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Data Transfer Object representing an actor assigned to a project
 * (WIKI4AI-132). Used in the responses of
 * {@code /api/v1/projects/{projectId}/actors}. Mirrors the columns of the V16
 * {@code project_actors} table.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectActorDTO {

    private Long id;

    /** Id of the project the actor is assigned to. */
    private Long projectId;

    /** Id of the assigned actor. */
    private Long actorId;

    /** 'viewer' | 'worker' | 'planner' | 'admin'. */
    private String role;

    private LocalDateTime createdAt;
}
