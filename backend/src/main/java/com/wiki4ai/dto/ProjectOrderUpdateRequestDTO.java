package com.wiki4ai.dto;

import java.util.List;

/**
 * Body of {@code PUT /api/v1/admin/projects-order} (WIKI4AI-200): the administrator's
 * curated global default order.
 *
 * <p>Partial by design. Each entry may omit {@code pinned} or {@code sortOrder} to leave
 * that one value alone, and a project absent from the list keeps both. An administrator
 * who wants to move three projects does not have to restate the position of the other
 * eighty, and a payload that names every project still works.</p>
 *
 * <p>The list is applied in the order it arrives, so two entries that ask for the same
 * {@code sortOrder} resolve in favour of the later one rather than failing — the global
 * column has no uniqueness to violate, and {@code id} breaks the tie in the list either
 * way.</p>
 *
 * @param items the entries to write; required, and must not be empty
 */
public record ProjectOrderUpdateRequestDTO(
        List<Entry> items) {

    /**
     * One project's new global position.
     *
     * @param slug      the project to address; required
     * @param pinned    the new global pin flag, or null to leave it unchanged
     * @param sortOrder the new global position, or null to leave it unchanged
     */
    public record Entry(
            String slug,
            Boolean pinned,
            Integer sortOrder) {
    }
}
