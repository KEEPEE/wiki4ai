package com.wiki4ai.repository;

import com.wiki4ai.model.User;
import com.wiki4ai.model.UserProjectOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WIKI4AI-200 (PER-1): the per-viewer ordering rows.
 *
 * <p>The suite builds its H2 schema from the entities (Flyway is off), so what this test
 * can prove is the entity's own shape — the unique constraint, the not-null columns, the
 * derived per-viewer reads — and the JPQL of the two guarded writes. What it cannot prove
 * is the {@code ON DELETE CASCADE} of V28, which only exists in PostgreSQL; that is
 * asserted from the migration text in {@code UserProjectOrderMigrationTest} and verified
 * against the live dev database.</p>
 */
@DataJpaTest
@ActiveProfiles("test")
@DisplayName("UserProjectOrderRepository: one viewer's ordering of one project")
class UserProjectOrderRepositoryTest {

    @Autowired
    private UserProjectOrderRepository orderRepository;

    @Autowired
    private UserRepository userRepository;

    private User user(String name) {
        return userRepository.save(User.builder()
                .username(name)
                .email(name + "@example.com")
                .password("x")
                .build());
    }

    private UserProjectOrder row(Long userId, Long projectId, boolean pinned, int sortOrder) {
        return orderRepository.saveAndFlush(UserProjectOrder.builder()
                .userId(userId)
                .projectId(projectId)
                .pinned(pinned)
                .sortOrder(sortOrder)
                .build());
    }

    // =======================================================================

    @Nested
    @DisplayName("The one row per (viewer, project)")
    class Uniqueness {

        @Test
        @DisplayName("a viewer cannot hold two rows for the same project")
        void twoRowsForTheSameViewerAndProjectAreRefused() {
            User viewer = user("dup-viewer");
            row(viewer.getId(), 11L, false, 0);

            assertThatThrownBy(() -> row(viewer.getId(), 11L, true, 4))
                    .as("uk_user_project_order is the whole feature: two rows would list the "
                            + "same project twice at two positions")
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("two viewers may hold the same project, each with their own state")
        void twoViewersAreIndependent() {
            User a = user("viewer-a");
            User b = user("viewer-b");
            row(a.getId(), 42L, true, -1);
            row(b.getId(), 42L, false, 7);

            UserProjectOrder fromA = orderRepository
                    .findByUserIdAndProjectId(a.getId(), 42L).orElseThrow();
            UserProjectOrder fromB = orderRepository
                    .findByUserIdAndProjectId(b.getId(), 42L).orElseThrow();

            assertThat(fromA.isPinned()).isTrue();
            assertThat(fromA.getSortOrder()).isEqualTo(-1);
            assertThat(fromB.isPinned()).isFalse();
            assertThat(fromB.getSortOrder()).isEqualTo(7);
        }
    }

    @Nested
    @DisplayName("Reads are scoped to one viewer")
    class Reads {

        @Test
        @DisplayName("findByUserId returns that viewer's rows and nobody else's")
        void findByUserIdIsScopedToTheViewer() {
            User a = user("scoped-a");
            User b = user("scoped-b");
            row(a.getId(), 1L, false, 0);
            row(a.getId(), 2L, true, -1);
            row(b.getId(), 3L, false, 5);

            List<UserProjectOrder> aRows = orderRepository.findByUserId(a.getId());

            assertThat(aRows).hasSize(2);
            assertThat(aRows).allSatisfy(r -> assertThat(r.getUserId()).isEqualTo(a.getId()));
        }

        @Test
        @DisplayName("a viewer who customised nothing has no rows, which is not an error")
        void absenceIsAValue() {
            User fresh = user("fresh-viewer");

            assertThat(orderRepository.findByUserId(fresh.getId())).isEmpty();
            assertThat(orderRepository.findByUserIdAndProjectId(fresh.getId(), 99L))
                    .isEqualTo(Optional.empty());
        }
    }

    @Nested
    @DisplayName("minSortOrderForViewer")
    class MinPosition {

        @Test
        @DisplayName("0 while the viewer has no rows, so the first pin lands at -1")
        void minIsZeroForAViewerWithNoRows() {
            User fresh = user("min-fresh");

            assertThat(orderRepository.minSortOrderForViewer(fresh.getId())).isZero();
        }

        @Test
        @DisplayName("the viewer's own minimum, negative values included")
        void minIsTheViewersOwnLowestPosition() {
            User a = user("min-a");
            User b = user("min-b");
            row(a.getId(), 1L, false, 0);
            row(a.getId(), 2L, true, -3);
            row(b.getId(), 3L, true, -99);

            assertThat(orderRepository.minSortOrderForViewer(a.getId()))
                    .as("another viewer's -99 must not become this viewer's slot")
                    .isEqualTo(-3);
        }
    }

