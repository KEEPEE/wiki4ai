package com.wiki4ai.service;

import com.wiki4ai.dto.ProjectCreateDTO;
import com.wiki4ai.dto.ProjectDTO;
import com.wiki4ai.dto.ReorderRequestDTO;
import com.wiki4ai.model.Role;
import com.wiki4ai.model.User;
import com.wiki4ai.model.UserProjectOrder;
import com.wiki4ai.repository.DocumentRepository;
import com.wiki4ai.repository.ProjectPermissionRepository;
import com.wiki4ai.repository.ProjectRepository;
import com.wiki4ai.repository.UserProjectOrderRepository;
import com.wiki4ai.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WIKI4AI-200 (PER-1): two writers of ONE viewer's order must not lose each other.
 *
 * <p>WIKI4AI-197 commit 11 solved this for the global column by locking the whole projects
 * table. Per-user ordering moves the unit of serialisation to the viewer, so the same race
 * has to be re-won at the new unit — and the acceptance criterion names it: "concurrent
 * reorder of two drags over the same account leaves no duplicate {@code sort_order}".</p>
 *
 * <h2>Why the assertions are about shape, not about who won</h2>
 *
 * Which drag wins is timing. What must hold however the two interleave is that the
 * viewer's stored positions are still a dense permutation of 0..n-1. A duplicate position
 * is exactly what a lost writer produces, and it is what the SQL
 * {@code count(*) vs count(DISTINCT sort_order)} in the acceptance criteria checks on dev —
 * this test is that check, run on every build.</p>
 *
 * <h2>Why these tests are not {@code @Transactional}</h2>
 *
 * A {@code @Transactional} test method rolls back instead of committing, so a second
 * thread could never see — or fight with — the first one's locks. Every write here goes
 * through a service method that opens its own transaction, on the calling thread or on one
 * of the two pool threads.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("WIKI4AI-200: two writers of one viewer's order")
class PerUserOrderingConcurrencyTest {

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private ProjectPermissionRepository projectPermissionRepository;

    @Autowired
    private UserProjectOrderRepository userProjectOrderRepository;

    @Autowired
    private UserRepository userRepository;

    private final List<User> createdUsers = new ArrayList<>();

