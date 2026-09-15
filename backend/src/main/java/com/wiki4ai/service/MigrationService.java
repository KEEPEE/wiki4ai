package com.wiki4ai.service;

import com.wiki4ai.dto.ImportResultDTO;
import com.wiki4ai.dto.IntegrityReportDTO;
import com.wiki4ai.dto.MigrationExportDTO;
import com.wiki4ai.exception.BadRequestException;
import com.wiki4ai.model.Document;
import com.wiki4ai.model.Project;
import com.wiki4ai.model.Role;
import com.wiki4ai.model.User;
import com.wiki4ai.repository.DocumentRepository;
import com.wiki4ai.repository.ProjectPermissionRepository;
import com.wiki4ai.repository.ProjectRepository;
import com.wiki4ai.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Instance-wide data migration between wiki4ai instances (WIKI4AI-41 + WIKI4AI-42,
 * epic WIKI4AI-28).
 *
 * <p><b>Why API-level JSON and not a SQL dump:</b> the two production/dev instances
 * run different database schemas (.4: V1–V9, .219: V10 with pgvector embeddings), so
 * a {@code pg_dump} restore is not schema-compatible. The export archive is therefore
 * pure portable content (projects + hierarchy, documents, document links) and the
 * import maps old→new IDs on the target side. Embeddings are intentionally not part
 * of the archive — on a pgvector instance newly imported documents are re-embedded
 * by the standard async backfill.</p>
 *
 * <p><b>Modes:</b></p>
 * <ul>
 *   <li>{@code merge} (default, safe): creates only what is missing. Identity = project
 *       slug / (projectSlug, documentSlug). Existing entities are never modified or
 *       deleted — re-running the same import is a no-op (idempotent).</li>
 *   <li>{@code full}: wipes ALL wiki content on the target (projects, documents, links,
 *       project permissions — users/tokens/vault stay) and recreates everything from the
 *       archive. Requires an explicit {@code confirmFullRestore=true} confirmation.</li>
 * </ul>
 *
 * <p><b>No-data-loss guarantee (merge):</b> verified by identity-key containment —
 * every project slug and (projectSlug, documentSlug) pair present before the import
 * must still be present afterwards; the result reports {@code noDataLoss=false} otherwise.</p>
 *
 * <p>Known limitation: original createdAt/updatedAt timestamps are not preserved
 * (JPA lifecycle callbacks stamp them on creation); content, slugs and hierarchy are.</p>
 */
@Service
public class MigrationService {

    private static final Logger log = LoggerFactory.getLogger(MigrationService.class);

    public static final String MODE_MERGE = "merge";
    public static final String MODE_FULL = "full";

    private final ProjectRepository projectRepository;
    private final DocumentRepository documentRepository;
    private final UserRepository userRepository;
    private final ProjectPermissionRepository projectPermissionRepository;
    private final PermissionService permissionService;
    private final IntegrityService integrityService;
    private final EmbeddingClient embeddingClient;
    private final EmbeddingService embeddingService;

    public MigrationService(ProjectRepository projectRepository,
                            DocumentRepository documentRepository,
                            UserRepository userRepository,
                            ProjectPermissionRepository projectPermissionRepository,
                            PermissionService permissionService,
                            IntegrityService integrityService,
                            EmbeddingClient embeddingClient,
                            EmbeddingService embeddingService) {
        this.projectRepository = projectRepository;
        this.documentRepository = documentRepository;
        this.userRepository = userRepository;
        this.projectPermissionRepository = projectPermissionRepository;
        this.permissionService = permissionService;
        this.integrityService = integrityService;
        this.embeddingClient = embeddingClient;
        this.embeddingService = embeddingService;
    }

    /**
     * Verify the given username belongs to an ADMIN user. Testable variant of the
     * SecurityContext-based check (the controller extracts the username).
     */
    public void verifyAdminRole(String username) {
        if (username == null || username.isBlank()
                || "anonymous".equals(username) || "anonymousUser".equals(username)) {
            throw new SecurityException("Authentication required. Please provide a valid JWT token.");
        }
        User currentUser = userRepository.findByUsername(username)
                .orElseThrow(() -> new SecurityException("Authenticated user not found in database"));
        if (currentUser.getRole() != Role.ADMIN) {
            throw new SecurityException("Admin access required. Current role: " + currentUser.getRole());
        }
    }

