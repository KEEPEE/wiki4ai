package com.wiki4ai.service;

import com.wiki4ai.dto.ActorCreateDTO;
import com.wiki4ai.dto.ActorDTO;
import com.wiki4ai.dto.ProjectActorAssignDTO;
import com.wiki4ai.dto.ProjectActorDTO;
import com.wiki4ai.dto.ProjectActorUpdateRoleDTO;
import com.wiki4ai.exception.BadRequestException;
import com.wiki4ai.model.Actor;
import com.wiki4ai.model.Project;
import com.wiki4ai.model.ProjectActor;
import com.wiki4ai.model.Role;
import com.wiki4ai.model.User;
import com.wiki4ai.repository.ActorRepository;
import com.wiki4ai.repository.ProjectActorRepository;
import com.wiki4ai.repository.ProjectRepository;
import com.wiki4ai.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for ProjectActorService against a real database (H2,
 * WIKI4AI-132).
 * <p>
 * Uses @SpringBootTest with the test profile: Flyway is disabled and the schema
 * is created by Hibernate from the entities, so the V16 migration itself is not
 * exercised here (the pipeline verifies it on dev against Postgres).
 * <p>
 * The heart of this story is which caller may manage a project's actors, so most
 * tests here are about the three ways of holding the project's admin role
 * (project owner / ADMIN user / owning an actor that has role='admin' in the
 * project) and about the callers that hold none of them.
 */
@SpringBootTest
@ActiveProfiles("test")
class ProjectActorServiceTest {

    private static final String ADMIN = "pa_admin";
    private static final String ALICE = "pa_alice";
    private static final String BOB = "pa_bob";

    @Autowired
    private ProjectActorService projectActorService;

    @Autowired
    private ActorService actorService;

    @Autowired
    private ProjectActorRepository projectActorRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private ActorRepository actorRepository;

    @Autowired
    private UserRepository userRepository;

    private User adminUser;
    private User alice;
    private User bob;

    @BeforeEach
    void setUp() {
        // Clean up state from previous tests (the H2 "testdb" is shared across contexts).
        projectActorRepository.deleteAll();
        projectRepository.deleteAll();
        actorRepository.deleteAll();
        for (String name : List.of(ADMIN, ALICE, BOB)) {
            userRepository.findByUsername(name).ifPresent(userRepository::delete);
        }

        adminUser = saveUser(ADMIN, Role.ADMIN);
        alice = saveUser(ALICE, Role.USER);
        bob = saveUser(BOB, Role.USER);
    }

