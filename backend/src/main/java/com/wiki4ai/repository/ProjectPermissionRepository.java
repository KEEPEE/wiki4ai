package com.wiki4ai.repository;

import com.wiki4ai.model.ProjectPermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for ProjectPermission entity operations.
 */
@Repository
public interface ProjectPermissionRepository extends JpaRepository<ProjectPermission, Long> {

    /**
     * Find a project permission by project ID and user ID.
     */
    Optional<ProjectPermission> findByProjectIdAndUserId(Long projectId, Long userId);

    /**
     * Find all permissions for a given project.
     *
     * @param projectId the project ID
     * @return list of all ProjectPermission records for this project
     */
    List<ProjectPermission> findByProjectId(Long projectId);

    /**
     * Delete all permissions for a given user on a specific project.
     *
     * @param projectId the project ID
     * @param userId the user ID
     */
    void deleteByProjectIdAndUserId(Long projectId, Long userId);

    /**
     * Delete ALL permissions for a given project (used when deleting a project).
     * This avoids foreign key constraint violations.
     *
     * @param projectId the project ID to delete all permissions for
     */
    @Modifying
    @Query("DELETE FROM ProjectPermission pp WHERE pp.project.id = :projectId")
    void deleteByProjectIdOnly(Long projectId);
}