    // ==================== export ====================

    /**
     * Build the JSON export archive of this instance: all projects (with parent
     * hierarchy by source ID), all documents (title + body + slug), all document
     * links, and the usernames present here (no secrets).
     */
    @Transactional(readOnly = true)
    public MigrationExportDTO export() {
        List<Project> projects = new ArrayList<>(projectRepository.findAll());
        projects.sort(Comparator.comparing(Project::getId, Comparator.nullsLast(Comparator.naturalOrder())));

        List<String> users = userRepository.findAll().stream()
                .map(User::getUsername)
                .filter(u -> u != null && !u.isBlank())
                .sorted()
                .toList();

        List<MigrationExportDTO.ProjectEntry> projectEntries = new ArrayList<>();
        for (Project p : projects) {
            projectEntries.add(new MigrationExportDTO.ProjectEntry(
                    p.getId(),
                    p.getName(),
                    p.getSlug(),
                    p.getDescription(),
                    p.getParent() != null ? p.getParent().getId() : null,
                    iso(p.getCreatedAt()),
                    iso(p.getUpdatedAt())
            ));
        }

        List<MigrationExportDTO.DocumentEntry> documentEntries = new ArrayList<>();
        for (Project p : projects) {
            if (p.getId() == null) {
                continue;
            }
            List<Document> docs = new ArrayList<>(documentRepository.findByProjectId(p.getId()));
            docs.sort(Comparator.comparing(Document::getId, Comparator.nullsLast(Comparator.naturalOrder())));
            for (Document d : docs) {
                documentEntries.add(new MigrationExportDTO.DocumentEntry(
                        d.getId(),
                        p.getId(),
                        d.getTitle(),
                        d.getSlug(),
                        d.getContent(),
                        iso(d.getCreatedAt()),
                        iso(d.getUpdatedAt())
                ));
            }
        }

        Set<String> seenPairs = new TreeSet<>();
        List<MigrationExportDTO.LinkEntry> linkEntries = new ArrayList<>();
        for (Project p : projects) {
            if (p.getId() == null) {
                continue;
            }
            for (Document d : documentRepository.findByProjectId(p.getId())) {
                if (d.getId() == null) {
                    continue;
                }
                for (Document target : d.getLinkedDocuments()) {
                    if (target.getId() == null) {
                        continue;
                    }
                    String pair = d.getId() + "->" + target.getId();
                    if (seenPairs.add(pair)) {
                        linkEntries.add(new MigrationExportDTO.LinkEntry(d.getId(), target.getId()));
                    }
                }
            }
        }

        return new MigrationExportDTO(
                MigrationExportDTO.CURRENT_FORMAT_VERSION,
                Instant.now().toString(),
                users,
                projectEntries,
                documentEntries,
                linkEntries
        );
    }

    // ==================== import ====================

