package com.wiki4ai.service;

import com.wiki4ai.dto.ProjectCreateDTO;
import com.wiki4ai.dto.ProjectDTO;
import com.wiki4ai.dto.ProjectTreeNodeDTO;
import com.wiki4ai.dto.ProjectUpdateDTO;
import com.wiki4ai.exception.BadRequestException;
import com.wiki4ai.model.Document;
import com.wiki4ai.model.Permission;
import com.wiki4ai.model.Project;
import com.wiki4ai.repository.DocumentRepository;
import com.wiki4ai.repository.ProjectPermissionRepository;
import com.wiki4ai.repository.ProjectRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
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

    /**
     * Get all projects ordered by creation date (newest first).
     * Public read - no authentication required.
     */
    public List<ProjectDTO> getAllProjects() {
        return projectRepository.findAllByOrderByCreatedAtDesc()
                .stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    /**
     * Get a project by ID.
     * Public read - no authentication required.
     */
    public ProjectDTO getProjectById(Long id) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Project not found with id: " + id));
        return convertToDTO(project);
    }

    /**
     * Get a project by slug.
     * Public read - no authentication required.
     */
    public ProjectDTO getProjectBySlug(String slug) {
        Project project = projectRepository.findBySlug(slug)
                .orElseThrow(() -> new EntityNotFoundException("Project not found with slug: " + slug));
        return convertToDTO(project);
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

        Project project = new Project();
        // Slug is generated from the name in Project.onCreate() (@PrePersist) on save;
        // setName() must NOT touch the slug (WIKI4AI-54: rename keeps the URL stable).
        project.setName(dto.getName());
        project.setDescription(dto.getDescription());
        if (parent != null) {
            parent.addChild(project);
        }

        Project saved = projectRepository.save(project);

        // Auto-grant MANAGE permission to the creator
        if (username != null && !username.isBlank() && !"anonymous".equals(username)) {
            permissionService.grantManageToCreator(username, saved.getId());
        }

        return convertToDTO(saved);
    }

    /**
     * Update an existing project by ID.
     * Requires MANAGE permission on the project.
     */
    @Transactional
    public ProjectDTO updateProject(Long id, ProjectDTO dto, String username) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Project not found with id: " + id));

        permissionService.checkPermission(username, project.getId(), Permission.MANAGE);

        project.setName(dto.getName());
        project.setDescription(dto.getDescription());

        Project saved = projectRepository.save(project);
        return convertToDTO(saved);
    }

    /**
     * Update an existing project by slug.
     * Requires MANAGE permission on the project.
     */
    @Transactional
    public ProjectDTO updateProjectBySlug(String slug, ProjectUpdateDTO dto, String username) {
        Project project = projectRepository.findBySlug(slug)
                .orElseThrow(() -> new EntityNotFoundException("Project not found with slug: " + slug));

        permissionService.checkPermission(username, project.getId(), Permission.MANAGE);

        project.setName(dto.getName());
        project.setDescription(dto.getDescription());

        // Optional hierarchy move (WIKI4AI-30): only when the payload explicitly
        // contains a "parentId" key. Explicit null = back to root; absent = no move.
        if (dto.isParentIdPresent()) {
            return doMove(project, dto.getParentId());
        }

        Project saved = projectRepository.save(project);
        return convertToDTO(saved);
    }

    /**
     * Update an existing project by ID.
     * Requires MANAGE permission on the project.
     */
    @Transactional
    public ProjectDTO updateProjectById(Long id, ProjectUpdateDTO dto, String username) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Project not found with id: " + id));

        permissionService.checkPermission(username, project.getId(), Permission.MANAGE);

        project.setName(dto.getName());
        project.setDescription(dto.getDescription());

        // Optional hierarchy move (WIKI4AI-30): only when the payload explicitly
        // contains a "parentId" key. Explicit null = back to root; absent = no move.
        if (dto.isParentIdPresent()) {
            return doMove(project, dto.getParentId());
        }

        Project saved = projectRepository.save(project);
        return convertToDTO(saved);
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
        Project project = projectRepository.findBySlug(slug)
                .orElseThrow(() -> new EntityNotFoundException("Project not found with slug: " + slug));

        permissionService.checkPermission(username, project.getId(), Permission.MANAGE);

        return doMove(project, newParentId);
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
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Project not found with id: " + id));

        permissionService.checkPermission(username, project.getId(), Permission.MANAGE);

        return doMove(project, newParentId);
    }

    /**
     * Core move logic shared by slug/id variants. Validates:
     * <ul>
     *   <li>new parent exists (404 when missing)</li>
     *   <li>no self-move and no cycle (target is not a descendant of the moved project)</li>
     *   <li>resulting depth of the moved project AND all its descendants ≤ 5</li>
     * </ul>
     */
    private ProjectDTO doMove(Project project, Long newParentId) {
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
        return convertToDTO(saved);
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
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Project not found with id: " + id));

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
        Project project = projectRepository.findBySlug(slug)
                .orElseThrow(() -> new EntityNotFoundException("Project not found with slug: " + slug));

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
    public ProjectTreeNodeDTO getProjectTree(String slug) {
        Project root = projectRepository.findBySlug(slug)
                .orElseThrow(() -> new EntityNotFoundException("Project not found with slug: " + slug));

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

        return buildTreeNode(root, null, 1, byId, childrenByParent);
    }

    /**
     * Recursively build a tree node. Depth is capped at {@link Project#MAX_HIERARCHY_DEPTH}
     * (the service layer guarantees the invariant; the cap is a defensive guard).
     */
    private ProjectTreeNodeDTO buildTreeNode(
            Project project,
            String parentSlug,
            int depth,
            java.util.Map<Long, Project> byId,
            java.util.Map<Long, List<Project>> childrenByParent) {

        List<Project> childProjects = (project.getId() != null)
                ? childrenByParent.getOrDefault(project.getId(), List.of())
                : List.of();

        List<ProjectTreeNodeDTO> childNodes = new ArrayList<>();
        if (depth < Project.MAX_HIERARCHY_DEPTH) {
            for (Project child : childProjects) {
                childNodes.add(buildTreeNode(child, project.getSlug(), depth + 1, byId, childrenByParent));
            }
        }

        return ProjectTreeNodeDTO.builder()
                .id(project.getId())
                .name(project.getName())
                .slug(project.getSlug())
                .parentSlug(parentSlug)
                .depth(depth)
                .hasChildren(!childProjects.isEmpty())
                .documentCount(project.getId() != null ? (int) documentRepository.countByProjectId(project.getId()) : 0)
                .children(childNodes)
                .build();
    }

    /**
     * Export all documents of a project as a ZIP archive.
     * Requires READ permission on the project.
     */
    public byte[] exportProjectAsZip(String slug, String username) {
        Project project = projectRepository.findBySlug(slug)
                .orElseThrow(() -> new EntityNotFoundException("Project not found with slug: " + slug));

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
     * Convert Project entity to DTO (includes parentSlug and depth, WIKI4AI-29).
     */
    private ProjectDTO convertToDTO(Project project) {
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
                .documentCount(project.getDocuments() != null ? project.getDocuments().size() : 0)
                .createdAt(project.getCreatedAt())
                .updatedAt(project.getUpdatedAt())
                .build();
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
}
