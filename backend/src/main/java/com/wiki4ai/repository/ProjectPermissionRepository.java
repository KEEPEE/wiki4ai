package com.wiki4ai.repository;

import com.wiki4ai.model.ProjectPermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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
}
