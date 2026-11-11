package com.wiki4ai.service;

import com.wiki4ai.dto.ProjectOrderEntryDTO;
import com.wiki4ai.dto.ProjectOrderUpdateRequestDTO;
import com.wiki4ai.exception.BadRequestException;
import com.wiki4ai.model.Project;
import com.wiki4ai.repository.ProjectRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The GLOBAL project order an administrator curates (WIKI4AI-200).
 *
 * <h2>Why this still exists after per-user ordering</h2>
 * WIKI4AI-200 moved pinning and dragging to the individual user, and deliberately kept
 * {@code projects.pinned} / {@code projects.sort_order} as the state a user inherits until
 * they customise something. Inheriting is only meaningful if somebody can set it. Without
 * this endpoint the global columns would be write-only legacy: no way to arrange the order
 * a freshly registered account sees, and no way to explain to a new user why the projects
 * come in the order they do.
 *
 * <h2>What this endpoint is NOT</h2>
 * It reads and writes the global columns and nothing else. It never touches
 * {@code user_project_order}, so an administrator arranging the default does not overwrite
 * what individual users have already chosen for themselves — the same asymmetry the story
 * exists to establish, applied in the other direction.
 *
 * <h2>Why the write takes the whole-table lock</h2>
 * It is a writer of the global manual order, so it takes the same mutex every other writer
 * of that order takes (WIKI4AI-197 commit 11). A per-viewer lock would be wrong here: this
 * write is not per-viewer, it is the one write that is allowed to affect everybody.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProjectOrderAdminService {

    private final AdminService adminService;
    private final ProjectRepository projectRepository;

    /**
     * The global order, in the order a user who has customised nothing would see it.
     *
     * @throws SecurityException when the caller is not an administrator
     */
    public List<ProjectOrderEntryDTO> getGlobalOrder() {
        adminService.verifyAdminRole();
        return readGlobalOrder();
    }

    /**
     * Write the global default order (WIKI4AI-200).
     *
     * <p>Partial per entry: a null {@code pinned} or {@code sortOrder} leaves that value
     * alone, and a project absent from the payload keeps both. The payload is applied in
     * the order it arrives, under the whole-table ordering lock.</p>
     *
     * @param request the entries to write
     * @return the resulting global order, so the caller sees what it did in one round trip
     * @throws SecurityException       when the caller is not an administrator
     * @throws BadRequestException     when the payload is empty
     * @throws EntityNotFoundException when a slug does not resolve to a project
     */
    @Transactional
    public List<ProjectOrderEntryDTO> setGlobalOrder(ProjectOrderUpdateRequestDTO request) {
        adminService.verifyAdminRole();

        if (request == null || request.items() == null || request.items().isEmpty()) {
            throw new BadRequestException(
                    "projects-order requires a non-empty 'items' list of {slug, pinned, sortOrder}");
        }

        // The same mutex every writer of the global manual order takes. Rows are locked in
        // a fixed id order, which is what keeps this from deadlocking against a reorder.
        projectRepository.findAllForUpdate();

        for (ProjectOrderUpdateRequestDTO.Entry entry : request.items()) {
            if (entry.slug() == null || entry.slug().isBlank()) {
                throw new BadRequestException("Every projects-order entry needs a 'slug'");
            }
            Project project = projectRepository.findBySlug(entry.slug())
                    .orElseThrow(() -> new EntityNotFoundException(
                            "Project not found with slug: " + entry.slug()));

            boolean pinned = entry.pinned() != null ? entry.pinned() : project.isPinned();
            int sortOrder = entry.sortOrder() != null ? entry.sortOrder() : project.getSortOrder();

            // updatePinning is the only writer of these two columns (WIKI4AI-197: they are
            // updatable=false on the entity, so an ordinary save cannot reach them).
            projectRepository.updatePinning(project.getId(), pinned, sortOrder);
        }

        return readGlobalOrder();
    }

    /**
     * Read the global order. Split out so {@code setGlobalOrder} can return it without
     * asking {@code verifyAdminRole} a second time in the same request.
     */
    private List<ProjectOrderEntryDTO> readGlobalOrder() {
        return projectRepository.findAll()
                .stream()
                .sorted(ListSort.manualProjectOrder())
                .map(p -> new ProjectOrderEntryDTO(
                        p.getId(), p.getSlug(), p.getName(), p.isPinned(), p.getSortOrder()))
                .collect(Collectors.toList());
    }
}
