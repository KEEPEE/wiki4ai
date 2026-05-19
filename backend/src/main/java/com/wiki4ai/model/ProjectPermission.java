package com.wiki4ai.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * JPA Entity representing a user's permissions within a specific project.
 * Manages which permissions a user has on a given project.
 */
@Entity
@Table(name = "project_permissions", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"project_id", "user_id"})
})
@Getter
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class ProjectPermission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ElementCollection
    @CollectionTable(
        name = "project_permission_values",
        joinColumns = @JoinColumn(name = "permission_id")
    )
    @Column(name = "permission")
    @Builder.Default
    private List<Permission> permissions = new ArrayList<>();

    /**
     * Add a permission to this project permission.
     */
    public void addPermission(Permission permission) {
        permissions.add(permission);
    }

    /**
     * Remove a permission from this project permission.
     */
    public void removePermission(Permission permission) {
        permissions.remove(permission);
    }

    /**
     * Set the project reference. Used by bidirectional relationship maintenance.
     */
    public void setProject(Project project) {
        this.project = project;
    }

    /**
     * Set the user reference.
     */
    public void setUser(User user) {
        this.user = user;
    }

    /**
     * Set the project permission id. Used mainly for testing and entity comparison.
     */
    public void setId(Long id) {
        this.id = id;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ProjectPermission that = (ProjectPermission) o;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return "ProjectPermission{" +
                "id=" + id +
                ", projectId=" + (project != null ? project.getId() : null) +
                ", userId=" + (user != null ? user.getId() : null) +
                ", permissions=" + permissions +
                '}';
    }
}