    @Nested
    @DisplayName("The two guarded writes")
    class Writes {

        @Test
        @DisplayName("the compare-and-set lands when the caller read the current position")
        void compareAndSetLandsOnThePositionItRead() {
            User viewer = user("cas-hit");
            UserProjectOrder moved = row(viewer.getId(), 5L, false, 3);

            int updated = orderRepository.updateSortOrderIfCurrent(
                    moved.getId(), 0, 3, LocalDateTime.now());

            assertThat(updated).isEqualTo(1);
            assertThat(orderRepository.findById(moved.getId()).orElseThrow().getSortOrder())
                    .isZero();
        }

        @Test
        @DisplayName("the compare-and-set writes nothing when somebody moved it first")
        void compareAndSetLosesAgainstAConcurrentMove() {
            User viewer = user("cas-miss");
            UserProjectOrder moved = row(viewer.getId(), 6L, false, 3);

            int updated = orderRepository.updateSortOrderIfCurrent(
                    moved.getId(), 0, 2, LocalDateTime.now());

            assertThat(updated)
                    .as("0 rows is the signal the service turns into a retry, not a silent "
                            + "overwrite of the writer that got there first")
                    .isZero();
            assertThat(orderRepository.findById(moved.getId()).orElseThrow().getSortOrder())
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("updatePinning sets pin and position together and moves updated_at")
        void updatePinningWritesBothColumnsAndTheTimestamp() {
            User viewer = user("pin-write");
            UserProjectOrder created = row(viewer.getId(), 7L, false, 0);
            LocalDateTime before = created.getUpdatedAt();

            LocalDateTime now = LocalDateTime.now().plusSeconds(5);
            int updated = orderRepository.updatePinning(created.getId(), true, -1, now);

            assertThat(updated).isEqualTo(1);
            UserProjectOrder after = orderRepository.findById(created.getId()).orElseThrow();
            assertThat(after.isPinned()).isTrue();
            assertThat(after.getSortOrder()).isEqualTo(-1);
            assertThat(after.getUpdatedAt())
                    .as("a bulk UPDATE does not run @PreUpdate, so the statement has to set "
                            + "the timestamp itself or the column would freeze at insert time")
                    .isAfter(before);
        }

        @Test
        @DisplayName("the writes never touch another viewer's row")
        void aWriteIsScopedToTheRowItNames() {
            User a = user("scope-a");
            User b = user("scope-b");
            UserProjectOrder aRow = row(a.getId(), 8L, false, 0);
            UserProjectOrder bRow = row(b.getId(), 8L, false, 0);

            orderRepository.updatePinning(aRow.getId(), true, -1, LocalDateTime.now());

            assertThat(orderRepository.findById(bRow.getId()).orElseThrow().isPinned())
                    .as("this is the entire point of the story: one viewer's pin must not "
                            + "move another viewer's list")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("Deletes")
    class Deletes {

        @Test
        @DisplayName("deleteAllForViewer drops one account's whole customisation")
        void deleteAllForViewerDropsOnlyThatViewer() {
            User a = user("del-a");
            User b = user("del-b");
            row(a.getId(), 1L, false, 0);
            row(a.getId(), 2L, false, 1);
            row(b.getId(), 1L, false, 0);

            assertThat(orderRepository.deleteAllForViewer(a.getId())).isEqualTo(2);
            assertThat(orderRepository.findByUserId(a.getId())).isEmpty();
            assertThat(orderRepository.findByUserId(b.getId())).hasSize(1);
        }

        @Test
        @DisplayName("deleteAllForProject drops every viewer's row for that project")
        void deleteAllForProjectDropsEveryViewer() {
            User a = user("del-proj-a");
            User b = user("del-proj-b");
            row(a.getId(), 100L, false, 0);
            row(b.getId(), 100L, true, -1);
            row(a.getId(), 101L, false, 1);

            assertThat(orderRepository.deleteAllForProject(100L)).isEqualTo(2);
            assertThat(orderRepository.findByUserId(a.getId())).hasSize(1);
            assertThat(orderRepository.findByUserId(b.getId())).isEmpty();
        }
    }

    @Nested
    @DisplayName("The viewer row is the mutex")
    class ViewerLock {

        @Test
        @DisplayName("findUserByIdForUpdate returns the account it locks")
        void theLockQueryResolvesTheUser() {
            User viewer = user("lock-viewer");

            assertThat(userRepository.findUserByIdForUpdate(viewer.getId()))
                    .get()
                    .satisfies(u -> assertThat(u.getUsername()).isEqualTo("lock-viewer"));
        }

        @Test
        @DisplayName("findUserByIdForUpdate is empty for an account that is gone")
        void theLockQueryIsEmptyForAMissingAccount() {
            assertThat(userRepository.findUserByIdForUpdate(999_999L)).isEmpty();
        }
    }
}
