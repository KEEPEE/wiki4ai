package com.wiki4ai.service;

import com.wiki4ai.dto.ProjectCreateDTO;
import com.wiki4ai.dto.ProjectDTO;
import com.wiki4ai.dto.ProjectTreeNodeDTO;
import com.wiki4ai.dto.ProjectUpdateDTO;
import com.wiki4ai.dto.ReorderRequestDTO;
import com.wiki4ai.exception.BadRequestException;
import com.wiki4ai.model.Document;
import com.wiki4ai.model.Permission;
import com.wiki4ai.model.Project;
import com.wiki4ai.model.Role;
import com.wiki4ai.model.User;
import com.wiki4ai.model.UserProjectOrder;
import com.wiki4ai.repository.DocumentRepository;
import com.wiki4ai.repository.ProjectPermissionRepository;
import com.wiki4ai.repository.ProjectRepository;
import com.wiki4ai.repository.UserProjectOrderRepository;
import com.wiki4ai.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.OptimisticLockException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.stream.Collectors;

/**
 * Service layer for Project business logic.
 * Handles CRUD operations, validation, and project management with RBAC permissions.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final DocumentRepository documentRepository;
    private final PermissionService permissionService;
    private final ProjectPermissionRepository projectPermissionRepository;
    private final UserRepository userRepository;

    /**
     * One viewer's ordering of one project (WIKI4AI-200, PER-1).
     *
     * <p>{@code projects.pinned} and {@code projects.sort_order} remain what they always
     * were — one global, curated state — and this repository holds the per-viewer override
     * of it. A viewer with no row here sees the global value, which is why the feature
     * ships with an empty table and nobody's dashboard changes.</p>
     */
    private final UserProjectOrderRepository userProjectOrderRepository;

    // ── Visibility (WIKI4AI-99) ───────────────────────────────────────────────
    // PRIVATE projects are visible only to their owner and ADMIN users. For
    // everyone else they are reported as 404 / absent from lists so that the
    // existence of someone else's private project is not revealed. Public
    // projects keep the exact pre-privacy behaviour (including the existing
    // project_permissions sharing — it is untouched by this feature).

    /**
     * Resolve the requesting user from the authenticated username.
     * Returns null for anonymous callers (public endpoints / test mode with
     * security disabled) — they only ever see public projects.
     */
    private User resolveUser(String username) {
        if (username == null || username.isBlank()
                || "anonymous".equals(username) || "anonymousUser".equals(username)) {
            return null;
        }
        return userRepository.findByUsername(username).orElse(null);
    }

    /**
     * Whether the given user may see a private project: its owner or an ADMIN.
     */
    private boolean canSeePrivate(Project project, User user) {
        if (user == null) {
            return false;
        }
        boolean isOwner = project.getOwner() != null
                && user.getId() != null && user.getId().equals(project.getOwner().getId());
        return isOwner || user.getRole() == Role.ADMIN;
    }

    /**
     * Enforce the visibility rule for a single project. Public projects pass for
     * everyone; private projects only for their owner or an ADMIN. Other callers
     * receive EntityNotFoundException (404) so that the existence of someone
     * else's private project is not revealed.
     */
    private void checkProjectVisible(Project project, User user) {
        if (!Project.VISIBILITY_PRIVATE.equals(project.getVisibility())) {
            return;
        }
        if (!canSeePrivate(project, user)) {
            throw new EntityNotFoundException("Project not found with slug: " + project.getSlug());
        }
    }

    /**
     * Validate the visibility value (null/blank = keep default, otherwise it
     * must be 'public' or 'private', case-insensitive).
     */
    private void validateVisibility(String visibility) {
        if (visibility == null || visibility.isBlank()) {
            return;
        }
        if (!Project.VISIBILITY_PUBLIC.equalsIgnoreCase(visibility.trim())
                && !Project.VISIBILITY_PRIVATE.equalsIgnoreCase(visibility.trim())) {
            throw new BadRequestException(
                    "Invalid visibility '" + visibility + "'. Allowed values: 'public', 'private'");
        }
    }

    /**
     * Normalize the visibility value to lowercase; null/blank defaults to 'public'.
     */
    private String normalizeVisibility(String visibility) {
        return (visibility == null || visibility.isBlank())
                ? Project.VISIBILITY_PUBLIC
                : visibility.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Get all projects ordered by creation date (newest first), applying the
     * visibility filter (WIKI4AI-99): ADMIN sees everything; a regular user sees
     * public projects plus their own private ones; anonymous callers see only
     * public projects.
     * <p>
     * Kept as the no-parameter form of {@link #getAllProjects(String, String, String, String)}
     * (WIKI4AI-184): it now also floats pinned projects to the top, which is the one
     * behaviour change a caller that sends no parameters can observe.
     */
    public List<ProjectDTO> getAllProjects(String username) {
        return getAllProjects(null, null, null, username);
    }

    /**
     * List projects with the WIKI4AI-184 sort and filter parameters.
     *
     * <p>Three independent things happen here, in this order:</p>
     * <ol>
     *   <li><b>visibility filter (WIKI4AI-99)</b> — unchanged, and it runs first so a
     *       private project of someone else is absent before any ordering could leak it.</li>
     *   <li><b>{@code q}</b> — case-insensitive substring of name OR description.</li>
     *   <li><b>{@code visibility}</b> — {@code public} or {@code private}; narrows the
     *       result to that visibility. It narrows, it never widens: a regular caller can
     *       not ask for {@code private} and see other people's private projects, because
     *       step 1 already removed them.</li>
     * </ol>
     *
     * <p>Ordering is {@link ListSort}: pinned items first, then the requested key, with
     * {@code id} as the final tie-break. Sorting happens in Java because the list is not
     * paginated and the visibility filter is already in Java — one code path instead of
     * one query per sort key.</p>
     *
     * <p><b>WIKI4AI-200 (PER-1): {@code pinned} and the manual position are the caller's,
     * not the project's.</b> The caller's {@code user_project_order} rows are read once and
     * overlaid on the projects they name; a project with no row keeps its global V26 value.
     * That overlay is what the list is sorted by AND what the DTO reports, so the order the
     * caller is shown and the {@code pinned}/{@code sortOrder} they are told always agree —
     * a list sorted by one and reporting the other is the bug this paragraph exists to
     * prevent. The overlay never widens the visibility filter, which runs first, so a
     * private project of someone else is absent before any per-viewer value could leak it.</p>
     *
     * @param sort       one of {@link ListSort#ALLOWED}; null/blank = {@code created_desc}
     * @param q          case-insensitive filter on name and description; null/blank = no filter
     * @param visibility {@code public} or {@code private}; null/blank = both
     * @param username   the authenticated caller (null/anonymous = public projects only)
     * @return the filtered, ordered projects
     * @throws BadRequestException on an unknown {@code sort} or {@code visibility} value
     */
    public List<ProjectDTO> getAllProjects(String sort, String q, String visibility, String username) {
        String sortKey = ListSort.normalize(sort, ListSort.DEFAULT_PROJECT_SORT, "sort");
        String visibilityFilter = normalizeVisibilityFilter(visibility);
        String needle = (q == null || q.isBlank()) ? null : q.trim().toLowerCase(Locale.ROOT);

        User user = resolveUser(username);
        Map<Long, UserProjectOrder> viewerRows = viewerOrderRows(user);

        List<ViewerProject> visible = projectRepository.findAll()
                .stream()
                .filter(p -> !Project.VISIBILITY_PRIVATE.equals(p.getVisibility()) || canSeePrivate(p, user))
                .filter(p -> visibilityFilter == null || visibilityFilter.equals(p.getVisibility()))
                .filter(p -> needle == null || matchesQuery(p, needle))
                .map(p -> new ViewerProject(p, effectiveOrder(p, viewerRows)))
                .sorted(Comparator.comparing(ViewerProject::key, ListSort.sortKeyComparator(sortKey)))
                .collect(Collectors.toList());

        // WIKI4AI-197 commit 16: one grouped query for every count on the page. Counting
        // per project made this endpoint issue one query per card, each loading all of the
        // project's document rows just to take their size.
        Map<Long, Long> counts = documentCounts(
                visible.stream().map(ViewerProject::project).collect(Collectors.toList()));
        return visible.stream()
                .map(v -> convertToDTO(v.project(), counts.getOrDefault(v.project().getId(), 0L),
                        viewerRows.get(v.project().getId())))
                .collect(Collectors.toList());
    }

    /**
     * A project paired with the ordering values the caller sees (WIKI4AI-200).
     *
     * <p>The pairing exists because the sort and the DTO must read the same values. A
     * {@code Project} alone cannot carry that: two callers looking at the same row see two
     * different positions, and the row itself holds only the global one.</p>
     */
    private record ViewerProject(Project project, ListSort.ProjectSortKey key) {
    }

    /**
     * The caller's ordering rows, keyed by project id — one query for the whole list.
     *
     * <p>Empty for an anonymous caller and for a viewer who has customised nothing, which
     * is the normal case and not a special one: an empty map means every project keeps its
     * global value.</p>
     */
    private Map<Long, UserProjectOrder> viewerOrderRows(User user) {
        if (user == null || user.getId() == null) {
            return Collections.emptyMap();
        }
        return userProjectOrderRepository.findByUserId(user.getId())
                .stream()
                .collect(Collectors.toMap(UserProjectOrder::getProjectId, Function.identity(),
                        (a, b) -> a));
    }

    /**
     * The ordering values of one project as this caller sees them (WIKI4AI-200).
     *
     * <p>The project supplies everything; the viewer's row, when there is one, replaces
     * exactly the two values that became per-viewer. Name and dates stay the project's,
     * because per-user ordering customises a position, not the project.</p>
     */
    private ListSort.ProjectSortKey effectiveOrder(Project project,
                                                   Map<Long, UserProjectOrder> viewerRows) {
        UserProjectOrder row = viewerRows.get(project.getId());
        ListSort.ProjectSortKey global = ListSort.ProjectSortKey.of(project);
        return row == null
                ? global
                : global.withViewerOrder(row.isPinned(), row.getSortOrder());
    }

    /**
     * The one ordering row this caller holds for this project, or null (WIKI4AI-200).
     *
     * <p>The single-project form of the two reads and of every write response. The
     * frontend applies a pin response straight to its local list (WIKI4AI-186), so a
     * global value here would visibly undo the pin the user just made.</p>
     */
    private UserProjectOrder findViewerOrder(User user, Project project) {
        if (user == null || user.getId() == null || project.getId() == null) {
            return null;
        }
        return userProjectOrderRepository
                .findByUserIdAndProjectId(user.getId(), project.getId())
                .orElse(null);
    }

    /**
     * Case-insensitive substring test on name and description (WIKI4AI-184). Null-safe:
     * a project with no description still matches a needle that hits its name.
     */
    private boolean matchesQuery(Project project, String lowerNeedle) {
        if (project.getName() != null
                && project.getName().toLowerCase(Locale.ROOT).contains(lowerNeedle)) {
            return true;
        }
        return project.getDescription() != null
                && project.getDescription().toLowerCase(Locale.ROOT).contains(lowerNeedle);
    }

    /**
     * Validate the {@code visibility} query parameter. Unlike the visibility of a
     * create/update payload there is no default to fall back to: absent means "both",
     * so only an explicit, valid value narrows the list.
     */
    private String normalizeVisibilityFilter(String visibility) {
        if (visibility == null || visibility.isBlank()) {
            return null;
        }
        String value = visibility.trim().toLowerCase(Locale.ROOT);
        if (!Project.VISIBILITY_PUBLIC.equals(value) && !Project.VISIBILITY_PRIVATE.equals(value)) {
            throw new BadRequestException(
                    "Unknown visibility '" + visibility + "'. Allowed values: public, private");
        }
        return value;
    }

    /**
     * Get a project by ID. Visibility-aware (WIKI4AI-99): other users' private
     * projects are reported as 404.
     */
    public ProjectDTO getProjectById(Long id, String username) {
        User user = resolveUser(username);
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Project not found with id: " + id));
        checkProjectVisible(project, user);
        return convertToDTO(project, user);
    }

    /**
     * Get a project by slug. Visibility-aware (WIKI4AI-99): other users' private
     * projects are reported as 404 (existence is not revealed).
     */
    public ProjectDTO getProjectBySlug(String slug, String username) {
        User user = resolveUser(username);
        Project project = projectRepository.findBySlug(slug)
                .orElseThrow(() -> new EntityNotFoundException("Project not found with slug: " + slug));
        checkProjectVisible(project, user);
        return convertToDTO(project, user);
    }

    /**
     * Create a new project.
     * Available to any authenticated user. Auto-grants MANAGE permission to the creator.
     */
    @Transactional
    public ProjectDTO createProject(ProjectCreateDTO dto, String username) {
        // Validate uniqueness of name
        if (projectRepository.existsByName(dto.getName())) {
            throw new IllegalArgumentException("A project with this name already exists");
        }

        // Optional parent → subproject (WIKI4AI-29): validate hierarchy depth ≤ 5.
        Project parent = null;
        if (dto.getParentId() != null) {
            parent = projectRepository.findById(dto.getParentId())
                    .orElseThrow(() -> new EntityNotFoundException(
                            "Parent project not found with id: " + dto.getParentId()));
            int childDepth = parent.getDepth() + 1;
            if (childDepth > Project.MAX_HIERARCHY_DEPTH) {
                throw new BadRequestException(
                        "Cannot create subproject: maximum hierarchy depth of "
                                + Project.MAX_HIERARCHY_DEPTH + " levels would be exceeded");
            }
        }

        // WIKI4AI-99: visibility is optional (default 'public'); the owner is always
        // the authenticated creator and never comes from the client.
        validateVisibility(dto.getVisibility());
        User creator = resolveUser(username);

        Project project = new Project();
        // Slug is generated from the name in Project.onCreate() (@PrePersist) on save;
        // setName() must NOT touch the slug (WIKI4AI-54: rename keeps the URL stable).
        project.setName(dto.getName());
        project.setDescription(dto.getDescription());
        project.setVisibility(normalizeVisibility(dto.getVisibility()));
        if (creator != null) {
            project.setOwner(creator);
        }
        if (parent != null) {
            parent.addChild(project);
        }

        // WIKI4AI-184: a new project takes the slot above the current first one, so a
        // sort=manual list shows it where created_desc has always shown it. Negative
        // positions are expected — pinning uses the same trick — and the first reorder
        // renumbers the whole list densely, which brings it back to 0..n-1.
        // WIKI4AI-197 commit 11: min-1 is read under the ordering lock, or two concurrent
        // creates both take the same slot above it.
        lockProjectList();
        project.setSortOrder(projectRepository.minSortOrder() - 1);

        Project saved = projectRepository.save(project);

        // Auto-grant MANAGE permission to the creator
        if (username != null && !username.isBlank() && !"anonymous".equals(username)) {
            permissionService.grantManageToCreator(username, saved.getId());
        }

        return convertToDTO(saved, creator);
    }

    /**
     * Update an existing project by ID.
     * Requires MANAGE permission on the project.
     */
    @Transactional
    public ProjectDTO updateProject(Long id, ProjectDTO dto, String username) {
        User user = resolveUser(username);
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Project not found with id: " + id));

        // WIKI4AI-99: a foreign private project is 404 (existence not revealed);
        // public projects keep the existing MANAGE check (403 without it).
        checkProjectVisible(project, user);

        permissionService.checkPermission(username, project.getId(), Permission.MANAGE);

        project.setName(dto.getName());
        project.setDescription(dto.getDescription());
        applyVisibilityChange(project, dto.getVisibility());

        Project saved = projectRepository.save(project);
        return convertToDTO(saved, user);
    }

    /**
     * Update an existing project by slug.
     * Requires MANAGE permission on the project.
     */
    @Transactional
    public ProjectDTO updateProjectBySlug(String slug, ProjectUpdateDTO dto, String username) {
        User user = resolveUser(username);
        Project project = projectRepository.findBySlug(slug)
                .orElseThrow(() -> new EntityNotFoundException("Project not found with slug: " + slug));

        // WIKI4AI-99: a foreign private project is 404 (existence not revealed);
        // public projects keep the existing MANAGE check (403 without it).
        checkProjectVisible(project, user);

        permissionService.checkPermission(username, project.getId(), Permission.MANAGE);

        // Partial update: null fields are left unchanged (WIKI4AI-99 makes a
        // visibility-only payload valid — it must not wipe name/description).
        if (dto.getName() != null) {
            project.setName(dto.getName());
        }
        if (dto.getDescription() != null) {
            project.setDescription(dto.getDescription());
        }
        applyVisibilityChange(project, dto.getVisibility());

        // Optional hierarchy move (WIKI4AI-30): only when the payload explicitly
        // contains a "parentId" key. Explicit null = back to root; absent = no move.
        if (dto.isParentIdPresent()) {
            return doMove(project, dto.getParentId(), user);
        }

        Project saved = projectRepository.save(project);
        return convertToDTO(saved, user);
    }

    /**
     * Update an existing project by ID.
     * Requires MANAGE permission on the project.
     */
    @Transactional
    public ProjectDTO updateProjectById(Long id, ProjectUpdateDTO dto, String username) {
        User user = resolveUser(username);
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Project not found with id: " + id));

        // WIKI4AI-99: a foreign private project is 404 (existence not revealed);
        // public projects keep the existing MANAGE check (403 without it).
        checkProjectVisible(project, user);

        permissionService.checkPermission(username, project.getId(), Permission.MANAGE);

        // Partial update: null fields are left unchanged (WIKI4AI-99 makes a
        // visibility-only payload valid — it must not wipe name/description).
        if (dto.getName() != null) {
            project.setName(dto.getName());
        }
        if (dto.getDescription() != null) {
            project.setDescription(dto.getDescription());
        }
        applyVisibilityChange(project, dto.getVisibility());

        // Optional hierarchy move (WIKI4AI-30): only when the payload explicitly
        // contains a "parentId" key. Explicit null = back to root; absent = no move.
        if (dto.isParentIdPresent()) {
            return doMove(project, dto.getParentId(), user);
        }

        Project saved = projectRepository.save(project);
        return convertToDTO(saved, user);
    }

    /**
     * Move a project to a new parent in the hierarchy (WIKI4AI-30).
     * Requires MANAGE permission on the moved project.
     *
     * @param slug        slug of the project to move
     * @param newParentId id of the new parent, or null to move back to root
     */
    @Transactional
    public ProjectDTO moveProjectBySlug(String slug, Long newParentId, String username) {
        User user = resolveUser(username);
        Project project = projectRepository.findBySlug(slug)
                .orElseThrow(() -> new EntityNotFoundException("Project not found with slug: " + slug));

        checkProjectVisible(project, user);
        permissionService.checkPermission(username, project.getId(), Permission.MANAGE);

        return doMove(project, newParentId, user);
    }

    /**
     * Move a project to a new parent in the hierarchy (WIKI4AI-30).
     * Requires MANAGE permission on the moved project.
     *
     * @param id          id of the project to move
     * @param newParentId id of the new parent, or null to move back to root
     */
    @Transactional
    public ProjectDTO moveProjectById(Long id, Long newParentId, String username) {
        User user = resolveUser(username);
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Project not found with id: " + id));

        checkProjectVisible(project, user);
        permissionService.checkPermission(username, project.getId(), Permission.MANAGE);

        return doMove(project, newParentId, user);
    }

    /**
     * Core move logic shared by slug/id variants. Validates:
     * <ul>
     *   <li>new parent exists (404 when missing)</li>
     *   <li>no self-move and no cycle (target is not a descendant of the moved project)</li>
     *   <li>resulting depth of the moved project AND all its descendants ≤ 5</li>
     * </ul>
     */
    private ProjectDTO doMove(Project project, Long newParentId, User user) {
        // Self-move is a client error even if the id would resolve to the same entity.
        if (newParentId != null && newParentId.equals(project.getId())) {
            throw new BadRequestException("Cannot move a project under itself");
        }

        Project newParent = null;
        if (newParentId != null) {
            newParent = projectRepository.findById(newParentId)
                    .orElseThrow(() -> new EntityNotFoundException(
                            "Parent project not found with id: " + newParentId));

            // Anti-cycle: the new parent must not be a descendant of the moved project.
            Project cursor = newParent.getParent();
            while (cursor != null) {
                if (cursor.getId() != null && cursor.getId().equals(project.getId())) {
                    throw new BadRequestException(
                            "Cannot move a project under its own subproject (would create a cycle)");
                }
                cursor = cursor.getParent();
            }
        }

        int currentDepth = project.getDepth();
        int newDepth = (newParent == null) ? 1 : newParent.getDepth() + 1;
        if (newDepth > Project.MAX_HIERARCHY_DEPTH) {
            throw new BadRequestException(
                    "Cannot move project: maximum hierarchy depth of "
                            + Project.MAX_HIERARCHY_DEPTH + " levels would be exceeded");
        }

        // If the project moves deeper, every descendant must still fit within the limit.
        int delta = newDepth - currentDepth;
        if (delta > 0) {
            int maxDescendantLevels = maxDescendantLevels(project);
            if (newDepth + maxDescendantLevels > Project.MAX_HIERARCHY_DEPTH) {
                throw new BadRequestException(
                        "Cannot move project: its subprojects would exceed the maximum hierarchy depth of "
                                + Project.MAX_HIERARCHY_DEPTH + " levels");
            }
        }

        // Perform the move, keeping both sides of the relationship consistent.
        Project oldParent = project.getParent();
        if (oldParent != null) {
            oldParent.removeChild(project);
        } else {
            project.setParent(null);
        }
        if (newParent != null) {
            newParent.addChild(project);
        }

        Project saved = projectRepository.save(project);
        return convertToDTO(saved, user);
    }

    /**
     * Number of hierarchy levels below the given project (its children count as 1).
     * Returns 0 when the project has no descendants. BFS over repository queries, so
     * lazy collections are never initialized.
     */
    private int maxDescendantLevels(Project root) {
        Deque<Long> queue = new ArrayDeque<>();
        queue.add(root.getId());
        int levels = 0;
        while (!queue.isEmpty()) {
            int size = queue.size();
            for (int i = 0; i < size; i++) {
                Long id = queue.poll();
                for (Project child : projectRepository.findByParentId(id)) {
                    queue.add(child.getId());
                }
            }
            levels++;
        }
        return levels - 1; // the root itself was processed once
    }

    /**
     * Collect ids of all descendants of the given project (BFS), not including the project itself.
     */
    private List<Long> collectDescendantIds(Project root) {
        List<Long> ids = new ArrayList<>();
        Deque<Long> queue = new ArrayDeque<>();
        queue.add(root.getId());
        while (!queue.isEmpty()) {
            Long id = queue.poll();
            for (Project child : projectRepository.findByParentId(id)) {
                ids.add(child.getId());
                queue.add(child.getId());
            }
        }
        return ids;
    }

    /**
     * Delete a project by ID.
     * Requires MANAGE permission on the project.
     */
    @Transactional
    public void deleteProject(Long id, String username) {
        // For backward compatibility with tests (username=null), use existsById check first
        if (username == null || username.isBlank() || "anonymous".equals(username)) {
            if (!projectRepository.existsById(id)) {
                throw new EntityNotFoundException("Project not found with id: " + id);
            }
            // Delete permissions of the project and all its subprojects first to avoid FK violations
            deletePermissionsForProjectAndDescendants(id);
            projectRepository.deleteById(id);
            return;
        }
        // For authenticated users, find project first to check permissions
        User user = resolveUser(username);
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Project not found with id: " + id));

        // WIKI4AI-99: a foreign private project is 404 (existence not revealed);
        // public projects keep the existing MANAGE check (403 without it).
        checkProjectVisible(project, user);
        permissionService.checkPermission(username, project.getId(), Permission.MANAGE);

        // Delete all project permissions (project + subprojects) to avoid FK constraint violations
        deletePermissionsForProjectAndDescendants(project.getId());
        projectRepository.delete(project);
    }

    /**
     * Delete permissions for the given project and ALL of its descendants (WIKI4AI-29).
     * Subproject rows are removed by JPA cascade REMOVE / DB ON DELETE CASCADE; their
     * permission rows must be cleaned explicitly because project_permissions has no cascade.
     */
    private void deletePermissionsForProjectAndDescendants(Long projectId) {
        Project project = projectRepository.findById(projectId).orElse(null);
        if (project == null) {
            projectPermissionRepository.deleteByProjectIdOnly(projectId);
            return;
        }
        List<Long> allIds = new ArrayList<>();
        allIds.add(projectId);
        allIds.addAll(collectDescendantIds(project));
        projectPermissionRepository.deleteByProjectIdIn(allIds);
    }

    /**
     * Delete a project by slug.
     * Requires MANAGE permission on the project.
     */
    @Transactional
    public void deleteProjectBySlug(String slug, String username) {
        // For backward compatibility with tests (username=null), use exists check first
        if (username == null || username.isBlank() || "anonymous".equals(username)) {
            Project project = projectRepository.findBySlug(slug)
                    .orElseThrow(() -> new EntityNotFoundException("Project not found with slug: " + slug));
            // Delete permissions of the project and all its subprojects first to avoid FK violations
            deletePermissionsForProjectAndDescendants(project.getId());
            projectRepository.delete(project);
            return;
        }
        // For authenticated users, find project first to check permissions
        User user = resolveUser(username);
        Project project = projectRepository.findBySlug(slug)
                .orElseThrow(() -> new EntityNotFoundException("Project not found with slug: " + slug));

        // WIKI4AI-99: a foreign private project is 404 (existence not revealed);
        // public projects keep the existing MANAGE check (403 without it).
        checkProjectVisible(project, user);
        permissionService.checkPermission(username, project.getId(), Permission.MANAGE);

        // Delete all project permissions (project + subprojects) to avoid FK constraint violations
        deletePermissionsForProjectAndDescendants(project.getId());
        projectRepository.delete(project);
    }

    /**
     * Build the nested subproject tree rooted at the given project (WIKI4AI-30).
     * Public read — no authentication required (consistent with other GET endpoints).
     * Each node carries id, name, slug, parentSlug, depth, hasChildren and documentCount.
     */
    public ProjectTreeNodeDTO getProjectTree(String slug, String username) {
        User user = resolveUser(username);
        Project root = projectRepository.findBySlug(slug)
                .orElseThrow(() -> new EntityNotFoundException("Project not found with slug: " + slug));

        // WIKI4AI-99: a foreign private root is 404 (existence not revealed).
        checkProjectVisible(root, user);

        // Load the whole hierarchy once and group children by parent id (avoids N+1 DFS queries).
        List<Project> all = projectRepository.findAll();
        java.util.Map<Long, Project> byId = new java.util.HashMap<>();
        java.util.Map<Long, List<Project>> childrenByParent = new java.util.HashMap<>();
        for (Project p : all) {
            if (p.getId() == null) {
                continue;
            }
            byId.put(p.getId(), p);
            Long parentId = (p.getParent() != null) ? p.getParent().getId() : null;
            if (parentId != null) {
                childrenByParent.computeIfAbsent(parentId, k -> new ArrayList<>()).add(p);
            }
        }

        // WIKI4AI-197 commit 16: the tree used to ask for one document count per node. The
        // hierarchy is already loaded in one query above, so the counts are batched the
        // same way — one grouped query for the whole tree.
        return buildTreeNode(root, null, 1, byId, childrenByParent, user, documentCounts(all));
    }

    /**
     * Recursively build a tree node. Depth is capped at {@link Project#MAX_HIERARCHY_DEPTH}
     * (the service layer guarantees the invariant; the cap is a defensive guard).
     *
     * @param counts project id → document count, computed once for the whole tree
     *               (WIKI4AI-197 commit 16)
     */
    private ProjectTreeNodeDTO buildTreeNode(
            Project project,
            String parentSlug,
            int depth,
            java.util.Map<Long, Project> byId,
            java.util.Map<Long, List<Project>> childrenByParent,
            User user,
            java.util.Map<Long, Long> counts) {

        // WIKI4AI-99: private subprojects are invisible to non-owner/non-ADMIN
        // callers — they simply do not appear in the tree (existence not revealed).
        List<Project> childProjects = (project.getId() != null)
                ? childrenByParent.getOrDefault(project.getId(), List.of())
                        .stream()
                        .filter(c -> !Project.VISIBILITY_PRIVATE.equals(c.getVisibility()) || canSeePrivate(c, user))
                        .collect(Collectors.toList())
                : List.of();

        List<ProjectTreeNodeDTO> childNodes = new ArrayList<>();
        if (depth < Project.MAX_HIERARCHY_DEPTH) {
            for (Project child : childProjects) {
                childNodes.add(buildTreeNode(child, project.getSlug(), depth + 1, byId, childrenByParent, user, counts));
            }
        }

        return ProjectTreeNodeDTO.builder()
                .id(project.getId())
                .name(project.getName())
                .slug(project.getSlug())
                .parentSlug(parentSlug)
                .depth(depth)
                .hasChildren(!childProjects.isEmpty())
                .documentCount(project.getId() != null
                        ? counts.getOrDefault(project.getId(), 0L).intValue()
                        : 0)
                .children(childNodes)
                .build();
    }

    /**
     * Export all documents of a project as a ZIP archive.
     * Requires READ permission on the project.
     */
    public byte[] exportProjectAsZip(String slug, String username) {
        User user = resolveUser(username);
        Project project = projectRepository.findBySlug(slug)
                .orElseThrow(() -> new EntityNotFoundException("Project not found with slug: " + slug));

        // WIKI4AI-99: a foreign private project is 404 (existence not revealed).
        checkProjectVisible(project, user);

        permissionService.checkPermission(username, project.getId(), Permission.READ);

        List<Document> documents = documentRepository.findByProjectId(project.getId());

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (Document doc : documents) {
                String entryName = doc.getSlug() + ".md";
                zos.putNextEntry(new ZipEntry(entryName));
                String content = doc.getContent() != null ? doc.getContent() : "";
                zos.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zos.closeEntry();
            }
            zos.finish();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to create ZIP archive for project: " + slug, e);
        }
    }

    /**
     * Generate a URL-friendly slug from a project name.
     */
    private String generateSlug(String name) {
        return name.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
    }

    /**
     * Apply a visibility change to the project (WIKI4AI-99). Null/blank means
     * "no change" (backward compatible — updates that never send the field keep
     * working). Non-null values are validated (400 on anything but public/private)
     * and normalized to lowercase. The owner is never touched here.
     */
    private void applyVisibilityChange(Project project, String visibility) {
        if (visibility == null || visibility.isBlank()) {
            return;
        }
        validateVisibility(visibility);
        project.setVisibility(normalizeVisibility(visibility));
    }

    // ── Pin to top + manual order (WIKI4AI-184, per-caller since WIKI4AI-200) ─
    //
    // Both writes go through JPQL UPDATEs rather than load-and-save, so pinning or
    // dragging a project does not move updatedAt — which is one of the sort keys, so a
    // save would silently reshuffle the very list the user was rearranging. That holds for
    // the global column (ProjectRepository) and for the caller's row
    // (UserProjectOrderRepository) alike.
    //
    // Whose state each write touches (WIKI4AI-200): an authenticated caller writes their
    // own user_project_order row, so the pin and the drag are private to them. An
    // anonymous caller has no personal view and writes the global column, which stays the
    // curated default every viewer without a row inherits.
    //
    // Permission rule is UNCHANGED by this story and is the one every other project write
    // uses: the project must be visible to the caller (a foreign private project is 404,
    // not 403) and the caller must hold MANAGE. That is exactly what updateProjectBySlug
    // does. Pinning is now a personal preference, so MANAGE is arguably stricter than the
    // feature needs — but relaxing it is a product decision, not a side effect of this
    // story, and the two would be different bugs to untangle later.

    /**
     * Resolve a project for a write operation: 404 when it does not exist or is
     * someone else's private project, 403 when the caller lacks MANAGE.
     */
    private Project requireManageableProject(String slug, String username) {
        User user = resolveUser(username);
        Project project = projectRepository.findBySlug(slug)
                .orElseThrow(() -> new EntityNotFoundException("Project not found with slug: " + slug));
        checkProjectVisible(project, user);
        permissionService.checkPermission(username, project.getId(), Permission.MANAGE);
        return project;
    }

    /**
     * Resolve a project for a write that changes only <b>the caller's own</b> order
     * (WIKI4AI-200 follow-up).
     *
     * <p>This is {@link #requireManageableProject} with MANAGE replaced by READ, and the
     * manager decided it that way on purpose. Since WIKI4AI-200 a pin or a drag writes a
     * row of {@code user_project_order} belonging to the caller — it is a personal
     * preference, not an edit of the project. Requiring MANAGE would mean that a user who
     * can only read a project <b>could not push aside a project the administrator had
     * pinned globally</b>, which is the very defect this story exists to remove, only
     * inverted. The blast radius is one's own dashboard: the write cannot touch another
     * viewer's row, and a private project is still 404 for anyone who cannot see it.</p>
     *
     * <p>READ is the right level rather than a new one because {@code PermissionService}
     * already defines READ as "any authenticated user may read this wiki", so the rule
     * reduces to exactly "you can see it, you can arrange your own view of it".</p>
     *
     * <p>The <b>global</b> writers keep MANAGE (see {@code pinProjectGlobally}): they
     * renumber the curated default for every viewer at once, which is an administrator's
     * act, not a preference. Same reasoning as {@code /admin/projects-order}.</p>
     *
     * @param slug     the project slug
     * @param username the authenticated caller
     * @return the project, if the caller may see it
     * @throws EntityNotFoundException when the project does not exist, or is someone
     *                                 else's private project and its existence is hidden
     */
    private Project requireVisibleReadableProject(String slug, String username) {
        User user = resolveUser(username);
        Project project = projectRepository.findBySlug(slug)
                .orElseThrow(() -> new EntityNotFoundException("Project not found with slug: " + slug));
        checkProjectVisible(project, user);
        permissionService.checkPermission(username, project.getId(), Permission.READ);
        return project;
    }

    /**
     * Re-read a project after a JPQL UPDATE cleared the persistence context. The
     * entity loaded before the update is detached and still carries the old
     * pinned/sortOrder, so returning it would report the pre-update state.
     */
    private Project reloadProject(Long id) {
        return projectRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Project not found with id: " + id));
    }

    /**
     * Pin a project to the top of the CALLER's lists (WIKI4AI-184; per-caller since
     * WIKI4AI-200).
     *
     * <p>On the first pin the project also gets the manual position above everything the
     * caller can see, so it lands at the top of a {@code sort=manual} list and not merely
     * "somewhere among the pinned". Re-pinning an already pinned project keeps its
     * position: pinning twice must not teleport it.</p>
     *
     * <p><b>Whose pin this is (WIKI4AI-200).</b> An authenticated caller writes their own
     * {@code user_project_order} row, so the pin moves the project in their dashboard and
     * in nobody else's. Before this story the same call wrote {@code projects.pinned}, and
     * because every project creator holds MANAGE, one user's pin moved that project to the
     * top of everybody's dashboard.</p>
     *
     * <p>An anonymous caller has no personal view to write, so they still write the global
     * column — the curated default. That is not a loophole: the endpoint is authenticated
     * in a deployed instance, and the anonymous path is the one the test suite and any
     * future administrative "set the default" flow use.</p>
     *
     * @param slug     the project slug
     * @param username the authenticated caller
     * @return the updated project, as the caller now sees it
     */
    @Transactional
    public ProjectDTO pinProject(String slug, String username) {
        User user = resolveUser(username);
        if (user == null) {
            return pinProjectGlobally(slug, username);
        }
        // WIKI4AI-197 commit 11, moved to the caller's row by WIKI4AI-200: the lock is
        // taken BEFORE anything is read, because the values the compare-and-set guards are
        // the caller's rows. Reading them first and locking second renumbers a list
        // somebody else has already changed — except now "somebody else" can only ever be
        // this same caller on another tab, which is the race the story must still survive.
        lockViewerOrder(user.getId());
        // WIKI4AI-200 follow-up: READ, not MANAGE. This writes the caller's own row.
        Project project = requireVisibleReadableProject(slug, username);
        UserProjectOrder row = findOrCreateViewerOrder(user, project);
        int order = row.isPinned() ? row.getSortOrder() : topSlotForViewer(user);
        userProjectOrderRepository.updatePinning(row.getId(), true, order, LocalDateTime.now());
        return convertToDTO(reloadProject(project.getId()), user);
    }

    /**
     * Unpin a project (WIKI4AI-184; per-caller since WIKI4AI-200). The manual position is
     * deliberately kept: unpinning puts the project back where it stood before it was
     * pinned instead of sending it to the end of the manual order.
     *
     * <p>A caller with no row yet gets one, materialised from the project's global values.
     * Without that, unpinning a project that is pinned GLOBALLY would be a no-op for this
     * caller — there would be nothing to set to false, and the global pin would keep
     * floating the project to their top.</p>
     *
     * @param slug     the project slug
     * @param username the authenticated caller
     * @return the updated project, as the caller now sees it
     */
    @Transactional
    public ProjectDTO unpinProject(String slug, String username) {
        User user = resolveUser(username);
        if (user == null) {
            return unpinProjectGlobally(slug, username);
        }
        lockViewerOrder(user.getId());
        // WIKI4AI-200 follow-up: READ, not MANAGE — see pinProject.
        Project project = requireVisibleReadableProject(slug, username);
        UserProjectOrder row = findOrCreateViewerOrder(user, project);
        userProjectOrderRepository.updatePinning(
                row.getId(), false, row.getSortOrder(), LocalDateTime.now());
        return convertToDTO(reloadProject(project.getId()), user);
    }

    /**
     * Move a project inside the CALLER's manual order (WIKI4AI-184; per-caller since
     * WIKI4AI-200).
     *
     * <p>The list the move happens against is the caller's own: every project they can see,
     * ordered by the values the list endpoint would report for them. That is the list the
     * user is dragging inside, and it is the only list this call may renumber — renumbering
     * the global column is what made one user's drag rewrite other people's order.</p>
     *
     * <p>After the move the list is renumbered densely 0..n-1 for this caller, so their
     * stored state is a permutation and never a pile of duplicates. A project whose
     * position already matches is skipped, which keeps the number of writes proportional to
     * the distance travelled. A project with no row yet gets one, seeded from the project's
     * global values, so the projects the caller never touched keep their pin state while
     * gaining a position of the caller's own.</p>
     *
     * <p>The write is still a compare-and-set on the position the caller read
     * (WIKI4AI-197 commit 11) — not a return to load-and-save. Two drags by the same
     * account that read the same list must not silently renumber from the same snapshot,
     * and the caller's row lock is what makes one of them wait rather than lose.</p>
     *
     * @param slug     the project to move
     * @param request  exactly one of beforeSlug / afterSlug / position
     * @param username the authenticated caller
     * @return the moved project, with its new position for this caller
     * @throws BadRequestException      when the body is not exactly one selector
     * @throws EntityNotFoundException  when the anchor slug does not exist
     */
    @Transactional
    public ProjectDTO reorderProject(String slug, ReorderRequestDTO request, String username) {
        User user = resolveUser(username);
        if (user == null) {
            return reorderProjectGlobally(slug, request, username);
        }
        lockViewerOrder(user.getId());
        // WIKI4AI-200 follow-up: READ, not MANAGE — see pinProject. A drag renumbers only
        // this caller's rows, so it is not an edit of the projects it moves.
        Project project = requireVisibleReadableProject(slug, username);

        // Read the caller's rows AFTER the lock, and order the projects by the same
        // effective values the list endpoint serves. Sorting by the project's own columns
        // here would renumber a list the caller never saw.
        Map<Long, UserProjectOrder> rows = viewerOrderRows(user);
        List<Project> ordered = projectRepository.findAll()
                .stream()
                .filter(p -> !Project.VISIBILITY_PRIVATE.equals(p.getVisibility())
                        || canSeePrivate(p, user))
                .sorted(Comparator.comparing((Project p) -> effectiveOrder(p, rows),
                        ListSort.sortKeyComparator(ListSort.MANUAL)))
                .collect(Collectors.toList());

        int target = projectTargetIndex(ordered, project, request);
        ordered.remove(project);
        ordered.add(target, project);

        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < ordered.size(); i++) {
            Project moved = ordered.get(i);
            UserProjectOrder existing = rows.get(moved.getId());
            int current = existing != null ? existing.getSortOrder() : moved.getSortOrder();
            if (current == i) {
                continue;
            }
            if (existing != null) {
                int updated = userProjectOrderRepository.updateSortOrderIfCurrent(
                        existing.getId(), i, current, now);
                if (updated == 0) {
                    throw new OptimisticLockException(
                            "Project ordering changed concurrently; retry the reorder");
                }
            } else {
                userProjectOrderRepository.saveAndFlush(UserProjectOrder.builder()
                        .userId(user.getId())
                        .projectId(moved.getId())
                        // Materialise the project's own pin, so a project pinned globally
                        // stays pinned for this caller after they drag something else.
                        .pinned(moved.isPinned())
                        .sortOrder(i)
                        .build());
            }
        }
        return convertToDTO(reloadProject(project.getId()), user);
    }

    // ── The global path, kept for a caller with no personal view ──────────────
    //
    // WIKI4AI-200 did not delete these three; it demoted them. They are what an anonymous
    // caller writes, and they are the curated default the administrator arranges and every
    // viewer without a row inherits. They keep WIKI4AI-197 commit 11 exactly as it was:
    // the whole projects table is the mutex, because these writes renumber every project
    // for every viewer.

    /** Global pin — see the block comment above. */
    private ProjectDTO pinProjectGlobally(String slug, String username) {
        // WIKI4AI-197 commit 11: the lock is taken BEFORE anything is read. A Project that
        // is already in the persistence context keeps the values it was loaded with — the
        // locking query returns the managed instance rather than refreshing it — so a
        // project read before the lock can carry a sortOrder that was never the current
        // one, and the compare-and-set below would then fail on its own stale read.
        lockProjectList();
        Project project = requireManageableProject(slug, username);
        int order = project.isPinned()
                ? project.getSortOrder()
                : projectRepository.minSortOrder() - 1;
        projectRepository.updatePinning(project.getId(), true, order);
        return convertToDTO(reloadProject(project.getId()), null);
    }

    /** Global unpin — see the block comment on {@link #pinProjectGlobally}. */
    private ProjectDTO unpinProjectGlobally(String slug, String username) {
        // WIKI4AI-197 commit 11: lock first, then read (see pinProjectGlobally for why).
        lockProjectList();
        Project project = requireManageableProject(slug, username);
        projectRepository.updatePinning(project.getId(), false, project.getSortOrder());
        return convertToDTO(reloadProject(project.getId()), null);
    }

    /** Global reorder — see the block comment on {@link #pinProjectGlobally}. */
    private ProjectDTO reorderProjectGlobally(String slug, ReorderRequestDTO request,
                                             String username) {
        // WIKI4AI-197 commit 11: one query takes the lock AND reads the list, and it runs
        // before the moved project is resolved. Reading first and locking second means
        // renumbering a list somebody else has already changed — and the managed instance
        // of a row loaded before the lock keeps its pre-lock values. The unit is the whole
        // table because sort_order is one global column: reordering one project can
        // renumber every project.
        List<Project> ordered = lockProjectList().stream()
                .sorted(ListSort.manualProjectOrder())
                .collect(Collectors.toList());
        Project project = requireManageableProject(slug, username);

        int target = projectTargetIndex(ordered, project, request);
        ordered.remove(project);
        ordered.add(target, project);

        for (int i = 0; i < ordered.size(); i++) {
            Project row = ordered.get(i);
            if (row.getSortOrder() != i) {
                // Compare-and-set, not load-and-save with @Version: Project has no
                // @Version precisely so that a sidebar drag cannot 409 an editor's save
                // (WIKI4AI-72, WIKI4AI-184).
                int moved = projectRepository.updateSortOrderIfCurrent(
                        row.getId(), i, row.getSortOrder());
                if (moved == 0) {
                    throw new OptimisticLockException(
                            "Project ordering changed concurrently; retry the reorder");
                }
            }
        }
        return convertToDTO(reloadProject(project.getId()), null);
    }

    // ── Per-viewer ordering helpers (WIKI4AI-200) ─────────────────────────────

    /**
     * Take the mutex that serialises every writer of THIS caller's project order
     * (WIKI4AI-200).
     *
     * <p>The unit is the caller's {@code users} row, not their {@code user_project_order}
     * rows. A caller who has customised nothing has no rows, and {@code FOR UPDATE} over an
     * empty result set locks nothing — two first-time pins would then both read
     * {@code min(sort_order)} from nothing, take the same slot, and the second insert would
     * die on {@code uk_user_project_order}. The user row always exists.</p>
     *
     * <p>It is also what makes the story's complaint go away at the database level: two
     * different viewers take two different rows and never wait for each other, where the
     * global {@link #lockProjectList()} made every pin in the instance queue behind every
     * other.</p>
     *
     * <p>Must be called inside a transaction, and before the caller's rows are read.</p>
     */
    private void lockViewerOrder(Long userId) {
        userRepository.findUserByIdForUpdate(userId);
    }

    /**
     * The caller's ordering row for this project, created from the project's global values
     * when the caller has none yet (WIKI4AI-200).
     *
     * <p>Seeding from the global values is what makes the first write of a viewer a pure
     * override rather than a reset: unpinning a globally pinned project must leave the
     * project where it stood, and dragging one project must not silently unpin the other
     * 82.</p>
     */
    private UserProjectOrder findOrCreateViewerOrder(User user, Project project) {
        return userProjectOrderRepository
                .findByUserIdAndProjectId(user.getId(), project.getId())
                .orElseGet(() -> userProjectOrderRepository.saveAndFlush(UserProjectOrder.builder()
                        .userId(user.getId())
                        .projectId(project.getId())
                        .pinned(project.isPinned())
                        .sortOrder(project.getSortOrder())
                        .build()));
    }

    /**
     * The slot above everything this caller can see (WIKI4AI-200).
     *
     * <p>The minimum of the caller's own rows AND of the global column, minus one. The
     * global half is not optional: a caller with three rows and eighty projects they have
     * never customised would otherwise get a "top" slot that still sits below seventy-seven
     * global positions, and the pin would look like it did nothing on a {@code sort=manual}
     * list.</p>
     */
    private int topSlotForViewer(User user) {
        return Math.min(userProjectOrderRepository.minSortOrderForViewer(user.getId()),
                projectRepository.minSortOrder()) - 1;
    }


    /**
     * Take the mutex that serialises every writer of the project manual order
     * (WIKI4AI-197 commit 11). Unlike documents, projects share one global list, so the
     * unit is the whole table. Rows are locked in a fixed id order, which is what keeps
     * two concurrent reorders from deadlocking; with the row count of a wiki (tens, not
     * thousands) that is cheaper than any finer-grained scheme.
     *
     * <p>Must be called inside a transaction, and before the list or the min is read.</p>
     *
     * @return every project, each row locked for the rest of the transaction
     */
    private List<Project> lockProjectList() {
        return projectRepository.findAllForUpdate();
    }

    /**
     * Resolve the requested slot to an index in the list <b>without</b> the moved
     * project — that is the list the insertion happens against, so an anchor slug
     * needs no off-by-one correction and {@code position} means "index in the list
     * you would see after the item is lifted out of it".
     */
    private int projectTargetIndex(List<Project> ordered, Project moved, ReorderRequestDTO request) {
        if (request == null) {
            throw new BadRequestException(
                    "Reorder requires exactly one of: beforeSlug, afterSlug, position");
        }
        int selectors = (request.getBeforeSlug() == null ? 0 : 1)
                + (request.getAfterSlug() == null ? 0 : 1)
                + (request.getPosition() == null ? 0 : 1);
        if (selectors != 1) {
            throw new BadRequestException(
                    "Reorder requires exactly one of: beforeSlug, afterSlug, position (got "
                            + selectors + ")");
        }

        // "Move X before X" is a no-op, not a 404: the anchor is the item itself and it
        // is about to be removed from the list below. Returning its current index in the
        // full list is exactly the insertion index that reproduces the original position
        // in the list without it, so nothing moves and nothing is renumbered.
        if (moved.getSlug().equals(request.getBeforeSlug())
                || moved.getSlug().equals(request.getAfterSlug())) {
            return indexOfProjectSlug(ordered, moved.getSlug());
        }

        List<Project> without = new ArrayList<>(ordered);
        without.remove(moved);

        if (request.getPosition() != null) {
            // Clamped, not rejected: a drag past the end of a list is a drop at the end.
            return Math.max(0, Math.min(request.getPosition(), without.size()));
        }
        if (request.getBeforeSlug() != null) {
            return indexOfProjectSlug(without, request.getBeforeSlug());
        }
        return indexOfProjectSlug(without, request.getAfterSlug()) + 1;
    }

    /** Index of a slug in a project list; 404 when it is not there. */
    private int indexOfProjectSlug(List<Project> list, String slug) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).getSlug().equals(slug)) {
                return i;
            }
        }
        throw new EntityNotFoundException("Project not found with slug: " + slug);
    }

    /**
     * Convert Project entity to DTO (includes parentSlug and depth, WIKI4AI-29;
     * ownerId and visibility, WIKI4AI-99).
     */
    /**
     * Convert one project to its DTO, counting its documents with a dedicated query.
     *
     * <p>The count used to be {@code project.getDocuments().size()}. That collection is a
     * lazy {@code @OneToMany}, so the first read of it issued a select that loaded every
     * document of the project — content and embeddings included — and only the size was
     * used. A COUNT query returns the same number without materialising a single row.
     * For a list of projects use {@link #convertToDTO(Project, long, UserProjectOrder)} with counts from
     * {@link #documentCounts(java.util.Collection)}, so the whole list costs one query
     * instead of one per project.</p>
     */
    private ProjectDTO convertToDTO(Project project, User user) {
        return convertToDTO(project, countDocumentsFor(project), findViewerOrder(user, project));
    }

    /**
     * Convert one project to its DTO with a document count the caller already has
     * (WIKI4AI-197 commit 16) and with this caller's ordering row (WIKI4AI-200).
     *
     * <p>{@code viewerRow} is null in the two cases where there is nothing to overlay: an
     * anonymous caller, and a project this viewer has never pinned or dragged. Both fall
     * back to the project's own {@code pinned}/{@code sortOrder}, which is the curated
     * global default — not a degraded or second-class view.</p>
     *
     * <p>The DTO field is called {@code pinned} and its meaning changed; its shape did
     * not. That is deliberate: the frontend, the MCP server and every existing client read
     * the same two fields, and the only thing that had to change is whose value they
     * carry.</p>
     */
    private ProjectDTO convertToDTO(Project project, long documentCount, UserProjectOrder viewerRow) {
        String parentSlug = null;
        if (project.getParent() != null) {
            // Lazy proxy: getSlug() initializes it within the open session.
            parentSlug = project.getParent().getSlug();
        }
        return ProjectDTO.builder()
                .id(project.getId())
                .name(project.getName())
                .description(project.getDescription())
                .slug(project.getSlug())
                .parentSlug(parentSlug)
                .depth(project.getDepth())
                .documentCount((int) documentCount)
                .ownerId(project.getOwner() != null ? project.getOwner().getId() : null)
                .visibility(project.getVisibility() != null ? project.getVisibility() : Project.VISIBILITY_PUBLIC)
                .pinned(viewerRow != null ? viewerRow.isPinned() : project.isPinned())
                .sortOrder(viewerRow != null ? viewerRow.getSortOrder() : project.getSortOrder())
                .createdAt(project.getCreatedAt())
                .updatedAt(project.getUpdatedAt())
                .build();
    }

    /** Document count of one project, or 0 for a project that has no id yet. */
    private long countDocumentsFor(Project project) {
        return project.getId() == null ? 0L : documentRepository.countByProjectId(project.getId());
    }

    /**
     * Document counts for a whole list of projects in ONE query (WIKI4AI-197 commit 16).
     *
     * <p>Projects with no documents are absent from the result, so callers read the map
     * with a zero default rather than assuming a key exists.</p>
     *
     * @param projects the projects to count; null entries and unsaved rows are ignored
     * @return project id → document count, empty when there is nothing to count
     */
    private java.util.Map<Long, Long> documentCounts(java.util.Collection<Project> projects) {
        List<Long> ids = projects.stream()
                .filter(java.util.Objects::nonNull)
                .map(Project::getId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (ids.isEmpty()) {
            // IN () is not valid SQL; a project list with no persisted rows needs no query.
            return java.util.Map.of();
        }
        return documentRepository.countByProjectIdIn(ids).stream()
                .filter(row -> row.getProjectId() != null)
                .collect(Collectors.toMap(
                        DocumentRepository.ProjectDocumentCount::getProjectId,
                        row -> row.getDocumentCount() == null ? 0L : row.getDocumentCount(),
                        (a, b) -> a));
    }

    // ==================== BACKWARD COMPATIBILITY OVERLOADS (no username param) ====================
    // These delegate to the permission-aware methods with null username, which skips permission checks.
    // Used by existing unit tests that don't set up security context.

    @Transactional
    public ProjectDTO createProject(ProjectCreateDTO dto) {
        return createProject(dto, null);
    }

    /**
     * Legacy overload: create project from ProjectDTO (for backward compatibility with tests).
     */
    @Transactional
    public ProjectDTO createProject(ProjectDTO dto) {
        ProjectCreateDTO createDto = new ProjectCreateDTO();
        java.lang.reflect.Field nameField;
        try {
            nameField = ProjectCreateDTO.class.getDeclaredField("name");
            nameField.setAccessible(true);
            nameField.set(createDto, dto.getName());
            java.lang.reflect.Field descField = ProjectCreateDTO.class.getDeclaredField("description");
            descField.setAccessible(true);
            descField.set(createDto, dto.getDescription());
        } catch (Exception e) {
            // Fallback: use builder pattern via DTO conversion
            return createProject(
                com.wiki4ai.dto.ProjectCreateDTO.builder()
                    .name(dto.getName())
                    .description(dto.getDescription())
                    .build(), null);
        }
        return createProject(createDto, null);
    }

    @Transactional
    public ProjectDTO updateProject(Long id, ProjectDTO dto) {
        return updateProject(id, dto, null);
    }

    @Transactional
    public ProjectDTO updateProjectBySlug(String slug, ProjectUpdateDTO dto) {
        return updateProjectBySlug(slug, dto, null);
    }

    @Transactional
    public ProjectDTO updateProjectById(Long id, ProjectUpdateDTO dto) {
        return updateProjectById(id, dto, null);
    }

    @Transactional
    public void deleteProject(Long id) {
        deleteProject(id, null);
    }

    @Transactional
    public void deleteProjectBySlug(String slug) {
        deleteProjectBySlug(slug, null);
    }

    @Transactional
    public ProjectDTO moveProjectBySlug(String slug, Long newParentId) {
        return moveProjectBySlug(slug, newParentId, null);
    }

    @Transactional
    public ProjectDTO moveProjectById(Long id, Long newParentId) {
        return moveProjectById(id, newParentId, null);
    }

    public byte[] exportProjectAsZip(String slug) {
        return exportProjectAsZip(slug, null);
    }

    // ── Backward-compatible overloads (no username = anonymous caller,
    // sees only public projects — WIKI4AI-99) ────────────────────────────────

    public List<ProjectDTO> getAllProjects() {
        return getAllProjects(null);
    }

    public ProjectDTO getProjectById(Long id) {
        return getProjectById(id, null);
    }

    public ProjectDTO getProjectBySlug(String slug) {
        return getProjectBySlug(slug, null);
    }

    public ProjectTreeNodeDTO getProjectTree(String slug) {
        return getProjectTree(slug, null);
    }
}
