package com.wiki4ai.repository;

import com.wiki4ai.model.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for Project entity.
 * Provides CRUD operations and custom queries for project data access.
 */
@Repository
public interface ProjectRepository extends JpaRepository<Project, Long> {

    /**
     * Find a project by its slug.
     *
     * @param slug the project slug
     * @return Optional containing the project if found
     */
    Optional<Project> findBySlug(String slug);

    /**
     * Check if a project with the given name already exists.
     *
     * @param name the project name
     * @return true if a project with this name exists
     */
    boolean existsByName(String name);

    /**
     * Check if a project with the given slug already exists.
     *
     * @param slug the project slug
     * @return true if a project with this slug exists
     */
    boolean existsBySlug(String slug);

    /**
     * Find all projects ordered by creation date (newest first).
     *
     * @return list of projects sorted by createdAt descending
     */
    List<Project> findAllByOrderByCreatedAtDesc();
}