    /**
     * Import an export archive into this instance.
     *
     * @param exportArchive       the archive (exact output of {@link #export()})
     * @param mode                {@code merge} (default) or {@code full}; anything else is rejected
     * @param confirmFullRestore  must be {@code true} when mode=full, otherwise the import is refused
     * @param importingUsername   authenticated caller (must be ADMIN — verified by the controller)
     * @return detailed result with ID mapping and integrity report (before/after + content verification)
     */
    @Transactional
    public ImportResultDTO importData(MigrationExportDTO exportArchive, String mode,
                                      Boolean confirmFullRestore, String importingUsername) {
        validateArchive(exportArchive);

        boolean full = MODE_FULL.equalsIgnoreCase(mode == null ? null : mode.trim());
        if (mode != null && !mode.isBlank() && !MODE_MERGE.equalsIgnoreCase(mode.trim()) && !full) {
            throw new BadRequestException("Unknown import mode: '" + mode + "'. Use 'merge' or 'full'.");
        }
        if (full && !Boolean.TRUE.equals(confirmFullRestore)) {
            throw new BadRequestException(
                    "Import mode 'full' wipes ALL wiki content on this instance (projects, documents, links). "
                            + "Re-send the request with confirmFullRestore=true to confirm.");
        }

        // Integrity snapshot BEFORE anything changes (counts + identity keys).
        IntegrityReportDTO before = integrityService.generateReport();
        IntegrityService.KeySnapshot beforeKeys = integrityService.snapshotKeys();

        List<String> warnings = new ArrayList<>();

        // 1) User mapping by username — never creates users (no duplicates, no secrets).
        Map<String, Long> userMapping = new LinkedHashMap<>();
        List<String> unresolvedUsers = new ArrayList<>();
        for (String username : exportArchive.users() == null ? List.<String>of() : exportArchive.users()) {
            if (username == null || username.isBlank()) {
                continue;
            }
            User local = userRepository.findByUsername(username).orElse(null);
            if (local != null) {
                userMapping.put(username, local.getId());
            } else {
                unresolvedUsers.add(username);
                warnings.add("User '" + username + "' from the export does not exist on this instance "
                        + "(not auto-created; permissions for it cannot be mapped).");
            }
        }

        // 2) Full-restore wipe (wiki content only; users/tokens/vault are instance-local and stay).
        if (full) {
            wipeWikiContent();
        }

        // 3) Projects — pass 1: match by slug or create.
        Map<Long, Long> projectIdMap = new LinkedHashMap<>();
        Set<Long> createdProjectSourceIds = new HashSet<>();
        long projectsCreated = 0;
        long projectsSkipped = 0;
        for (MigrationExportDTO.ProjectEntry e : exportArchive.projects()) {
            Optional<Project> existing = projectRepository.findBySlug(e.slug());
            if (existing.isPresent()) {
                projectIdMap.put(e.id(), existing.get().getId());
                projectsSkipped++;
            } else {
                Project p = Project.builder()
                        .name(e.name())
                        .description(e.description())
                        .build();
                p.setSlug(e.slug()); // explicit source slug (bypasses auto-generation)
                Project saved = projectRepository.save(p);
                projectIdMap.put(e.id(), saved.getId());
                createdProjectSourceIds.add(e.id());
                projectsCreated++;
                grantManageToImporter(importingUsername, saved.getId(), warnings);
            }
        }

        // 4) Projects — pass 2: apply parent links (only to newly created projects;
        //    existing projects keep their current hierarchy — non-destructive merge).
        long hierarchyApplied = 0;
        long hierarchySkipped = 0;
        for (MigrationExportDTO.ProjectEntry e : exportArchive.projects()) {
            if (e.parentId() == null || !createdProjectSourceIds.contains(e.id())) {
                continue;
            }
            Long childTargetId = projectIdMap.get(e.id());
            Long parentTargetId = projectIdMap.get(e.parentId());
            Project child = childTargetId == null ? null : projectRepository.findById(childTargetId).orElse(null);
            Project parent = parentTargetId == null ? null : projectRepository.findById(parentTargetId).orElse(null);
            if (child == null || parent == null) {
                hierarchySkipped++;
                warnings.add("Parent link for project '" + e.slug() + "' skipped: missing mapping.");
                continue;
            }
            if (!isSafeParent(child, parent)) {
                hierarchySkipped++;
                warnings.add("Parent link for project '" + e.slug() + "' under '" + parent.getSlug()
                        + "' skipped (cycle or depth > " + Project.MAX_HIERARCHY_DEPTH + ").");
                continue;
            }
            child.setParent(parent);
            projectRepository.save(child);
            hierarchyApplied++;
        }

        // 5) Documents — match by slug in target project (then title), else create.
        Map<Long, Long> documentIdMap = new LinkedHashMap<>();
        long documentsCreated = 0;
        long documentsSkipped = 0;
        for (MigrationExportDTO.DocumentEntry e : exportArchive.documents()) {
            Long targetProjectId = projectIdMap.get(e.projectId());
            if (targetProjectId == null) {
                warnings.add("Document '" + e.slug() + "' skipped: its project (source id "
                        + e.projectId() + ") is not part of the archive.");
                continue;
            }
            Optional<Document> existing = documentRepository.findBySlugAndProjectId(e.slug(), targetProjectId);
            if (existing.isEmpty()) {
                existing = documentRepository.findByProjectIdAndTitle(targetProjectId, e.title());
            }
            if (existing.isPresent()) {
                documentIdMap.put(e.id(), existing.get().getId());
                documentsSkipped++;
                continue;
            }
            Project targetProject = projectRepository.findById(targetProjectId)
                    .orElseThrow(() -> new IllegalStateException("Target project vanished: id=" + targetProjectId));
            Document d = Document.builder()
                    .title(e.title())
                    .content(e.content())
                    .project(targetProject)
                    .build();
            d.setSlug(e.slug()); // explicit source slug (bypasses auto-generation)
            Document saved = documentRepository.save(d);
            documentIdMap.put(e.id(), saved.getId());
            documentsCreated++;
        }

        // 6) Document links — recreate via the mapped IDs, idempotent.
        long linksCreated = 0;
        long linksSkipped = 0;
        for (MigrationExportDTO.LinkEntry e : exportArchive.links()) {
            Long sourceTargetId = documentIdMap.get(e.sourceDocumentId());
            Long targetTargetId = documentIdMap.get(e.targetDocumentId());
            if (sourceTargetId == null || targetTargetId == null) {
                continue; // both ends must be mapped
            }
            Document sourceDoc = documentRepository.findById(sourceTargetId).orElse(null);
            Document targetDoc = documentRepository.findById(targetTargetId).orElse(null);
            if (sourceDoc == null || targetDoc == null) {
                continue;
            }
            boolean alreadyLinked = sourceDoc.getLinkedDocuments().stream()
                    .anyMatch(t -> t.getId() != null && t.getId().equals(targetTargetId));
            if (alreadyLinked) {
                linksSkipped++;
                continue;
            }
            sourceDoc.addLinkedDocument(targetDoc);
            documentRepository.save(sourceDoc);
            linksCreated++;
        }

        // 7) Integrity AFTER + content verification of everything that was imported.
        IntegrityReportDTO after = integrityService.generateReport();
        IntegrityService.KeySnapshot afterKeys = integrityService.snapshotKeys();

        long contentVerified = 0;
        List<String> contentMismatches = new ArrayList<>();
        for (MigrationExportDTO.DocumentEntry e : exportArchive.documents()) {
            Long targetDocId = documentIdMap.get(e.id());
            if (targetDocId == null) {
                continue;
            }
            Document local = documentRepository.findById(targetDocId).orElse(null);
            if (local == null) {
                contentMismatches.add(e.slug());
                continue;
            }
            String expected = e.content() == null ? "" : e.content();
            String actual = local.getContent() == null ? "" : local.getContent();
            if (expected.equals(actual)) {
                contentVerified++;
            } else {
                contentMismatches.add(e.slug());
                warnings.add("Content mismatch for document '" + e.slug() + "' after import.");
            }
        }

        boolean noDataLoss;
        String dataLossNote;
        if (full) {
            noDataLoss = true;
            dataLossNote = "Full restore: pre-existing wiki content was intentionally wiped "
                    + "(explicitly confirmed). The before/after delta is expected and documented.";
        } else {
            noDataLoss = afterKeys.containsAll(beforeKeys);
            dataLossNote = noDataLoss
                    ? "Merge import: every pre-existing project and document is still present — no data loss."
                    : "DATA LOSS DETECTED in merge mode: some pre-existing entities are missing. "
                            + "Roll back from the pre-import backup immediately!";
            if (!noDataLoss) {
                log.error("Merge import lost pre-existing entities on this instance");
            }
        }

        // 8) Embeddings: the archive never carries vectors — let the target's sidecar
        //    re-embed what was newly created (async, single-flight, idempotent).
        boolean backfillStarted = false;
        if (documentsCreated > 0 && embeddingClient.isAvailable()) {
            backfillStarted = embeddingService.startBackfill(false);
        }

        log.info("Migration import finished: mode={}, projects={}/{} created/skipped, documents={}/{} "
                        + "created/skipped, links={}/{} created/skipped, contentVerified={}/{}, noDataLoss={}",
                full ? MODE_FULL : MODE_MERGE, projectsCreated, projectsSkipped,
                documentsCreated, documentsSkipped, linksCreated, linksSkipped,
                contentVerified, exportArchive.documents().size(), noDataLoss);

        return new ImportResultDTO(
                full ? MODE_FULL : MODE_MERGE,
                Instant.now().toString(),
                toCounts(before),
                toCounts(after),
                projectsCreated,
                projectsSkipped,
                documentsCreated,
                documentsSkipped,
                linksCreated,
                linksSkipped,
                hierarchyApplied,
                hierarchySkipped,
                projectIdMap,
                documentIdMap,
                userMapping,
                unresolvedUsers,
                contentVerified,
                contentMismatches,
                noDataLoss,
                dataLossNote,
                backfillStarted,
                warnings
        );
    }

