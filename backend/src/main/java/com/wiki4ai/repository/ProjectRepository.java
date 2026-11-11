package com.wiki4ai.repository;

import com.wiki4ai.model.Project;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
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

    /**
     * Find direct children of a project.
     *
     * @param parentId the parent project id
     * @return list of child projects
     */
    List<Project> findByParentId(Long parentId);

    /**
     * Check whether a project has at least one child.
     *
     * @param parentId the parent project id
     * @return true if the project has children
     */
    boolean existsByParentId(Long parentId);

    /**
     * Find all root projects (no parent), ordered by creation date (newest first).
     *
     * @return list of root projects sorted by createdAt descending
     */
    List<Project> findByParentIsNullOrderByCreatedAtDesc();

    // ==================== WIKI4AI-184: pin to top + manual order ====================
    //
    // Both writes are JPQL UPDATEs, not load-and-save. A managed Project would go
    // through @PreUpdate and move updatedAt, and updatedAt is one of the sort keys
    // (updated_desc) — pinning or dragging a project in a sidebar would silently
    // reshuffle a list the caller never edited.
    //
    // clearAutomatically is required because these columns ARE mapped on the entity:
    // without it the persistence context keeps the pre-update values and the DTO built
    // after the write would report the old pinned/sortOrder. flushAutomatically keeps
    // the ordering of writes correct when a reorder renumbers rows in a loop.

    /**
     * Set the pinned flag and the manual position of one project in a single UPDATE
     * (WIKI4AI-184). Used by pin/unpin, where both columns change together.
     *
     * @param id        the project id
     * @param pinned    the new pinned flag
     * @param sortOrder the new manual position
     * @return number of updated rows (0 when the project is gone)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Project p SET p.pinned = :pinned, p.sortOrder = :sortOrder WHERE p.id = :id")
    int updatePinning(@Param("id") Long id,
                      @Param("pinned") boolean pinned,
                      @Param("sortOrder") int sortOrder);

    // The unconditional "set the position of one project" UPDATE that WIKI4AI-184 added
    // is gone (WIKI4AI-197 commit 11): an unguarded write to sort_order is exactly how a
    // reorder loses to a concurrent one, and a repository method nobody has to think about
    // is a method somebody will eventually call. updateSortOrderIfCurrent is the only way
    // to move a project now.

    /**
     * Lowest manual position currently in use, or 0 when there are no projects
     * (WIKI4AI-184). A freshly pinned or freshly created project is placed one slot
     * above it, so it lands at the top of a sort=manual list — which is where a
     * client that has just created something expects to find it.
     */
    @Query("SELECT COALESCE(MIN(p.sortOrder), 0) FROM Project p")
    int minSortOrder();

    // ==================== WIKI4AI-197 commit 11: serialising the writers of sort_order ===
    //
    // Every writer of sort_order must take the same lock, or the lock does nothing.
    // Without it two sidebar drags that read the same list renumber from the same
    // snapshot and the second one silently overwrites the first; a pin racing a create
    // can hand both rows the same min-1.
    //
    // Projects share ONE global list (ProjectService.reorderProject renumbers all of
    // them), so the unit of serialisation is the whole table, not one row. Locking in a
    // fixed id order is what keeps two concurrent reorders from deadlocking.
    //
    // The timeout is a JDBC statement timeout, not a FOR UPDATE timeout: PostgreSQL has
    // NOWAIT and SKIP LOCKED but no "FOR UPDATE WAIT n", so a bounded wait has to be
    // enforced from the client side. Without it a stuck transaction holds a pooled
    // connection indefinitely, and OSIV is on, so the request holds one too.

    /**
     * Lock one project row for the duration of the transaction (WIKI4AI-197). Used by
     * every writer of a document's {@code sort_order}: the position is scoped to the
     * project, so the project row is the mutex that serialises its list.
     *
     * @param id the project id to lock
     * @return the project, or empty when it is gone
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.query.timeout", value = "5000"))
    @Query("SELECT p FROM Project p WHERE p.id = :id")
    Optional<Project> findProjectByIdForUpdate(@Param("id") Long id);

    /**
     * Lock every project row in id order and return them (WIKI4AI-197). This is the
     * project-list mutex: it both takes the lock and reads the canonical manual order,
     * which is the order the two must happen in — reading first and locking second means
     * reordering a list somebody else has already changed.
     *
     * @return every project, in id order, each row locked for update
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.query.timeout", value = "5000"))
    @Query("SELECT p FROM Project p ORDER BY p.id")
    List<Project> findAllForUpdate();

    /**
     * Compare-and-set the manual position of one project (WIKI4AI-197): the UPDATE only
     * lands if the row still holds the position the caller read. A load-and-save guarded
     * by {@code @Version} is the wrong tool here — {@code Project} has no {@code @Version},
     * and adding one would turn every sidebar drag into a 409 against an editor's save
     * (the very thing WIKI4AI-72 and WIKI4AI-184 avoided).
     *
     * @param id                the project id
     * @param sortOrder         the position to write
     * @param expectedSortOrder the position the caller read
     * @return 1 when the row was moved, 0 when somebody else moved it first
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Project p SET p.sortOrder = :sortOrder "
            + "WHERE p.id = :id AND p.sortOrder = :expectedSortOrder")
    int updateSortOrderIfCurrent(@Param("id") Long id,
                                 @Param("sortOrder") int sortOrder,
                                 @Param("expectedSortOrder") int expectedSortOrder);
}
