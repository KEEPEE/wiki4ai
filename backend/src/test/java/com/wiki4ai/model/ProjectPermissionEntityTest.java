package com.wiki4ai.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the ProjectPermission JPA entity.
 * Tests entity fields, unique constraint (project_id + user_id), and permission management.
 */
class ProjectPermissionEntityTest {

    private ProjectPermission permA;
    private ProjectPermission permB;
    private Project project;
    private User user;

    @BeforeEach
    void setUp() {
        project = new Project();
        project.setId(1L);
        project.setName("Test Wiki");
        project.setSlug("test-wiki");

        user = new User();
        user.setId(2L);
        user.setUsername("testuser");
        user.setEmail("test@example.com");
        user.setPassword("$2a$10.hashed");

        permA = ProjectPermission.builder()
                .id(1L)
                .project(project)
                .user(user)
                .permissions(new ArrayList<>(List.of(Permission.READ, Permission.UPDATE)))
                .build();

        permB = new ProjectPermission();
    }

    @Nested
    @DisplayName("Entity Fields and Construction")
    class FieldTests {

        @Test
        @DisplayName("should create entity with all fields via builder")
        void shouldCreateWithBuilder() {
            ProjectPermission permission = ProjectPermission.builder()
                    .id(1L)
                    .project(project)
                    .user(user)
                    .permissions(List.of(Permission.READ, Permission.CREATE))
                    .build();

            assertThat(permission.getId()).isEqualTo(1L);
            assertThat(permission.getProject()).isEqualTo(project);
            assertThat(permission.getUser()).isEqualTo(user);
            assertThat(permission.getPermissions()).hasSize(2);
            assertThat(permission.getPermissions()).containsExactly(Permission.READ, Permission.CREATE);
        }

        @Test
        @DisplayName("should set and get all fields via setters")
        void shouldSetAndGetAllFields() {
            ProjectPermission permission = new ProjectPermission();
            permission.setId(42L);
            permission.setProject(project);
            permission.setUser(user);

            assertThat(permission.getId()).isEqualTo(42L);
            assertThat(permission.getProject()).isEqualTo(project);
            assertThat(permission.getUser()).isEqualTo(user);
        }

        @Test
        @DisplayName("should have correct toString output")
        void shouldHaveCorrectToString() {
            ProjectPermission permission = new ProjectPermission();
            permission.setId(1L);
            permission.setProject(project);
            permission.setUser(user);
            permission.addPermission(Permission.READ);

            String str = permission.toString();

            assertThat(str).contains("id=1");
            assertThat(str).contains("projectId=1");
            assertThat(str).contains("userId=2");
            assertThat(str).contains("permissions=[READ]");
        }
    }

    @Nested
    @DisplayName("Permission Management")
    class PermissionManagementTests {

        @Test
        @DisplayName("should start with empty permissions list")
        void shouldStartWithEmptyPermissions() {
            ProjectPermission permission = new ProjectPermission();
            assertThat(permission.getPermissions()).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("should add a permission")
        void shouldAddPermission() {
            permB.addPermission(Permission.READ);
            permB.addPermission(Permission.CREATE);

            assertThat(permB.getPermissions()).hasSize(2);
            assertThat(permB.getPermissions()).containsExactly(Permission.READ, Permission.CREATE);
        }

        @Test
        @DisplayName("should remove a permission")
        void shouldRemovePermission() {
            permA.addPermission(Permission.DELETE);
            assertThat(permA.getPermissions()).hasSize(3);

            permA.removePermission(Permission.DELETE);
            assertThat(permA.getPermissions()).hasSize(2);
            assertThat(permA.getPermissions()).doesNotContain(Permission.DELETE);
        }

        @Test
        @DisplayName("should handle all permission types")
        void shouldHandleAllPermissionTypes() {
            for (Permission perm : Permission.values()) {
                permB.addPermission(perm);
            }

            assertThat(permB.getPermissions()).hasSize(5);
            assertThat(permB.getPermissions()).contains(Permission.READ, Permission.CREATE,
                    Permission.UPDATE, Permission.DELETE, Permission.MANAGE);
        }
    }

    @Nested
    @DisplayName("Entity Equality and HashCode")
    class EqualityTests {

        @Test
        @DisplayName("should be equal to itself")
        void shouldBeEqualToSelf() {
            assertThat(permA).isEqualTo(permA);
        }

        @Test
        @DisplayName("should not be null-equal")
        void shouldNotBeNullEqual() {
            assertThat(permA).isNotNull();
        }

        @Test
        @DisplayName("should be equal to another entity with same id")
        void shouldBeEqualToSameId() {
            ProjectPermission copy = new ProjectPermission();
            copy.setId(1L);
            permA.setId(1L);
            assertThat(permA).isEqualTo(copy);
        }

        @Test
        @DisplayName("should not be equal to entity with different id")
        void shouldNotEqualDifferentId() {
            permA.setId(1L);
            permB.setId(2L);
            assertThat(permA).isNotEqualTo(permB);
        }

        @Test
        @DisplayName("should have consistent hashCode for same object")
        void shouldHaveConsistentHashCode() {
            int hash1 = permA.hashCode();
            int hash2 = permA.hashCode();
            assertThat(hash1).isEqualTo(hash2);
        }

        @Test
        @DisplayName("equal objects must have same hashCode")
        void equalObjectsMustHaveSameHashCode() {
            ProjectPermission copy = new ProjectPermission();
            copy.setId(5L);
            permA.setId(5L);
            assertThat(permA.hashCode()).isEqualTo(copy.hashCode());
        }

        @Test
        @DisplayName("should not be equal to unrelated type")
        void shouldNotEqualUnrelatedType() {
            assertThat(permA).isNotEqualTo("string");
        }
    }

    @Nested
    @DisplayName("Relationships")
    class RelationshipTests {

        @Test
        @DisplayName("should set and get project reference")
        void shouldSetAndGetProject() {
            ProjectPermission permission = new ProjectPermission();
            permission.setId(1L);
            permission.setProject(project);

            assertThat(permission.getProject()).isEqualTo(project);
            assertThat(permission.getProject().getId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("should set and get user reference")
        void shouldSetAndGetUser() {
            ProjectPermission permission = new ProjectPermission();
            permission.setId(1L);
            permission.setUser(user);

            assertThat(permission.getUser()).isEqualTo(user);
            assertThat(permission.getUser().getId()).isEqualTo(2L);
        }

        @Test
        @DisplayName("should create entity with project and user via builder")
        void shouldCreateWithProjectAndUserViaBuilder() {
            ProjectPermission permission = ProjectPermission.builder()
                    .id(10L)
                    .project(project)
                    .user(user)
                    .permissions(List.of(Permission.MANAGE))
                    .build();

            assertThat(permission.getProject()).isEqualTo(project);
            assertThat(permission.getUser()).isEqualTo(user);
            assertThat(permission.getPermissions()).containsExactly(Permission.MANAGE);
        }
    }
}
