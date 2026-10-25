package com.wiki4ai.service;

import com.wiki4ai.dto.ProjectActorAssignDTO;
import com.wiki4ai.dto.ProjectActorDTO;
import com.wiki4ai.dto.ProjectActorUpdateRoleDTO;
import com.wiki4ai.exception.BadRequestException;
import com.wiki4ai.model.Actor;
import com.wiki4ai.model.Project;
import com.wiki4ai.model.ProjectActor;
import com.wiki4ai.model.Role;
import com.wiki4ai.model.User;
import com.wiki4ai.repository.ActorRepository;
import com.wiki4ai.repository.ProjectActorRepository;
import com.wiki4ai.repository.ProjectRepository;
import com.wiki4ai.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Service layer for project actor assignments (WIKI4AI-132).
 * <p>
 * Handles the CRUD of {@code project_actors} rows, validation of {@code role}
 * (400), duplicate assignments (409), unknown project or actor (404) and — the
 * core of this story — the enforcement of the project's actor roles.
 * <p>
 * <b>Who may manage a project's actors?</b> Only a caller holding the
 * {@code admin} role for that project, which means exactly one of:
 * <ol>
 *   <li>the project's owner ({@code projects.owner_id}, same resolution as
 *       {@code ProjectService.canSeePrivate}),</li>
 *   <li>an ADMIN user ({@code users.role = 'ADMIN'}),</li>
 *   <li>a caller who owns an actor that has a {@code project_actors} row with
 *       {@code role = 'admin'} in that project.</li>
 * </ol>
 * Anyone else gets 403 Forbidden. This is enforced HERE, not in SecurityConfig:
 * the JWT filter grants ROLE_USER to everyone, so role checks in the filter
 * chain are useless (SPEC rule 8).
 * <p>
 * Visibility follows {@code ProjectService} (WIKI4AI-99): someone else's private
 * project is reported as 404, so its existence is not revealed; public projects
 * are readable by anyone, writes still need the admin role.
 * <p>
 * {@link #getRole(Long, Long)} and {@link #hasAtLeast(Long, Long, String)} are
 * exposed for the later coordination stories (work items, events, comments):
 * they answer "what role does this actor have in this project" without any
 * HTTP involvement.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProjectActorService {

    private final ProjectActorRepository projectActorRepository;
    private final ProjectRepository projectRepository;
    private final ActorRepository actorRepository;
    private final UserRepository userRepository;

    // ── Current-user resolution (same pattern as ActorService / ProjectService) ─

    /**
     * Resolve the requesting user from the authenticated username.
     * Returns null for anonymous callers (public read endpoints / test mode with
     * security disabled).
     */
    private User resolveUser(String username) {
        if (username == null || username.isBlank()
                || "anonymous".equals(username) || "anonymousUser".equals(username)) {
            return null;
        }
        return userRepository.findByUsername(username).orElse(null);
    }

    /**
     * Resolve the requesting user or reject the request (403) when no
     * authenticated user can be resolved. Used by every write operation.
     */
    private User requireUser(String username) {
        User user = resolveUser(username);
        if (user == null) {
            throw new AccessDeniedException("Authentication required");
        }
        return user;
    }

    // ── Project actors ────────────────────────────────────────────────────────

    /**
     * List the actors assigned to a project. The optional role filter accepts
     * 'viewer', 'worker', 'planner' or 'admin' (case-insensitive); an unknown
     * role is rejected with 400 so a typo cannot silently return an empty list.
     * <p>
     * Read follows project visibility: public projects are readable by anyone
     * (including anonymous callers, consistent with the public project
     * endpoints), private projects only by their owner or an ADMIN — anyone else
     * gets 404.
     */
    public List<ProjectActorDTO> listProjectActors(Long projectId, String role, String username) {
        User user = resolveUser(username);
        Project project = requireVisibleProject(projectId, user);

        List<ProjectActor> assignments;
        if (role == null || role.isBlank()) {
            assignments = projectActorRepository.findAllByProjectIdOrderByRoleAsc(project.getId());
        } else {
            // normalizeRole validates the value (400 for an unknown role) and
            // returns it lowercased.
            assignments = projectActorRepository.findByProjectIdAndRole(
                    project.getId(), normalizeRole(role));
        }

        return assignments.stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    /**
     * Assign an actor to a project. Requires the project's admin role.
     * Validation: unknown project or unknown actor is 404, an archived actor
     * cannot be assigned (400), an unknown role is 400, an actor that is already
     * assigned is 409 Conflict. An omitted role defaults to 'viewer'.
     */
    @Transactional
    public ProjectActorDTO assignActor(Long projectId, ProjectActorAssignDTO dto, String username) {
        User user = requireUser(username);
        Project project = requireProject(projectId);
        checkProjectVisible(project, user);
        requireProjectAdmin(project, user, "assign actors of");

        if (dto == null || dto.getActorId() == null) {
            throw new BadRequestException("Actor id is required");
        }
        Long actorId = dto.getActorId();

        Actor actor = requireActor(actorId);
        if (Actor.STATUS_ARCHIVED.equals(actor.getStatus())) {
            throw new BadRequestException(
                    "Actor '" + actor.getHandle() + "' is archived and cannot be assigned to a project");
        }

        String role = normalizeRole(dto.getRole());

        if (projectActorRepository.existsByProjectIdAndActorId(project.getId(), actorId)) {
            // GlobalExceptionHandler maps IllegalArgumentException -> 409 Conflict.
            throw new IllegalArgumentException(
                    "Actor " + actorId + " is already assigned to project " + project.getId());
        }

        ProjectActor assignment = new ProjectActor();
        assignment.setProjectId(project.getId());
        assignment.setActorId(actorId);
        assignment.setRole(role);

        return convertToDTO(saveAssignment(assignment, project.getId(), actorId));
    }

    /**
     * Change the role an actor has in a project. Requires the project's admin
     * role. Unknown project is 404, an actor that is not assigned to the project
     * is 404, an unknown role is 400.
     */
    @Transactional
    public ProjectActorDTO updateRole(Long projectId, Long actorId,
                                      ProjectActorUpdateRoleDTO dto, String username) {
        User user = requireUser(username);
        Project project = requireProject(projectId);
        checkProjectVisible(project, user);
        requireProjectAdmin(project, user, "change actors of");

        if (dto == null || dto.getRole() == null || dto.getRole().isBlank()) {
            throw new BadRequestException("Role is required");
        }
        String role = normalizeRole(dto.getRole());

        ProjectActor assignment = requireAssignment(project.getId(), actorId);
        assignment.setRole(role);

        return convertToDTO(saveAssignment(assignment, project.getId(), actorId));
    }

    /**
     * Remove an actor from a project. Requires the project's admin role.
     * Unknown project or an actor that is not assigned is 404.
     */
    @Transactional
    public void removeActor(Long projectId, Long actorId, String username) {
        User user = requireUser(username);
        Project project = requireProject(projectId);
        checkProjectVisible(project, user);
        requireProjectAdmin(project, user, "remove actors of");

        ProjectActor assignment = requireAssignment(project.getId(), actorId);
        projectActorRepository.delete(assignment);
    }

    // ── Role queries for the coordination layer ───────────────────────────────

    /**
     * The role the given actor has in the given project, or null when the actor
     * is not assigned to it. Reports the stored assignment as-is — it does not
     * answer whether the actor may currently act (see {@link #hasAtLeast}).
     *
     * @param projectId project id
     * @param actorId   actor id
     * @return 'viewer' | 'worker' | 'planner' | 'admin', or null when unassigned
     */
    public String getRole(Long projectId, Long actorId) {
        if (projectId == null || actorId == null) {
            return null;
        }
        return projectActorRepository.findByProjectIdAndActorId(projectId, actorId)
                .map(ProjectActor::getRole)
                .orElse(null);
    }

    /**
     * Whether the actor's role in the project is at least the required role in
     * the ordering viewer &lt; worker &lt; planner &lt; admin.
     * <p>
     * An actor that is not assigned to the project has no role and never passes.
     * An actor whose registry status is 'archived' is kept for history and is
     * not usable for new work, so it never passes either — its stored role is
     * still visible through {@link #getRole}.
     *
     * @param projectId    project id
     * @param actorId      actor id
     * @param requiredRole 'viewer' | 'worker' | 'planner' | 'admin'
     * @return true when the actor holds at least the required role
     */
    public boolean hasAtLeast(Long projectId, Long actorId, String requiredRole) {
        String required = normalizeRole(requiredRole);
        String actual = getRole(projectId, actorId);
        if (actual == null || roleRank(actual) < roleRank(required)) {
            return false;
        }
        // Archived actors are not usable for new work (V15 semantics).
        return actorRepository.findById(actorId)
                .map(actor -> !Actor.STATUS_ARCHIVED.equals(actor.getStatus()))
                .orElse(false);
    }

    // ── Access and lookup helpers ─────────────────────────────────────────────

    /**
     * Load a project or fail with 404 Not Found.
     */
    private Project requireProject(Long projectId) {
        if (projectId == null) {
            throw new EntityNotFoundException("Project not found with id: null");
        }
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new EntityNotFoundException("Project not found with id: " + projectId));
    }

    /**
     * Load a project and enforce visibility (WIKI4AI-99 pattern from
     * ProjectService): public projects pass for everyone, private projects only
     * for their owner or an ADMIN — anyone else gets 404 so the existence of
     * someone else's private project is not revealed.
     */
    private Project requireVisibleProject(Long projectId, User user) {
        Project project = requireProject(projectId);
        checkProjectVisible(project, user);
        return project;
    }

    /**
     * Whether the given user may see a private project: its owner or an ADMIN.
     * Same rule as {@code ProjectService.canSeePrivate}.
     */
    private boolean canSeePrivate(Project project, User user) {
        if (user == null) {
            return false;
        }
        boolean isOwner = project.getOwner() != null
                && user.getId() != null && user.getId().equals(project.getOwner().getId());
        return isOwner || user.getRole() == Role.ADMIN;
    }

    private void checkProjectVisible(Project project, User user) {
        if (!Project.VISIBILITY_PRIVATE.equals(project.getVisibility())) {
            return;
        }
        if (!canSeePrivate(project, user)) {
            throw new EntityNotFoundException("Project not found with id: " + project.getId());
        }
    }

    /**
     * Enforce the admin role for actor management (the core rule of this story).
     * Allowed: the project owner, an ADMIN user, or a caller who owns an actor
     * that holds {@code role='admin'} in this project. Anyone else gets 403.
     */
    private void requireProjectAdmin(Project project, User user, String action) {
        if (isProjectAdmin(project, user)) {
            return;
        }
        throw new AccessDeniedException(
                "You can only " + action + " projects when you hold the 'admin' actor role "
                        + "(or you own the project, or you are an ADMIN user)");
    }

    /**
     * Whether the user holds the project's admin role by any of the three ways
     * described on the class level.
     */
    private boolean isProjectAdmin(Project project, User user) {
        if (user == null) {
            return false;
        }
        if (user.getRole() == Role.ADMIN) {
            return true;
        }
        if (project.getOwner() != null
                && user.getId() != null && user.getId().equals(project.getOwner().getId())) {
            return true;
        }
        return callerOwnsAdminActor(project, user);
    }

    /**
     * Third way of holding the admin role: the caller owns an actor that has a
     * project_actors row with role='admin' in this project. Checked through the
     * project's admin assignments (usually a handful of rows) instead of an
     * {@code IN (...)} query, so a caller owning no actor at all cannot produce
     * an empty IN list.
     */
    private boolean callerOwnsAdminActor(Project project, User user) {
        List<ProjectActor> admins = projectActorRepository.findByProjectIdAndRole(
                project.getId(), ProjectActor.ROLE_ADMIN);
        if (admins.isEmpty()) {
            return false;
        }
        List<Long> adminActorIds = admins.stream()
                .map(ProjectActor::getActorId)
                .collect(Collectors.toList());
        return actorRepository.findAllById(adminActorIds).stream()
                .anyMatch(actor -> user.getId() != null
                        && user.getId().equals(actor.getOwnerUserId()));
    }

    /**
     * Load an actor or fail with 404 Not Found.
     */
    private Actor requireActor(Long actorId) {
        return actorRepository.findById(actorId)
                .orElseThrow(() -> new EntityNotFoundException("Actor not found with id: " + actorId));
    }

    /**
     * Load the assignment of an actor to a project, or fail with 404 Not Found
     * (an actor that is not assigned to the project has no role to change).
     */
    private ProjectActor requireAssignment(Long projectId, Long actorId) {
        return projectActorRepository.findByProjectIdAndActorId(projectId, actorId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Actor " + actorId + " is not assigned to project " + projectId));
    }

    /**
     * Save the assignment, translating a UNIQUE-constraint violation into the
     * same 409 shape the pre-check produces. The pre-check covers the normal
     * case; this covers two concurrent requests racing for the same pair.
     */
    private ProjectActor saveAssignment(ProjectActor assignment, Long projectId, Long actorId) {
        try {
            return projectActorRepository.save(assignment);
        } catch (DataIntegrityViolationException e) {
            // GlobalExceptionHandler maps IllegalArgumentException -> 409 Conflict.
            throw new IllegalArgumentException(
                    "Actor " + actorId + " is already assigned to project " + projectId);
        }
    }

    // ── Validation helpers ────────────────────────────────────────────────────

    /**
     * Normalize and validate the role ('viewer' | 'worker' | 'planner' | 'admin');
     * null/blank defaults to 'viewer'.
     */
    private String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            return ProjectActor.ROLE_VIEWER;
        }
        String normalized = role.trim().toLowerCase(Locale.ROOT);
        if (!ProjectActor.ROLE_VIEWER.equals(normalized)
                && !ProjectActor.ROLE_WORKER.equals(normalized)
                && !ProjectActor.ROLE_PLANNER.equals(normalized)
                && !ProjectActor.ROLE_ADMIN.equals(normalized)) {
            throw new BadRequestException("Invalid role '" + role
                    + "'. Allowed values: 'viewer', 'worker', 'planner', 'admin'");
        }
        return normalized;
    }

    /**
     * Rank of a role in the permission ordering. An unknown role ranks lowest,
     * so it can never satisfy a requirement.
     */
    private static int roleRank(String role) {
        if (role == null) {
            return 0;
        }
        return switch (role) {
            case ProjectActor.ROLE_VIEWER -> 1;
            case ProjectActor.ROLE_WORKER -> 2;
            case ProjectActor.ROLE_PLANNER -> 3;
            case ProjectActor.ROLE_ADMIN -> 4;
            default -> 0;
        };
    }

    // ── DTO conversion (manual — this project does not use MapStruct) ─────────

    /**
     * Convert a ProjectActor entity to its DTO.
     */
    private ProjectActorDTO convertToDTO(ProjectActor assignment) {
        return ProjectActorDTO.builder()
                .id(assignment.getId())
                .projectId(assignment.getProjectId())
                .actorId(assignment.getActorId())
                .role(assignment.getRole())
                .createdAt(assignment.getCreatedAt())
                .build();
    }
}