    // ==================== helpers ====================

    private void validateArchive(MigrationExportDTO archive) {
        if (archive == null) {
            throw new BadRequestException("Export archive is empty.");
        }
        if (archive.formatVersion() != MigrationExportDTO.CURRENT_FORMAT_VERSION) {
            throw new BadRequestException("Unsupported export format version: " + archive.formatVersion()
                    + " (this instance supports " + MigrationExportDTO.CURRENT_FORMAT_VERSION + ").");
        }
        if (archive.projects() == null || archive.documents() == null || archive.links() == null) {
            throw new BadRequestException("Malformed export archive: projects/documents/links sections are required.");
        }
    }

    /**
     * Wipe all wiki content: project permissions, documents (detached from their
     * projects first — WIKI4AI-37 regression guard against flush-time PERSIST_ON_FLUSH
     * un-scheduling deletions), then projects. Users, tokens and vault entries stay.
     */
    private void wipeWikiContent() {
        List<Project> all = projectRepository.findAll();
        List<Document> allDocuments = new ArrayList<>();
        for (Project p : all) {
            List<Document> docs = new ArrayList<>(p.getDocuments());
            for (Document d : docs) {
                p.removeDocument(d);
                allDocuments.add(d);
            }
        }
        projectPermissionRepository.deleteAll();
        if (!allDocuments.isEmpty()) {
            documentRepository.deleteAll(allDocuments);
            documentRepository.flush();
        }
        if (!all.isEmpty()) {
            projectRepository.deleteAll(all);
            projectRepository.flush();
        }
    }

