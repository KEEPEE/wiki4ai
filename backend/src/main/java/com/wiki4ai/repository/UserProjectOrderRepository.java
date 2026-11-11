package com.wiki4ai.repository;

import com.wiki4ai.model.UserProjectOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository for one viewer's project ordering rows (WIKI4AI-200, PER-1).
 *
 * <h2>Every read is scoped to one viewer</h2>
 * There is deliberately no method here that reads across users. The feature exists
 * because a global read/write let one user's pin move another user's list; a repository
 * method that could read every viewer's rows is the same mistake one layer down, and it
 * would be the natural thing for the next endpoint to reach for.
 *
 * <h2>Writes are JPQL UPDATEs, not load-and-save</h2>
 * Same reasoning as {@link ProjectRepository} and WIKI4AI-197 commit 11: the reorder is a
 * compare-and-set on the position the caller read, so the guard lives in the WHERE clause.
 * A load-and-save guarded by {@code @Version} is the wrong tool — this entity has no
 * version, and adding one would turn a sidebar drag into a 409 against the same viewer's
 * next request.
 *
 * <p>{@code clearAutomatically} is required because the entity IS mapped: without it the
 * persistence context keeps the pre-update values and a DTO built after the write reports
 * the old pin. {@code flushAutomatically} keeps the order of writes correct when a reorder
 * renumbers rows in a loop.</p>
 *
 * <h2>{@code updated_at} is set by hand</h2>
 * A JPQL bulk UPDATE does not run {@code @PreUpdate}, so a bulk write that wanted to move
 * the timestamp would have to say so. Every bulk statement here takes {@code :now} for
 * exactly that reason — passing it from the service keeps the JPQL free of a
 * {@code CURRENT_TIMESTAMP} whose type Hibernate has to reconcile with a
 * {@code LocalDateTime} attribute.
 */
@Repository
public interface UserProjectOrderRepository extends JpaRepository<UserProjectOrder, Long> {

    /**
     * Every row of one viewer — the read the project list LEFT JOINs in its place.
     *
     * <p>The list is small (at most one row per project this viewer has customised) and
     * the caller builds a {@code Map<Long, UserProjectOrder>} from it, so the whole
     * dashboard costs one indexed query rather than one per project.</p>
     */
    List<UserProjectOrder> findByUserId(Long userId);

    /**
     * The one row of one viewer for one project, if they have customised it.
     *
     * <p>Used by the single-project reads and by the pin/unpin/reorder responses, which
     * must report the caller's view and not the global one — {@code pinned} in the DTO is
     * the viewer's state now, and the frontend applies that response straight to its local
     * list (WIKI4AI-186), so a global value there would visibly undo the pin the user just
     * made.</p>
     */
    Optional<UserProjectOrder> findByUserIdAndProjectId(Long userId, Long projectId);

    /**
     * Lowest position this viewer has, or 0 while they have no rows at all
     * (WIKI4AI-200). A freshly pinned project takes one slot above it, so it lands at the
     * top of the viewer's own list — the same min-1 trick the global column uses, scoped
     * to the caller.
     *
     * <p>Read under the viewer's ordering lock, or two concurrent pins by the same viewer
     * are handed the same slot.</p>
     */
    @Query("SELECT COALESCE(MIN(o.sortOrder), 0) FROM UserProjectOrder o WHERE o.userId = :userId")
    int minSortOrderForViewer(@Param("userId") Long userId);

    /**
     * Set this viewer's pin flag and position in one UPDATE (WIKI4AI-200). Used by
     * pin/unpin, where both change together.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE UserProjectOrder o SET o.pinned = :pinned, o.sortOrder = :sortOrder, "
            + "o.updatedAt = :now WHERE o.id = :id")
    int updatePinning(@Param("id") Long id,
                      @Param("pinned") boolean pinned,
                      @Param("sortOrder") int sortOrder,
                      @Param("now") LocalDateTime now);

    /**
     * Compare-and-set this viewer's position (WIKI4AI-200): the UPDATE lands only if the
     * row still holds the position the caller read.
     *
     * @param id                the row to move
     * @param sortOrder         the position to write
     * @param expectedSortOrder the position the caller read
     * @return 1 when the row moved, 0 when something else moved it first
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE UserProjectOrder o SET o.sortOrder = :sortOrder, o.updatedAt = :now "
            + "WHERE o.id = :id AND o.sortOrder = :expectedSortOrder")
    int updateSortOrderIfCurrent(@Param("id") Long id,
                                 @Param("sortOrder") int sortOrder,
                                 @Param("expectedSortOrder") int expectedSortOrder,
                                 @Param("now") LocalDateTime now);

    /**
     * Drop one viewer's whole customisation (WIKI4AI-200).
     *
     * <p>The database already does this with {@code ON DELETE CASCADE} on
     * {@code user_id} when an account is deleted, and the test suite needs it because the
     * H2 schema is built from the entities, where that cascade does not exist.</p>
     */
    @Modifying
    @Query("DELETE FROM UserProjectOrder o WHERE o.userId = :userId")
    int deleteAllForViewer(@Param("userId") Long userId);

    /**
     * Drop every viewer's rows for one project (WIKI4AI-200).
     *
     * <p>Same reasoning: {@code ON DELETE CASCADE} on {@code project_id} covers production;
     * this is the explicit form for code and tests that run without that cascade.</p>
     */
    @Modifying
    @Query("DELETE FROM UserProjectOrder o WHERE o.projectId = :projectId")
    int deleteAllForProject(@Param("projectId") Long projectId);
}
