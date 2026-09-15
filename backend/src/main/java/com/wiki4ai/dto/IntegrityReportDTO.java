package com.wiki4ai.dto;

import java.util.Map;

/**
 * Integrity report for one wiki4ai instance (WIKI4AI-42, epic WIKI4AI-28).
 *
 * <p>Produced by {@code GET /api/v1/admin/integrity} and embedded into import
 * results (before/after). The checksums are <em>canonical</em>: entities are
 * ordered by slug (not by database ID) before hashing, so two instances holding
 * the same content produce identical checksums even though their auto-generated
 * IDs differ.</p>
 *
 * @param generatedAt         ISO-8601 UTC timestamp
 * @param counts              row counts per table: users, projects, documents,
 *                            documentLinks, projectPermissions, vaultEntries,
 *                            apiTokens, refreshTokens
 * @param projectsChecksum    MD5 over all projects ordered by slug of
 *                            {@code slug|name|md5(description)}
 * @param documentsChecksum   MD5 over all documents ordered by (projectSlug, slug) of
 *                            {@code projectSlug|slug|md5(content)}
 * @param documentTotalLength sum of document content lengths (cheap secondary signal)
 */
public record IntegrityReportDTO(
        String generatedAt,
        Map<String, Long> counts,
        String projectsChecksum,
        String documentsChecksum,
        long documentTotalLength
) {
}
