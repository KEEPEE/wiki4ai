package com.wiki4ai.service;

import com.wiki4ai.exception.BadRequestException;
import com.wiki4ai.model.Document;
import com.wiki4ai.model.Project;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The sort keys shared by {@code GET /projects} and {@code GET /projects/{slug}/documents}
 * (WIKI4AI-184).
 *
 * <p>One place owns the vocabulary because the two lists must speak the same language:
 * a frontend that learns {@code name_asc} for projects should not have to discover that
 * documents call it something else. The keys are plain strings rather than an enum in
 * the controller signature so that an unknown value produces a 400 with the list of
 * allowed keys instead of Spring's opaque conversion-failure message.</p>
 *
 * <h2>pinned is always the first key</h2>
 * Every comparator starts with {@code pinned DESC}, and so does the ORDER BY of the
 * paginated document query in {@code DocumentRepository}. That is what makes the feature
 * invisible to a client that does not use it: while nothing is pinned, the first key is
 * constant and the ordering degenerates to exactly the pre-WIKI4AI-184 one. The last key
 * is always {@code id}, so the order is total — which is what makes a paginated list
 * stable across pages (WIKI4AI-184 requires that sort=manual does not shuffle rows
 * between page 1 and page 2).</p>
 *
 * <h2>Two executors, one vocabulary</h2>
 * The project list is small, unpaginated and already visibility-filtered in Java, so it is
 * sorted here. The document list is paginated, so its ordering has to be SQL or the pages
 * would be inconsistent. The two must still agree, which is why {@code name_*} is
 * case-insensitive on both sides: the comparators use
 * {@link String#CASE_INSENSITIVE_ORDER} and the query orders by {@code LOWER(title)}.
 * Ordering by the raw column would have made the order depend on the database collation —
 * H2 sorts uppercase before lowercase, a UTF-8 Postgres does not — so the same request
 * would page differently in test and in production.</p>
 *
 * <h2>Defaults keep the old behaviour</h2>
 * Projects default to {@code created_desc} (they were ordered by createdAt DESC),
 * documents to {@code updated_desc} (they were ordered by updatedAt DESC). A request
 * without a {@code sort} parameter therefore sees the list it saw before this story.
 */
public final class ListSort {

    /** Order by the sort_order column maintained by the reorder endpoint. */
    public static final String MANUAL = "manual";
    /** Order by name/title, A→Z. */
    public static final String NAME_ASC = "name_asc";
    /** Order by name/title, Z→A. */
    public static final String NAME_DESC = "name_desc";
    /** Order by creation date, oldest first. */
    public static final String CREATED_ASC = "created_asc";
    /** Order by creation date, newest first — the historical order of the project list. */
    public static final String CREATED_DESC = "created_desc";
    /** Order by last update, newest first — the historical order of the document list. */
    public static final String UPDATED_DESC = "updated_desc";

    /** Default for the project list: the order {@code findAllByOrderByCreatedAtDesc} gave. */
    public static final String DEFAULT_PROJECT_SORT = CREATED_DESC;

    /** Default for the document list: the order {@code ...OrderByUpdatedAtDesc} gave. */
    public static final String DEFAULT_DOCUMENT_SORT = UPDATED_DESC;

    /** Every accepted value of the {@code sort} query parameter. */
    public static final List<String> ALLOWED = List.of(
            MANUAL, NAME_ASC, NAME_DESC, CREATED_ASC, CREATED_DESC, UPDATED_DESC);

    private ListSort() {
    }

    /**
     * Validate and normalize the {@code sort} parameter.
     *
     * @param sort        the raw value (null/blank = the caller asked for nothing)
     * @param fallback    the default for this endpoint
     * @param endpoint    name used in the error message, e.g. {@code "sort"}
     * @return one of {@link #ALLOWED}
     * @throws BadRequestException when the value is not one of the known keys
     */
    public static String normalize(String sort, String fallback, String endpoint) {
        if (sort == null || sort.isBlank()) {
            return fallback;
        }
        String key = sort.trim().toLowerCase(Locale.ROOT);
        if (!ALLOWED.contains(key)) {
            throw new BadRequestException("Unknown " + endpoint + " '" + sort + "'. Allowed values: "
                    + String.join(", ", ALLOWED));
        }
        return key;
    }

    /** pinned DESC — the key every ordering starts with. */
    private static Comparator<Document> pinnedFirstDocument() {
        return Comparator.comparing(Document::isPinned).reversed();
    }

    /**
     * The six values a project ordering reads, lifted out of the entity (WIKI4AI-200).
     *
     * <p>The reason this exists is that {@code pinned} and {@code sortOrder} are no longer
     * properties of the project alone. For a signed-in viewer they are the values of
     * {@code user_project_order} when that viewer has a row, and the project's own columns
     * when they do not. A comparator that reads {@code Project::isPinned} can only express
     * the global view, and the list would then sort one way and report another.</p>
     *
     * <p>So the ordering is expressed over this record and the caller decides where each
     * value comes from. {@link #of(Project)} is the global view — the anonymous caller, and
     * every viewer who has customised nothing.</p>
     */
    public record ProjectSortKey(boolean pinned, int sortOrder, String name,
                                 LocalDateTime createdAt, LocalDateTime updatedAt, Long id) {

        /** The key as the project row itself holds it: the global, uncustomised view. */
        public static ProjectSortKey of(Project project) {
            return new ProjectSortKey(project.isPinned(), project.getSortOrder(), project.getName(),
                    project.getCreatedAt(), project.getUpdatedAt(), project.getId());
        }

        /**
         * This key with the two ordering values replaced by what one viewer sees
         * (WIKI4AI-200). The four remaining values still come from the project, because
         * per-user ordering customises the position, not the project's name or dates.
         */
        public ProjectSortKey withViewerOrder(boolean viewerPinned, int viewerSortOrder) {
            return new ProjectSortKey(viewerPinned, viewerSortOrder, name, createdAt, updatedAt, id);
        }
    }

    /**
     * The project ordering expressed over {@link ProjectSortKey}.
     *
     * <p>Identical in shape to what {@link #projectComparator(String)} used to build
     * inline — pinned first, then the requested key, {@code id} as the final tie-break —
     * because the two must agree exactly: the list is sorted by one and the reorder
     * renumbers by the other.</p>
     *
     * @param sort a key already run through {@link #normalize}
     * @return a total comparator (pinned first, {@code id} last)
     */
    public static Comparator<ProjectSortKey> sortKeyComparator(String sort) {
        Comparator<ProjectSortKey> byId = Comparator.comparing(ProjectSortKey::id,
                Comparator.nullsFirst(Comparator.naturalOrder()));
        Comparator<ProjectSortKey> body;
        switch (sort) {
            case MANUAL:
                body = Comparator.comparingInt(ProjectSortKey::sortOrder).thenComparing(byId);
                break;
            case NAME_ASC:
                body = Comparator.comparing(ProjectSortKey::name, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(byId);
                break;
            case NAME_DESC:
                body = Comparator.comparing(ProjectSortKey::name, String.CASE_INSENSITIVE_ORDER)
                        .reversed().thenComparing(byId);
                break;
            case CREATED_ASC:
                body = Comparator.comparing(ProjectSortKey::createdAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())).thenComparing(byId);
                break;
            case CREATED_DESC:
                body = Comparator.comparing(ProjectSortKey::createdAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())).reversed()
                        .thenComparing(byId);
                break;
            case UPDATED_DESC:
                body = Comparator.comparing(ProjectSortKey::updatedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())).reversed()
                        .thenComparing(byId);
                break;
            default:
                throw new BadRequestException("Unknown sort '" + sort + "'. Allowed values: "
                        + String.join(", ", ALLOWED));
        }
        return Comparator.comparing(ProjectSortKey::pinned).reversed().thenComparing(body);
    }

    /**
     * The in-memory ordering for the project list. The project list is not paginated
     * and its visibility filter already runs in Java, so sorting in Java keeps one
     * code path instead of one query per sort key.
     *
     * <p>Delegates to {@link #sortKeyComparator(String)} over the project's own columns —
     * the global view. A caller holding per-viewer rows (WIKI4AI-200) sorts those keys
     * instead, because there {@code pinned} and {@code sortOrder} belong to the viewer and
     * not to the row.</p>
     *
     * @param sort a key already run through {@link #normalize}
     * @return a total comparator (pinned first, {@code id} last)
     */
    public static Comparator<Project> projectComparator(String sort) {
        return Comparator.comparing(ProjectSortKey::of, sortKeyComparator(sort));
    }

    /**
     * The in-memory ordering for the document list, used where documents are ordered
     * without pagination (the reorder endpoint computes the new order of the whole
     * project before renumbering it).
     *
     * @param sort a key already run through {@link #normalize}
     * @return a total comparator (pinned first, {@code id} last)
     */
    public static Comparator<Document> documentComparator(String sort) {
        Comparator<Document> byId = Comparator.comparing(Document::getId,
                Comparator.nullsFirst(Comparator.naturalOrder()));
        Comparator<Document> body;
        switch (sort) {
            case MANUAL:
                body = Comparator.comparingInt(Document::getSortOrder).thenComparing(byId);
                break;
            case NAME_ASC:
                body = Comparator.comparing(Document::getTitle, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(byId);
                break;
            case NAME_DESC:
                body = Comparator.comparing(Document::getTitle, String.CASE_INSENSITIVE_ORDER)
                        .reversed().thenComparing(byId);
                break;
            case CREATED_ASC:
                body = Comparator.comparing(Document::getCreatedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())).thenComparing(byId);
                break;
            case CREATED_DESC:
                body = Comparator.comparing(Document::getCreatedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())).reversed()
                        .thenComparing(byId);
                break;
            case UPDATED_DESC:
                body = Comparator.comparing(Document::getUpdatedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())).reversed()
                        .thenComparing(byId);
                break;
            default:
                throw new BadRequestException("Unknown sort '" + sort + "'. Allowed values: "
                        + String.join(", ", ALLOWED));
        }
        return pinnedFirstDocument().thenComparing(body);
    }

    /**
     * The canonical manual order of a list — the one the reorder endpoint renumbers.
     * Reorder always works on {@code manual} regardless of what the caller is viewing,
     * because sort_order is the only ordering it can write.
     */
    public static Comparator<Project> manualProjectOrder() {
        return projectComparator(MANUAL);
    }

    /** Canonical manual order of a project's documents; see {@link #manualProjectOrder()}. */
    public static Comparator<Document> manualDocumentOrder() {
        return documentComparator(MANUAL);
    }
}
