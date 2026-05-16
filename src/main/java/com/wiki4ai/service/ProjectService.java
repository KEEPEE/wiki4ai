package com.wiki4ai.service;

import com.wiki4ai.dto.ProjectDTO;
import com.wiki4ai.model.Project;
import com.wiki4ai.repository.ProjectRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Service layer for Project business logic.
 * Handles CRUD operations, validation, and project management.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProjectService {

    private final ProjectRepository projectRepository;

    /**
     * Get all projects ordered by creation date (newest first).
     */
    public List<ProjectDTO> getAllProjects() {
        return projectRepository.findAllOrderByCreatedAtDesc()
                .stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    /**
     * Get a project by ID.
     *
     * @param id the project ID
     * @return ProjectDTO
     * @throws EntityNotFoundException if project not found
     */
    public ProjectDTO getProjectById(Long id) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Project not found with id: " + id));
        return convertToDTO(project);
    }

    /**
     * Get a project by slug.
     *
     * @param slug the project slug
     * @return ProjectDTO
     * @throws EntityNotFoundException if project not found
     */
    public ProjectDTO getProjectBySlug(String slug) {
        Project project = projectRepository.findBySlug(slug)
                .orElseThrow(() -> new EntityNotFoundException("Project not found with slug: " + slug));
        return convertToDTO(project);
    }

    /**
     * Create a new project.
     *
     * @param dto the project data transfer object
     * @return created ProjectDTO
     */
    @Transactional
    public ProjectDTO createProject(ProjectDTO dto) {
        // Validate uniqueness of name
        if (projectRepository.existsByName(dto.getName())) {
            throw new IllegalArgumentException("A project with this name already exists");
        }

        Project project = Project.builder()
                .name(dto.getName())
                .description(dto.getDescription())
                .slug(generateSlug(dto.getName()))
                .build();

        Project saved = projectRepository.save(project);
        return convertToDTO(saved);
    }

    /**
     * Update an existing project.
     *
     * @param id  the project ID
     * @param dto the updated project data
     * @return updated ProjectDTO
     * @throws EntityNotFoundException if project not found
     */
    @Transactional
    public ProjectDTO updateProject(Long id, ProjectDTO dto) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Project not found with id: " + id));

        project.setName(dto.getName());
        project.setDescription(dto.getDescription());

        Project saved = projectRepository.save(project);
        return convertToDTO(saved);
    }

    /**
     * Delete a project by ID.
     *
     * @param id the project ID
     * @throws EntityNotFoundException if project not found
     */
    @Transactional
    public void deleteProject(Long id) {
        if (!projectRepository.existsById(id)) {
            throw new EntityNotFoundException("Project not found with id: " + id);
        }
        projectRepository.deleteById(id);
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
     * Convert Project entity to DTO.
     */
    private ProjectDTO convertToDTO(Project project) {
        return ProjectDTO.builder()
                .id(project.getId())
                .name(project.getName())
                .description(project.getDescription())
                .slug(project.getSlug())
                .createdAt(project.getCreatedAt())
                .updatedAt(project.getUpdatedAt())
                .build();
    }
}
