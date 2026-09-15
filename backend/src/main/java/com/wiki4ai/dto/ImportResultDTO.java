package com.wiki4ai.dto;

import java.util.List;
import java.util.Map;

/**
 * Result of a migration import (WIKI4AI-41 + WIKI4AI-42, epic WIKI4AI-28).
 *
 * <p>Carries the old→new ID mapping, per-type created/skipped counters and an
 * embedded integrity report (before/after counts + content verification) so a
 * migration is only considered successful when all checks pass.</p>
 *
 * @param mode                import mode that was applied: {@code merge} or {@code full}
 * @param importedAt          ISO-8601 UTC timestamp
 * @param before              entity counts on the target <em>before</em> the import
 * @param after               entity counts on the target <em>after</em> the import
 * @param projectsCreated     projects created by this import
 * @param projectsSkipped     projects already present (matched by slug) and left untouched
 * @param documentsCreated    documents created by this import
 * @param documentsSkipped    documents already present (matched by slug in target project) and left untouched
 * @param linksCreated        document links created by this import
 * @param linksSkipped        document links already present
 * @param hierarchyApplied    parent links applied to newly created subprojects
 * @param hierarchySkipped    parent links not applied (existing project kept as-is, or validation guard)
 * @param projectIdMapping    source project ID → target project ID
 * @param documentIdMapping   source document ID → target document ID
 * @param userMapping         source username → local user ID (only users that exist locally)
 * @param unresolvedUsers     usernames from the export with no matching local user (never auto-created)
 * @param contentVerified     imported documents whose stored content matches the export byte-for-byte
 * @param contentMismatches   slugs of documents whose stored content differs from the export
 * @param noDataLoss          true when no pre-existing entity was lost. In merge mode this is a hard
 *                            guarantee (verified by slug-set containment); in full mode it means no
 *                            <em>unintended</em> loss — the wipe itself was explicitly confirmed and is
 *                            documented in {@link #dataLossNote}
 * @param dataLossNote        human-readable explanation of the data-loss check outcome
 * @param backfillStarted     true when an async embedding backfill was started for newly created documents
 * @param warnings            non-fatal issues encountered during the import
 */
public record ImportResultDTO(
        String mode,
        String importedAt,
        Counts before,
        Counts after,
        long projectsCreated,
        long projectsSkipped,
        long documentsCreated,
        long documentsSkipped,
        long linksCreated,
        long linksSkipped,
        long hierarchyApplied,
        long hierarchySkipped,
        Map<Long, Long> projectIdMapping,
        Map<Long, Long> documentIdMapping,
        Map<String, Long> userMapping,
        List<String> unresolvedUsers,
        long contentVerified,
        List<String> contentMismatches,
        boolean noDataLoss,
        String dataLossNote,
        boolean backfillStarted,
        List<String> warnings
) {

    /** Entity counts for the three migrated entity types. */
    public record Counts(long projects, long documents, long documentLinks) {
    }
}
