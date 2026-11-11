package com.wiki4ai.dto;

/**
 * One entry of the GLOBAL project order an administrator curates (WIKI4AI-200).
 *
 * <p>This is the order every user who has customised nothing inherits — the
 * {@code projects.pinned} / {@code projects.sort_order} columns of V26, which WIKI4AI-200
 * deliberately kept as the default rather than replacing them. It is NOT any individual
 * user's view: a user's own rows in {@code user_project_order} override it per project, and
 * this endpoint neither reads nor writes those.</p>
 *
 * @param id        the project id
 * @param slug      the project slug, which is what a {@code PUT} addresses projects by
 * @param name      the project name, for the administrator's own orientation
 * @param pinned    the global pin flag
 * @param sortOrder the global position in the manual order
 */
public record ProjectOrderEntryDTO(
        Long id,
        String slug,
        String name,
        boolean pinned,
        int sortOrder) {
}