    /** Cycle + depth guard before linking {@code child} under {@code parent}. */
    private boolean isSafeParent(Project child, Project parent) {
        if (child.getId() == null || parent.getId() == null || child.getId().equals(parent.getId())) {
            return false;
        }
        Set<Long> seen = new HashSet<>();
        Project cursor = parent;
        while (cursor != null && cursor.getId() != null) {
            if (!seen.add(cursor.getId())) {
                return false; // cycle in existing data — defensive
            }
            if (cursor.getId().equals(child.getId())) {
                return false; // would create a cycle
            }
            cursor = cursor.getParent();
        }
        return parent.getDepth() + 1 <= Project.MAX_HIERARCHY_DEPTH;
    }

    private void grantManageToImporter(String importingUsername, Long projectId, List<String> warnings) {
        if (importingUsername == null || importingUsername.isBlank()
                || "anonymous".equals(importingUsername) || "anonymousUser".equals(importingUsername)) {
            return; // test mode without authentication — nothing to grant
        }
        try {
            permissionService.grantManageToCreator(importingUsername, projectId);
        } catch (Exception e) {
            warnings.add("Could not grant MANAGE on imported project id=" + projectId + ": " + e.getMessage());
        }
    }

    private static ImportResultDTO.Counts toCounts(IntegrityReportDTO report) {
        return new ImportResultDTO.Counts(
                report.counts().getOrDefault("projects", 0L),
                report.counts().getOrDefault("documents", 0L),
                report.counts().getOrDefault("documentLinks", 0L)
        );
    }

    private static String iso(LocalDateTime time) {
        return time == null ? null : time.atZone(ZoneOffset.UTC).toInstant().toString();
    }
}
