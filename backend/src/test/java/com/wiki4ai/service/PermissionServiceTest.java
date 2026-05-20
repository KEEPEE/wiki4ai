package com.wiki4ai.service;

import com.wiki4ai.dto.UserPermissionDTO;
import com.wiki4ai.model.Permission;
import com.wiki4ai.model.ProjectPermission;
import com.wiki4ai.model.User;
import com.wiki4ai.repository.ProjectPermissionRepository;
import com.wiki4ai.repository.ProjectRepository;
import com.wiki4ai.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for PermissionService.
 * Tests permission hierarchy enforcement, grant/revoke validation rules,
 * and core permission checking logic with mocked repositories.
 */
class PermissionServiceTest {

    private ProjectPermissionRepository projectPermissionRepo;
    private ProjectRepository projectRepository;
    private UserRepository userRepository;
    private PermissionService permissionService;

    // Test fixtures
    private User userAlice;
    private User userBob;
    private Long projectId;

    @BeforeEach
    void setUp() {
        projectPermissionRepo = mock(ProjectPermissionRepository.class);
        projectRepository = mock(ProjectRepository.class);
        userRepository = mock(UserRepository.class);
        permissionService = new PermissionService(projectPermissionRepo, projectRepository, userRepository);

        // Create test users
        userAlice = User.builder().id(1L).username("alice").email("alice@test.com").password("$2a$10.hashed").build();
        userBob = User.builder().id(2L).username("bob").email("bob@test.com").password("$2a$10.hashed").build();
        projectId = 100L;

        // Mock user lookups
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(userAlice));
        when(userRepository.findByUsername("bob")).thenReturn(Optional.of(userBob));
        when(userRepository.findById(1L)).thenReturn(Optional.of(userAlice));
        when(userRepository.findById(2L)).thenReturn(Optional.of(userBob));
    }

    @Nested
    @DisplayName("Permission Checking (hasPermission)")
    class PermissionCheckingTests {

        @Test
        @DisplayName("should return true when user has the exact permission")
        void shouldReturnTrueForExactPermission() {
            ProjectPermission pp = ProjectPermission.builder()
                    .id(1L).permissions(List.of(Permission.READ, Permission.CREATE)).build();
            when(projectPermissionRepo.findByProjectIdAndUserId(projectId, 1L))
                    .thenReturn(Optional.of(pp));

            boolean result = permissionService.hasPermission("alice", projectId, Permission.READ);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("should return true when user has MANAGE (implies all permissions)")
        void shouldReturnTrueWhenUserHasManage() {
            ProjectPermission pp = ProjectPermission.builder()
                    .id(1L).permissions(List.of(Permission.MANAGE)).build();
            when(projectPermissionRepo.findByProjectIdAndUserId(projectId, 1L))
                    .thenReturn(Optional.of(pp));

            assertThat(permissionService.hasPermission("alice", projectId, Permission.READ)).isTrue();
            assertThat(permissionService.hasPermission("alice", projectId, Permission.CREATE)).isTrue();
            assertThat(permissionService.hasPermission("alice", projectId, Permission.UPDATE)).isTrue();
            assertThat(permissionService.hasPermission("alice", projectId, Permission.DELETE)).isTrue();
        }

        @Test
        @DisplayName("should return false when user has no permissions")
        void shouldReturnFalseWhenNoPermissions() {
            when(projectPermissionRepo.findByProjectIdAndUserId(projectId, 1L))
                    .thenReturn(Optional.empty());

            boolean result = permissionService.hasPermission("alice", projectId, Permission.READ);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("should return false when user has different permission")
        void shouldReturnFalseForDifferentPermission() {
            ProjectPermission pp = ProjectPermission.builder()
                    .id(1L).permissions(List.of(Permission.READ)).build();
            when(projectPermissionRepo.findByProjectIdAndUserId(projectId, 1L))
                    .thenReturn(Optional.of(pp));

            boolean result = permissionService.hasPermission("alice", projectId, Permission.DELETE);

            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("checkPermission - throws AccessDeniedException")
    class CheckPermissionTests {

        @Test
        @DisplayName("should skip check for null username")
        void shouldSkipCheckForNullUsername() {
            // Should not throw any exception
            permissionService.checkPermission(null, projectId, Permission.READ);
            verify(projectPermissionRepo, never()).findByProjectIdAndUserId(any(), any());
        }

        @Test
        @DisplayName("should skip check for blank username")
        void shouldSkipCheckForBlankUsername() {
            permissionService.checkPermission("", projectId, Permission.READ);
            permissionService.checkPermission("   ", projectId, Permission.READ);
            verify(projectPermissionRepo, never()).findByProjectIdAndUserId(any(), any());
        }

        @Test
        @DisplayName("should skip check for anonymous username")
        void shouldSkipCheckForAnonymousUsername() {
            permissionService.checkPermission("anonymous", projectId, Permission.READ);
            verify(projectPermissionRepo, never()).findByProjectIdAndUserId(any(), any());
        }

        @Test
        @DisplayName("should throw AccessDeniedException when user lacks permission")
        void shouldThrowWhenUserLacksPermission() {
            when(projectPermissionRepo.findByProjectIdAndUserId(projectId, 1L))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    permissionService.checkPermission("alice", projectId, Permission.READ))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("lacks READ");
        }
    }

    @Nested
    @DisplayName("validateGrantPermission - hierarchy enforcement")
    class ValidateGrantPermissionTests {

        @Test
        @DisplayName("should allow MANAGE user to grant any permission")
        void shouldAllowManageToGrantAnyPermission() {
            ProjectPermission alicePerm = ProjectPermission.builder()
                    .id(1L).permissions(List.of(Permission.MANAGE)).build();
            when(projectPermissionRepo.findByProjectIdAndUserId(projectId, 1L))
                    .thenReturn(Optional.of(alicePerm));

            // Should not throw - MANAGE can grant anything
            permissionService.validateGrantPermission("alice", projectId, "bob", List.of(Permission.READ, Permission.CREATE));
        }

        @Test
        @DisplayName("should reject non-MANAGE user trying to grant permissions")
        void shouldRejectNonManageUser() {
            ProjectPermission alicePerm = ProjectPermission.builder()
                    .id(1L).permissions(List.of(Permission.READ)).build();
            when(projectPermissionRepo.findByProjectIdAndUserId(projectId, 1L))
                    .thenReturn(Optional.of(alicePerm));

            assertThatThrownBy(() ->
                    permissionService.validateGrantPermission("alice", projectId, "bob", List.of(Permission.CREATE)))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("lacks MANAGE");
        }

        @Test
        @DisplayName("should reject granting permissions the requester doesn't have")
        void shouldRejectGrantingPermissionsRequesterDoesntHave() {
            // Alice has READ and CREATE but not MANAGE - this test is for the specific rule
            // that a non-MANAGE user can only grant what they have
            ProjectPermission alicePerm = ProjectPermission.builder()
                    .id(1L).permissions(List.of(Permission.READ, Permission.CREATE)).build();
            when(projectPermissionRepo.findByProjectIdAndUserId(projectId, 1L))
                    .thenReturn(Optional.of(alicePerm));

            // First check: MANAGE is required to grant at all
            assertThatThrownBy(() ->
                    permissionService.validateGrantPermission("alice", projectId, "bob", List.of(Permission.READ)))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("lacks MANAGE");
        }

        @Test
        @DisplayName("should reject granting to non-existent user")
        void shouldRejectNonExistentTargetUser() {
            ProjectPermission alicePerm = ProjectPermission.builder()
                    .id(1L).permissions(List.of(Permission.MANAGE)).build();
            when(projectPermissionRepo.findByProjectIdAndUserId(projectId, 1L))
                    .thenReturn(Optional.of(alicePerm));
            when(userRepository.findByUsername("nobody")).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    permissionService.validateGrantPermission("alice", projectId, "nobody", List.of(Permission.READ)))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("not found");
        }
    }

    @Nested
    @DisplayName("validateRevokePermission - self-lockout prevention")
    class ValidateRevokePermissionTests {

        @Test
        @DisplayName("should reject revoking your own permissions")
        void shouldRejectRevokingOwnPermissions() {
            ProjectPermission alicePerm = ProjectPermission.builder()
                    .id(1L).permissions(List.of(Permission.MANAGE)).build();
            when(projectPermissionRepo.findByProjectIdAndUserId(projectId, 1L))
                    .thenReturn(Optional.of(alicePerm));

            assertThatThrownBy(() ->
                    permissionService.validateRevokePermission("alice", projectId, "alice"))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("Cannot revoke your own");
        }

        @Test
        @DisplayName("should reject revoking from the only MANAGE user")
        void shouldRejectRevokingOnlyManageUser() {
            ProjectPermission alicePerm = ProjectPermission.builder()
                    .id(1L).permissions(List.of(Permission.MANAGE)).build();
            ProjectPermission bobPerm = ProjectPermission.builder()
                    .id(2L).permissions(List.of(Permission.MANAGE)).build();

            when(projectPermissionRepo.findByProjectIdAndUserId(projectId, 1L))
                    .thenReturn(Optional.of(alicePerm));
            when(projectPermissionRepo.findByProjectIdAndUserId(projectId, 2L))
                    .thenReturn(Optional.of(bobPerm));
            when(projectPermissionRepo.findByProjectId(projectId))
                    .thenReturn(List.of(bobPerm)); // Only bob has MANAGE

            assertThatThrownBy(() ->
                    permissionService.validateRevokePermission("alice", projectId, "bob"))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("only user with MANAGE");
        }

        @Test
        @DisplayName("should allow revoking when multiple MANAGE users exist")
        void shouldAllowRevokingWhenMultipleManageUsersExist() {
            ProjectPermission alicePerm = ProjectPermission.builder()
                    .id(1L).permissions(List.of(Permission.MANAGE)).build();
            ProjectPermission bobPerm = ProjectPermission.builder()
                    .id(2L).permissions(List.of(Permission.MANAGE)).build();

            when(projectPermissionRepo.findByProjectIdAndUserId(projectId, 1L))
                    .thenReturn(Optional.of(alicePerm));
            when(projectPermissionRepo.findByProjectIdAndUserId(projectId, 2L))
                    .thenReturn(Optional.of(bobPerm));
            when(projectPermissionRepo.findByProjectId(projectId))
                    .thenReturn(List.of(alicePerm, bobPerm)); // Both have MANAGE

            // Should not throw - alice can revoke bob because alice still has MANAGE
            permissionService.validateRevokePermission("alice", projectId, "bob");
        }

        @Test
        @DisplayName("should reject non-MANAGE user trying to revoke")
        void shouldRejectNonManageRevoker() {
            ProjectPermission alicePerm = ProjectPermission.builder()
                    .id(1L).permissions(List.of(Permission.READ)).build();
            when(projectPermissionRepo.findByProjectIdAndUserId(projectId, 1L))
                    .thenReturn(Optional.of(alicePerm));

            assertThatThrownBy(() ->
                    permissionService.validateRevokePermission("alice", projectId, "bob"))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("lacks MANAGE");
        }
    }

    @Nested
    @DisplayName("getAllProjectPermissions")
    class GetAllProjectPermissionsTests {

        @Test
        @DisplayName("should return empty list when no permissions exist")
        void shouldReturnEmptyWhenNoPermissions() {
            when(projectPermissionRepo.findByProjectId(projectId)).thenReturn(List.of());

            List<UserPermissionDTO> result = permissionService.getAllProjectPermissions(projectId);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should return all users with their permissions")
        void shouldReturnAllUsersWithPermissions() {
            ProjectPermission pp1 = ProjectPermission.builder().id(1L)
                    .user(userAlice).permissions(List.of(Permission.MANAGE)).build();
            ProjectPermission pp2 = ProjectPermission.builder().id(2L)
                    .user(userBob).permissions(List.of(Permission.READ, Permission.CREATE)).build();

            when(projectPermissionRepo.findByProjectId(projectId)).thenReturn(List.of(pp1, pp2));

            List<UserPermissionDTO> result = permissionService.getAllProjectPermissions(projectId);

            assertThat(result).hasSize(2);
            assertThat(result)
                    .extracting(UserPermissionDTO::getUsername)
                    .containsExactlyInAnyOrder("alice", "bob");
        }
    }
}