    private User saveUser(String username, Role role) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@test.local");
        user.setPassword("not-a-real-hash");
        user.setRole(role);
        return userRepository.save(user);
    }

    /** Unique project name per call — both name and slug are UNIQUE in the DB. */
    private Project createProject(String name, User owner, String visibility) {
        Project project = new Project();
        project.setName(name);
        project.setVisibility(visibility);
        project.setOwner(owner);
        return projectRepository.save(project);
    }

    private Project publicProjectOwnedByAlice() {
        return createProject("PA project " + System.nanoTime(), alice, Project.VISIBILITY_PUBLIC);
    }

    private ActorDTO createActor(String handle, String username) {
        return actorService.createActor(ActorCreateDTO.builder().handle(handle).build(), username);
    }

    // ── CRUD ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Project actor CRUD")
    class CrudTests {

        @Test
        @DisplayName("Assigns an actor with the default role 'viewer'")
        void shouldAssignWithDefaultViewerRole() {
            Project project = publicProjectOwnedByAlice();
            ActorDTO actor = createActor("pa-agent-1", ALICE);

            ProjectActorDTO assigned = projectActorService.assignActor(
                    project.getId(),
                    ProjectActorAssignDTO.builder().actorId(actor.getId()).build(),
                    ALICE);

            assertThat(assigned.getId()).isNotNull();
            assertThat(assigned.getProjectId()).isEqualTo(project.getId());
            assertThat(assigned.getActorId()).isEqualTo(actor.getId());
            assertThat(assigned.getRole()).isEqualTo(ProjectActor.ROLE_VIEWER);
            assertThat(assigned.getCreatedAt()).isNotNull();

            assertThat(projectActorRepository
                    .existsByProjectIdAndActorId(project.getId(), actor.getId())).isTrue();
        }

        @Test
        @DisplayName("Assigns an actor with an explicit role (normalized to lowercase)")
        void shouldAssignWithExplicitRole() {
            Project project = publicProjectOwnedByAlice();
            ActorDTO actor = createActor("pa-agent-2", ALICE);

            ProjectActorDTO assigned = projectActorService.assignActor(project.getId(),
                    ProjectActorAssignDTO.builder().actorId(actor.getId()).role("PLANNER").build(),
                    ALICE);

            assertThat(assigned.getRole()).isEqualTo(ProjectActor.ROLE_PLANNER);
        }

        @Test
        @DisplayName("Lists the project's actors")
        void shouldListProjectActors() {
            Project project = publicProjectOwnedByAlice();
            ActorDTO first = createActor("pa-agent-a", ALICE);
            ActorDTO second = createActor("pa-agent-b", ALICE);
            projectActorService.assignActor(project.getId(),
                    ProjectActorAssignDTO.builder().actorId(first.getId()).role("worker").build(), ALICE);
            projectActorService.assignActor(project.getId(),
                    ProjectActorAssignDTO.builder().actorId(second.getId()).build(), ALICE);

            List<ProjectActorDTO> all = projectActorService.listProjectActors(project.getId(), null, ALICE);

            assertThat(all).hasSize(2);
            assertThat(all).extracting(ProjectActorDTO::getActorId)
                    .containsExactlyInAnyOrder(first.getId(), second.getId());
            assertThat(all).extracting(ProjectActorDTO::getRole)
                    .containsExactlyInAnyOrder(ProjectActor.ROLE_VIEWER, ProjectActor.ROLE_WORKER);
        }

        @Test
        @DisplayName("Filters the list by role (case-insensitive)")
        void shouldFilterListByRole() {
            Project project = publicProjectOwnedByAlice();
            ActorDTO worker = createActor("pa-agent-worker", ALICE);
            ActorDTO viewer = createActor("pa-agent-viewer", ALICE);
            projectActorService.assignActor(project.getId(),
                    ProjectActorAssignDTO.builder().actorId(worker.getId()).role("worker").build(), ALICE);
            projectActorService.assignActor(project.getId(),
                    ProjectActorAssignDTO.builder().actorId(viewer.getId()).build(), ALICE);

            List<ProjectActorDTO> workers = projectActorService.listProjectActors(
                    project.getId(), "WORKER", ALICE);

            assertThat(workers).extracting(ProjectActorDTO::getActorId)
                    .containsExactly(worker.getId());
        }

        @Test
        @DisplayName("An unknown role filter is rejected instead of returning an empty list")
        void shouldRejectUnknownRoleFilter() {
            Project project = publicProjectOwnedByAlice();

            assertThatThrownBy(() -> projectActorService.listProjectActors(project.getId(), "bogus", ALICE))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Invalid role");
        }

        @Test
        @DisplayName("Changes the role of an assigned actor")
        void shouldUpdateRole() {
            Project project = publicProjectOwnedByAlice();
            ActorDTO actor = createActor("pa-agent-3", ALICE);
            projectActorService.assignActor(project.getId(),
                    ProjectActorAssignDTO.builder().actorId(actor.getId()).build(), ALICE);

            ProjectActorDTO updated = projectActorService.updateRole(project.getId(), actor.getId(),
                    ProjectActorUpdateRoleDTO.builder().role("admin").build(), ALICE);

            assertThat(updated.getRole()).isEqualTo(ProjectActor.ROLE_ADMIN);
            assertThat(projectActorService.getRole(project.getId(), actor.getId()))
                    .isEqualTo(ProjectActor.ROLE_ADMIN);
        }

        @Test
        @DisplayName("Removing an actor deletes only that assignment")
        void shouldRemoveAssignment() {
            Project project = publicProjectOwnedByAlice();
            ActorDTO keep = createActor("pa-agent-keep", ALICE);
            ActorDTO drop = createActor("pa-agent-drop", ALICE);
            projectActorService.assignActor(project.getId(),
                    ProjectActorAssignDTO.builder().actorId(keep.getId()).build(), ALICE);
            projectActorService.assignActor(project.getId(),
                    ProjectActorAssignDTO.builder().actorId(drop.getId()).build(), ALICE);

            projectActorService.removeActor(project.getId(), drop.getId(), ALICE);

            assertThat(projectActorRepository.count()).isEqualTo(1);
            assertThat(projectActorService.getRole(project.getId(), drop.getId())).isNull();
            assertThat(projectActorService.getRole(project.getId(), keep.getId()))
                    .isEqualTo(ProjectActor.ROLE_VIEWER);
        }

        @Test
        @DisplayName("An actor can be assigned to several projects")
        void shouldAllowActorInMultipleProjects() {
            Project first = publicProjectOwnedByAlice();
            Project second = publicProjectOwnedByAlice();
            ActorDTO actor = createActor("pa-multi-agent", ALICE);

            projectActorService.assignActor(first.getId(),
                    ProjectActorAssignDTO.builder().actorId(actor.getId()).role("worker").build(), ALICE);
            projectActorService.assignActor(second.getId(),
                    ProjectActorAssignDTO.builder().actorId(actor.getId()).role("planner").build(), ALICE);

            assertThat(projectActorRepository.findByActorId(actor.getId())).hasSize(2);
        }
    }

    // ── Conflicts and validation ──────────────────────────────────────────────

    @Nested
    @DisplayName("Conflicts and validation")
    class ValidationTests {

        @Test
        @DisplayName("A duplicate assignment is a conflict (409)")
        void shouldRejectDuplicateAssignment() {
            Project project = publicProjectOwnedByAlice();
            ActorDTO actor = createActor("pa-agent-dup", ALICE);
            projectActorService.assignActor(project.getId(),
                    ProjectActorAssignDTO.builder().actorId(actor.getId()).build(), ALICE);

            assertThatThrownBy(() -> projectActorService.assignActor(project.getId(),
                    ProjectActorAssignDTO.builder().actorId(actor.getId()).role("admin").build(), ALICE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("already assigned");

            // The original role survives the rejected second assignment.
            assertThat(projectActorRepository.count()).isEqualTo(1);
            assertThat(projectActorService.getRole(project.getId(), actor.getId()))
                    .isEqualTo(ProjectActor.ROLE_VIEWER);
        }

        @Test
        @DisplayName("An unknown role is rejected on assign and on update (400)")
        void shouldRejectUnknownRole() {
            Project project = publicProjectOwnedByAlice();
            ActorDTO actor = createActor("pa-agent-role", ALICE);

            assertThatThrownBy(() -> projectActorService.assignActor(project.getId(),
                    ProjectActorAssignDTO.builder().actorId(actor.getId()).role("owner").build(), ALICE))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Invalid role");

            projectActorService.assignActor(project.getId(),
                    ProjectActorAssignDTO.builder().actorId(actor.getId()).build(), ALICE);

            assertThatThrownBy(() -> projectActorService.updateRole(project.getId(), actor.getId(),
                    ProjectActorUpdateRoleDTO.builder().role("owner").build(), ALICE))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Invalid role");

            assertThat(projectActorService.getRole(project.getId(), actor.getId()))
                    .isEqualTo(ProjectActor.ROLE_VIEWER);
        }

        @Test
        @DisplayName("A role-change payload without a role is rejected (400)")
        void shouldRejectMissingRoleOnUpdate() {
            Project project = publicProjectOwnedByAlice();
            ActorDTO actor = createActor("pa-agent-norole", ALICE);
            projectActorService.assignActor(project.getId(),
                    ProjectActorAssignDTO.builder().actorId(actor.getId()).build(), ALICE);

            assertThatThrownBy(() -> projectActorService.updateRole(project.getId(), actor.getId(),
                    ProjectActorUpdateRoleDTO.builder().build(), ALICE))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Role is required");
        }

        @Test
        @DisplayName("An archived actor cannot be assigned (400)")
        void shouldRejectArchivedActor() {
            Project project = publicProjectOwnedByAlice();
            ActorDTO actor = createActor("pa-agent-archived", ALICE);
            actorService.updateActor(actor.getId(),
                    com.wiki4ai.dto.ActorUpdateDTO.builder().status("archived").build(), ALICE);

            assertThatThrownBy(() -> projectActorService.assignActor(project.getId(),
                    ProjectActorAssignDTO.builder().actorId(actor.getId()).build(), ALICE))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("archived");

            assertThat(projectActorRepository.count()).isZero();
        }

        @Test
        @DisplayName("An unknown project is 404 on every operation")
        void shouldRejectUnknownProject() {
            ActorDTO actor = createActor("pa-agent-404", ALICE);

            assertThatThrownBy(() -> projectActorService.listProjectActors(999_999L, null, ALICE))
                    .isInstanceOf(EntityNotFoundException.class);
            assertThatThrownBy(() -> projectActorService.assignActor(999_999L,
                    ProjectActorAssignDTO.builder().actorId(actor.getId()).build(), ALICE))
                    .isInstanceOf(EntityNotFoundException.class);
            assertThatThrownBy(() -> projectActorService.updateRole(999_999L, actor.getId(),
                    ProjectActorUpdateRoleDTO.builder().role("admin").build(), ALICE))
                    .isInstanceOf(EntityNotFoundException.class);
            assertThatThrownBy(() -> projectActorService.removeActor(999_999L, actor.getId(), ALICE))
                    .isInstanceOf(EntityNotFoundException.class);
        }

        @Test
        @DisplayName("An unknown actor is 404")
        void shouldRejectUnknownActor() {
            Project project = publicProjectOwnedByAlice();

            assertThatThrownBy(() -> projectActorService.assignActor(project.getId(),
                    ProjectActorAssignDTO.builder().actorId(999_999L).build(), ALICE))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining("999999");
        }

        @Test
        @DisplayName("Changing the role of an unassigned actor is 404")
        void shouldRejectUpdateOfUnassignedActor() {
            Project project = publicProjectOwnedByAlice();
            ActorDTO actor = createActor("pa-agent-unassigned", ALICE);

            assertThatThrownBy(() -> projectActorService.updateRole(project.getId(), actor.getId(),
                    ProjectActorUpdateRoleDTO.builder().role("worker").build(), ALICE))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining("not assigned");
        }

        @Test
        @DisplayName("Removing an actor that is not assigned is 404")
        void shouldRejectRemoveOfUnassignedActor() {
            Project project = publicProjectOwnedByAlice();
            ActorDTO actor = createActor("pa-agent-absent", ALICE);

            assertThatThrownBy(() -> projectActorService.removeActor(project.getId(), actor.getId(), ALICE))
                    .isInstanceOf(EntityNotFoundException.class);
        }

        @Test
        @DisplayName("An assignment payload without an actor id is 400")
        void shouldRejectMissingActorId() {
            Project project = publicProjectOwnedByAlice();

            assertThatThrownBy(() -> projectActorService.assignActor(project.getId(),
                    ProjectActorAssignDTO.builder().build(), ALICE))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Actor id is required");
        }
    }

    // ── Who may manage the project's actors (the core rule) ───────────────────

    @Nested
    @DisplayName("Admin role enforcement")
    class AdminEnforcementTests {

        @Test
        @DisplayName("The project owner may assign, change and remove actors")
        void ownerMayManage() {
            Project project = publicProjectOwnedByAlice();
            ActorDTO actor = createActor("pa-agent-owner-mgmt", ALICE);

            projectActorService.assignActor(project.getId(),
                    ProjectActorAssignDTO.builder().actorId(actor.getId()).build(), ALICE);
            projectActorService.updateRole(project.getId(), actor.getId(),
                    ProjectActorUpdateRoleDTO.builder().role("worker").build(), ALICE);
            projectActorService.removeActor(project.getId(), actor.getId(), ALICE);

            assertThat(projectActorRepository.count()).isZero();
        }

        @Test
        @DisplayName("An ADMIN user may manage any public project")
        void adminUserMayManageForeignProject() {
            Project project = publicProjectOwnedByAlice();
            ActorDTO actor = createActor("pa-agent-admin-mgmt", BOB);

            ProjectActorDTO assigned = projectActorService.assignActor(project.getId(),
                    ProjectActorAssignDTO.builder().actorId(actor.getId()).role("admin").build(), ADMIN);

            assertThat(assigned.getRole()).isEqualTo(ProjectActor.ROLE_ADMIN);
        }

        @Test
        @DisplayName("A caller who owns an actor with role 'admin' in the project may manage it")
        void callerWithAdminActorRoleMayManage() {
            Project project = publicProjectOwnedByAlice();
            ActorDTO bobsActor = createActor("pa-agent-bobs-admin", BOB);
            // The project owner grants Bob's actor the admin role.
            projectActorService.assignActor(project.getId(),
                    ProjectActorAssignDTO.builder().actorId(bobsActor.getId()).role("admin").build(), ALICE);

            // Bob may now manage the project's actors himself.
            ActorDTO newcomer = createActor("pa-agent-newcomer", ALICE);
            ProjectActorDTO assigned = projectActorService.assignActor(project.getId(),
                    ProjectActorAssignDTO.builder().actorId(newcomer.getId()).role("worker").build(), BOB);
            assertThat(assigned.getActorId()).isEqualTo(newcomer.getId());

            projectActorService.updateRole(project.getId(), newcomer.getId(),
                    ProjectActorUpdateRoleDTO.builder().role("planner").build(), BOB);
            projectActorService.removeActor(project.getId(), newcomer.getId(), BOB);

            assertThat(projectActorService.getRole(project.getId(), bobsActor.getId()))
                    .isEqualTo(ProjectActor.ROLE_ADMIN);
        }

        @Test
        @DisplayName("A caller whose actor holds a non-admin role may NOT manage the project")
        void callerWithWorkerActorRoleMayNotManage() {
            Project project = publicProjectOwnedByAlice();
            ActorDTO bobsActor = createActor("pa-agent-bobs-worker", BOB);
            projectActorService.assignActor(project.getId(),
                    ProjectActorAssignDTO.builder().actorId(bobsActor.getId()).role("worker").build(), ALICE);

            ActorDTO newcomer = createActor("pa-agent-target", ALICE);
            projectActorService.assignActor(project.getId(),
                    ProjectActorAssignDTO.builder().actorId(newcomer.getId()).build(), ALICE);

            assertThatThrownBy(() -> projectActorService.assignActor(project.getId(),
                    ProjectActorAssignDTO.builder().actorId(createActor("pa-agent-late", ALICE).getId()).build(), BOB))
                    .isInstanceOf(AccessDeniedException.class);

            assertThatThrownBy(() -> projectActorService.updateRole(project.getId(), newcomer.getId(),
                    ProjectActorUpdateRoleDTO.builder().role("admin").build(), BOB))
                    .isInstanceOf(AccessDeniedException.class);

            assertThatThrownBy(() -> projectActorService.removeActor(project.getId(), newcomer.getId(), BOB))
                    .isInstanceOf(AccessDeniedException.class);

            // Nothing changed.
            assertThat(projectActorService.getRole(project.getId(), newcomer.getId()))
                    .isEqualTo(ProjectActor.ROLE_VIEWER);
        }

        @Test
        @DisplayName("A caller with no relation to the project is forbidden (403)")
        void unrelatedCallerIsForbidden() {
            Project project = publicProjectOwnedByAlice();
            ActorDTO actor = createActor("pa-agent-outsider", BOB);

            assertThatThrownBy(() -> projectActorService.assignActor(project.getId(),
                    ProjectActorAssignDTO.builder().actorId(actor.getId()).build(), BOB))
                    .isInstanceOf(AccessDeniedException.class);

            assertThat(projectActorRepository.count()).isZero();
        }

        @Test
        @DisplayName("An anonymous caller cannot write project actors")
        void anonymousCallerCannotManage() {
            Project project = publicProjectOwnedByAlice();
            ActorDTO actor = createActor("pa-agent-anon", ALICE);

            assertThatThrownBy(() -> projectActorService.assignActor(project.getId(),
                    ProjectActorAssignDTO.builder().actorId(actor.getId()).build(), "anonymous"))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("A caller whose actor is admin in ANOTHER project gets no rights here")
        void adminRoleDoesNotLeakAcrossProjects() {
            Project managed = publicProjectOwnedByAlice();
            Project foreign = publicProjectOwnedByAlice();
            ActorDTO bobsActor = createActor("pa-agent-bobs-admin2", BOB);
            projectActorService.assignActor(managed.getId(),
                    ProjectActorAssignDTO.builder().actorId(bobsActor.getId()).role("admin").build(), ALICE);

            ActorDTO target = createActor("pa-agent-other-project", ALICE);

            assertThatThrownBy(() -> projectActorService.assignActor(foreign.getId(),
                    ProjectActorAssignDTO.builder().actorId(target.getId()).build(), BOB))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("Someone else's private project is 404, not 403 (existence not revealed)")
        void foreignPrivateProjectIsNotFound() {
            Project privateProject = createProject(
                    "PA private " + System.nanoTime(), alice, Project.VISIBILITY_PRIVATE);
            ActorDTO actor = createActor("pa-agent-private", BOB);

            assertThatThrownBy(() -> projectActorService.listProjectActors(
                    privateProject.getId(), null, BOB))
                    .isInstanceOf(EntityNotFoundException.class);
            assertThatThrownBy(() -> projectActorService.assignActor(privateProject.getId(),
                    ProjectActorAssignDTO.builder().actorId(actor.getId()).build(), BOB))
                    .isInstanceOf(EntityNotFoundException.class);
        }

        @Test
        @DisplayName("The owner and an ADMIN user can read a private project's actors")
        void ownerAndAdminCanReadPrivateProjectActors() {
            Project privateProject = createProject(
                    "PA private read " + System.nanoTime(), alice, Project.VISIBILITY_PRIVATE);
            ActorDTO actor = createActor("pa-agent-private-read", ALICE);
            projectActorService.assignActor(privateProject.getId(),
                    ProjectActorAssignDTO.builder().actorId(actor.getId()).role("planner").build(), ALICE);

            assertThat(projectActorService.listProjectActors(privateProject.getId(), null, ALICE))
                    .hasSize(1);
            assertThat(projectActorService.listProjectActors(privateProject.getId(), null, ADMIN))
                    .hasSize(1);
        }

        @Test
        @DisplayName("Actors of a public project are readable by anyone, even anonymously")
        void publicProjectActorsAreReadableAnonymously() {
            Project project = publicProjectOwnedByAlice();
            ActorDTO actor = createActor("pa-agent-public-read", ALICE);
            projectActorService.assignActor(project.getId(),
                    ProjectActorAssignDTO.builder().actorId(actor.getId()).build(), ALICE);

            assertThat(projectActorService.listProjectActors(project.getId(), null, "anonymous"))
                    .hasSize(1);
        }
    }

    // ── getRole / hasAtLeast (used by the later coordination stories) ─────────

    @Nested
    @DisplayName("getRole and hasAtLeast")
    class RoleQueryTests {

        @Test
        @DisplayName("getRole returns the assigned role, or null when unassigned")
        void getRoleReturnsRoleOrNull() {
            Project project = publicProjectOwnedByAlice();
            ActorDTO actor = createActor("pa-agent-rolequery", ALICE);
            projectActorService.assignActor(project.getId(),
                    ProjectActorAssignDTO.builder().actorId(actor.getId()).role("planner").build(), ALICE);

            assertThat(projectActorService.getRole(project.getId(), actor.getId()))
                    .isEqualTo(ProjectActor.ROLE_PLANNER);
            assertThat(projectActorService.getRole(project.getId(), 999_999L)).isNull();
            assertThat(projectActorService.getRole(null, actor.getId())).isNull();
        }

        @Test
        @DisplayName("hasAtLeast follows viewer < worker < planner < admin")
        void hasAtLeastComparesTheRoleHierarchy() {
            Project project = publicProjectOwnedByAlice();
            ActorDTO worker = createActor("pa-agent-h-worker", ALICE);
            projectActorService.assignActor(project.getId(),
                    ProjectActorAssignDTO.builder().actorId(worker.getId()).role("worker").build(), ALICE);

            assertThat(projectActorService.hasAtLeast(project.getId(), worker.getId(), "viewer")).isTrue();
            assertThat(projectActorService.hasAtLeast(project.getId(), worker.getId(), "WORKER")).isTrue();
            assertThat(projectActorService.hasAtLeast(project.getId(), worker.getId(), "planner")).isFalse();
            assertThat(projectActorService.hasAtLeast(project.getId(), worker.getId(), "admin")).isFalse();

            projectActorService.updateRole(project.getId(), worker.getId(),
                    ProjectActorUpdateRoleDTO.builder().role("admin").build(), ALICE);
            assertThat(projectActorService.hasAtLeast(project.getId(), worker.getId(), "planner")).isTrue();
            assertThat(projectActorService.hasAtLeast(project.getId(), worker.getId(), "admin")).isTrue();
        }

        @Test
        @DisplayName("An actor that is not assigned to the project never passes")
        void hasAtLeastIsFalseForUnassignedActor() {
            Project project = publicProjectOwnedByAlice();
            ActorDTO actor = createActor("pa-agent-unassigned-role", ALICE);

            assertThat(projectActorService.hasAtLeast(project.getId(), actor.getId(), "viewer")).isFalse();
        }

        @Test
        @DisplayName("An archived actor keeps its stored role but never passes hasAtLeast")
        void hasAtLeastIsFalseForArchivedActor() {
            Project project = publicProjectOwnedByAlice();
            ActorDTO actor = createActor("pa-agent-archived-role", ALICE);
            projectActorService.assignActor(project.getId(),
                    ProjectActorAssignDTO.builder().actorId(actor.getId()).role("admin").build(), ALICE);

            assertThat(projectActorService.hasAtLeast(project.getId(), actor.getId(), "admin")).isTrue();

            actorService.updateActor(actor.getId(),
                    com.wiki4ai.dto.ActorUpdateDTO.builder().status("archived").build(), ALICE);

            // The assignment row is still there and reports its role...
            assertThat(projectActorService.getRole(project.getId(), actor.getId()))
                    .isEqualTo(ProjectActor.ROLE_ADMIN);
            // ...but an archived actor is not usable for new work.
            assertThat(projectActorService.hasAtLeast(project.getId(), actor.getId(), "viewer")).isFalse();
        }

        @Test
        @DisplayName("An unknown required role is rejected (400)")
        void hasAtLeastRejectsUnknownRequiredRole() {
            Project project = publicProjectOwnedByAlice();
            ActorDTO actor = createActor("pa-agent-badrequire", ALICE);
            projectActorService.assignActor(project.getId(),
                    ProjectActorAssignDTO.builder().actorId(actor.getId()).build(), ALICE);

            assertThatThrownBy(() -> projectActorService.hasAtLeast(project.getId(), actor.getId(), "owner"))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Invalid role");
        }
    }
}