    private User newUser(String username) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@test.local");
        user.setPassword("not-a-real-hash");
        user.setRole(Role.USER);
        User saved = userRepository.save(user);
        createdUsers.add(saved);
        return saved;
    }

    private ProjectDTO createProject(String name, String owner) {
        ProjectCreateDTO dto = new ProjectCreateDTO();
        dto.setName(name);
        dto.setDescription("concurrency playground");
        return projectService.createProject(dto, owner);
    }

    private List<Integer> viewerPositions(Long userId) {
        return userProjectOrderRepository.findByUserId(userId).stream()
                .map(UserProjectOrder::getSortOrder)
                .sorted()
                .collect(Collectors.toList());
    }

    @BeforeEach
    @AfterEach
    void leaveNothingBehind() {
        documentRepository.deleteAll();
        userProjectOrderRepository.deleteAll();
        projectPermissionRepository.deleteAll();
        projectRepository.deleteAll();
        userRepository.deleteAll(createdUsers);
        createdUsers.clear();
    }

    /**
     * Run two calls on two threads released at the same instant and return whatever they
     * threw (null entries mean the call succeeded).
     */
    private List<Throwable> race(Runnable first, Runnable second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
            Future<?> a = pool.submit(() -> {
                awaitQuietly(barrier);
                try {
                    first.run();
                } catch (Throwable t) {
                    failures.add(t);
                }
            });
            Future<?> b = pool.submit(() -> {
                awaitQuietly(barrier);
                try {
                    second.run();
                } catch (Throwable t) {
                    failures.add(t);
                }
            });
            a.get(30, TimeUnit.SECONDS);
            b.get(30, TimeUnit.SECONDS);
            return failures;
        } finally {
            pool.shutdownNow();
        }
    }

    private static void awaitQuietly(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // A broken barrier still runs the body; the assertions below decide.
        }
    }

    // =======================================================================

    @Test
    @DisplayName("two concurrent drags by the SAME account both succeed and leave a dense order")
    void twoDragsByOneAccountLeaveADenseOrder() throws Exception {
        User a = newUser("race-same-a");
        List<String> slugs = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            slugs.add(createProject("Race " + i, a.getUsername()).getSlug());
        }

        List<Throwable> failures = race(
                () -> projectService.reorderProject(slugs.get(0),
                        ReorderRequestDTO.builder().position(5).build(), a.getUsername()),
                () -> projectService.reorderProject(slugs.get(5),
                        ReorderRequestDTO.builder().position(0).build(), a.getUsername()));

        assertThat(failures).isEmpty();
        assertThat(viewerPositions(a.getId()))
                .as("count(*) and count(DISTINCT sort_order) must agree — a duplicate is a "
                        + "lost writer, which is the acceptance criterion for this story")
                .containsExactly(0, 1, 2, 3, 4, 5);
    }

    @Test
    @DisplayName("two concurrent first-time pins by the SAME account take different slots")
    void twoFirstPinsByOneAccountDoNotCollide() throws Exception {
        // The case a row-set lock cannot cover: the account has NO rows yet, so
        // FOR UPDATE over its own rows would lock nothing and both pins would be handed
        // the same min-1. The users row is the mutex precisely because it always exists.
        User a = newUser("race-firstpins-a");
        ProjectDTO one = createProject("Pin One", a.getUsername());
        ProjectDTO two = createProject("Pin Two", a.getUsername());

        List<Throwable> failures = race(
                () -> projectService.pinProject(one.getSlug(), a.getUsername()),
                () -> projectService.pinProject(two.getSlug(), a.getUsername()));

        assertThat(failures)
                .as("neither pin may die on uk_user_project_order or take the same slot")
                .isEmpty();

        List<Integer> positions = viewerPositions(a.getId());
        assertThat(positions)
                .as("two pins, two distinct positions")
                .hasSize(2)
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("two DIFFERENT accounts racing both keep their own pin and nothing else")
    void twoAccountsRacingStayApart() throws Exception {
        User a = newUser("race-two-a");
        User b = newUser("race-two-b");
        // Each account acts on a project it manages: the permission rule of WIKI4AI-184
        // is unchanged by this story, so a cross-account race is two users arranging their
        // OWN dashboards at the same instant, which is exactly what the old global table
        // lock made them queue for.
        ProjectDTO aProject = createProject("A Contested", a.getUsername());
        createProject("A Other", a.getUsername());
        ProjectDTO bProject = createProject("B Contested", b.getUsername());
        createProject("B Other", b.getUsername());

        List<Throwable> failures = race(
                () -> projectService.pinProject(aProject.getSlug(), a.getUsername()),
                () -> projectService.reorderProject(bProject.getSlug(),
                        ReorderRequestDTO.builder().position(0).build(), b.getUsername()));

        assertThat(failures).isEmpty();

        assertThat(userProjectOrderRepository.findByUserId(a.getId()))
                .as("A's pin is A's row and nothing else")
                .hasSize(1);
        assertThat(userProjectOrderRepository.findByUserId(b.getId()))
                .as("B's drag renumbered B's own list")
                .isNotEmpty();

        // B's list legitimately CONTAINS A's public project — a per-user order covers
        // every project the viewer can see, not only the ones they own. What must not
        // happen is B's drag changing A's view of it, or either of them changing the
        // global column.
        UserProjectOrder aRowForAProject = userProjectOrderRepository
                .findByUserIdAndProjectId(a.getId(), aProject.getId()).orElseThrow();
        assertThat(aRowForAProject.isPinned())
                .as("A's pin survived B's concurrent drag")
                .isTrue();
        userProjectOrderRepository.findByUserIdAndProjectId(b.getId(), aProject.getId())
                .ifPresent(bRow -> assertThat(bRow.isPinned())
                        .as("B never pinned A's project, and B's drag must not have")
                        .isFalse());

        assertThat(projectRepository.findById(aProject.getId()).orElseThrow().isPinned())
                .as("neither write touched the global column")
                .isFalse();
    }
}
