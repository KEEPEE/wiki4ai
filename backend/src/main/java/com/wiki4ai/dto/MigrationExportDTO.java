package com.wiki4ai.dto;

import java.util.List;

/**
 * Instance-wide JSON export archive (WIKI4AI-41, epic WIKI4AI-28).
 *
 * <p>Schema-independent and version-agnostic: the archive carries only portable
 * wiki content (projects with hierarchy, documents with title/body/slug, document
 * links) plus user <em>usernames</em> for reference mapping. It deliberately does
 * NOT contain:</p>
 * <ul>
 *   <li>passwords, vault entries, API tokens or refresh tokens — instance-local secrets;</li>
 *   <li>database IDs as identity — IDs are carried only for old→new mapping inside
 *       one export; cross-instance identity is the slug (projects) and
 *       (projectSlug, documentSlug) pair (documents);</li>
 *   <li>pgvector embeddings — schema-dependent (V10+ only); imported documents are
 *       re-embedded by the target instance's backfill instead.</li>
 * </ul>
 *
 * @param formatVersion archive format version (currently {@value #CURRENT_FORMAT_VERSION})
 * @param exportedAt    ISO-8601 UTC timestamp of the export
 * @param users         usernames present on the source instance (no secrets)
 * @param projects      all projects, each with its parent reference by <em>source</em> project ID
 * @param documents     all documents, each referencing its <em>source</em> project ID
 * @param links         document→document links as (sourceDocumentId, targetDocumentId) source-ID pairs
 */
public record MigrationExportDTO(
        int formatVersion,
        String exportedAt,
        List<String> users,
        List<ProjectEntry> projects,
        List<DocumentEntry> documents,
        List<LinkEntry> links
) {

    /** Current archive format version. Import rejects unknown versions. */
    public static final int CURRENT_FORMAT_VERSION = 1;

    /** A project entry. {@code parentId} is the <em>source</em> project ID of the parent (null = root). */
    public record ProjectEntry(
            Long id,
            String name,
            String slug,
            String description,
            Long parentId,
            String createdAt,
            String updatedAt
    ) {
    }

    /** A document entry. {@code projectId} is the <em>source</em> project ID. */
    public record DocumentEntry(
            Long id,
            Long projectId,
            String title,
            String slug,
            String content,
            String createdAt,
            String updatedAt
    ) {
    }

    /** A document link as a pair of <em>source</em> document IDs. */
    public record LinkEntry(
            Long sourceDocumentId,
            Long targetDocumentId
    ) {
    }
}
