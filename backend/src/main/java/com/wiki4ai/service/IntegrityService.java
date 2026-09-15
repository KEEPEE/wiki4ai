package com.wiki4ai.service;

import com.wiki4ai.dto.IntegrityReportDTO;
import com.wiki4ai.model.Document;
import com.wiki4ai.model.Project;
import com.wiki4ai.model.Role;
import com.wiki4ai.model.User;
import com.wiki4ai.repository.ApiTokenRepository;
import com.wiki4ai.repository.DocumentRepository;
import com.wiki4ai.repository.ProjectPermissionRepository;
import com.wiki4ai.repository.ProjectRepository;
import com.wiki4ai.repository.RefreshTokenRepository;
import com.wiki4ai.repository.UserRepository;
import com.wiki4ai.repository.VaultEntryRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Integrity reporting for wiki4ai instances (WIKI4AI-42, epic WIKI4AI-28).
 *
 * <p>Produces per-table row counts plus <em>canonical</em> content checksums.
 * Canonical means the hash input is ordered by slug rather than by database ID,
 * so two instances holding identical content produce identical checksums even
 * though their auto-generated IDs differ — this is what makes cross-instance
 * before/after comparison possible.</p>
 */
@Service
public class IntegrityService {

    private final UserRepository userRepository;
    private final ProjectRepository projectRepository;
    private final DocumentRepository documentRepository;
    private final ProjectPermissionRepository projectPermissionRepository;
    private final VaultEntryRepository vaultEntryRepository;
    private final ApiTokenRepository apiTokenRepository;
    private final RefreshTokenRepository refreshTokenRepository;

    public IntegrityService(UserRepository userRepository,
                            ProjectRepository projectRepository,
                            DocumentRepository documentRepository,
                            ProjectPermissionRepository projectPermissionRepository,
                            VaultEntryRepository vaultEntryRepository,
                            ApiTokenRepository apiTokenRepository,
                            RefreshTokenRepository refreshTokenRepository) {
        this.userRepository = userRepository;
        this.projectRepository = projectRepository;
        this.documentRepository = documentRepository;
        this.projectPermissionRepository = projectPermissionRepository;
        this.vaultEntryRepository = vaultEntryRepository;
        this.apiTokenRepository = apiTokenRepository;
        this.refreshTokenRepository = refreshTokenRepository;
    }

    /** Verify the caller has the ADMIN role (same pattern as AdminService/EmbeddingService). */
    public void verifyAdminRole() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null
                || "anonymousUser".equals(authentication.getName())) {
            throw new SecurityException("Authentication required. Please provide a valid JWT token.");
        }
        String username = authentication.getName();
        User currentUser = userRepository.findByUsername(username)
                .orElseThrow(() -> new SecurityException("Authenticated user not found in database"));
        if (currentUser.getRole() != Role.ADMIN) {
            throw new SecurityException("Admin access required. Current role: " + currentUser.getRole());
        }
    }

    /** Admin-facing integrity report (verifies ADMIN role first). */
    @Transactional(readOnly = true)
    public IntegrityReportDTO getIntegrityReport() {
        verifyAdminRole();
        return generateReport();
    }

    /**
     * Compute the integrity report without an authorization check.
     * Used internally by the migration import (which performs its own admin check).
     */
    @Transactional(readOnly = true)
    public IntegrityReportDTO generateReport() {
        List<Project> projects = projectRepository.findAll();
        List<Document> documents = documentRepository.findAll();

        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("users", userRepository.count());
        counts.put("projects", (long) projects.size());
        counts.put("documents", (long) documents.size());
        counts.put("documentLinks", documentRepository.countLinks());
        counts.put("projectPermissions", projectPermissionRepository.count());
        counts.put("vaultEntries", vaultEntryRepository.count());
        counts.put("apiTokens", apiTokenRepository.count());
        counts.put("refreshTokens", refreshTokenRepository.count());

        long totalLength = 0;
        for (Document d : documents) {
            if (d.getContent() != null) {
                totalLength += d.getContent().length();
            }
        }

        return new IntegrityReportDTO(
                Instant.now().toString(),
                counts,
                computeProjectsChecksum(projects),
                computeDocumentsChecksum(documents),
                totalLength
        );
    }

    /**
     * Snapshot of identity keys for the no-data-loss guarantee: every project slug
     * and every (projectSlug, documentSlug) pair currently present on this instance.
     */
    @Transactional(readOnly = true)
    public KeySnapshot snapshotKeys() {
        Set<String> projectSlugs = new TreeSet<>();
        for (Project p : projectRepository.findAll()) {
            if (p.getSlug() != null) {
                projectSlugs.add(p.getSlug());
            }
        }
        Set<String> documentKeys = new TreeSet<>();
        for (Document d : documentRepository.findAll()) {
            String projectSlug = d.getProject() != null ? d.getProject().getSlug() : "";
            if (d.getSlug() != null) {
                documentKeys.add(projectSlug + "/" + d.getSlug());
            }
        }
        return new KeySnapshot(projectSlugs, documentKeys);
    }

    /** Identity-key snapshot used to prove that a merge import deleted nothing. */
    public record KeySnapshot(Set<String> projectSlugs, Set<String> documentKeys) {

        /** True when every key present in {@code before} is still present in {@code after}. */
        public boolean containsAll(KeySnapshot before) {
            return this.projectSlugs.containsAll(before.projectSlugs)
                    && this.documentKeys.containsAll(before.documentKeys);
        }
    }

    // ==================== canonical checksums ====================

    /**
     * MD5 over all projects ordered by slug: {@code slug|name|md5(description||'')}.
     * Instance-independent (slug-ordered, ID-free).
     */
    public String computeProjectsChecksum(List<Project> projects) {
        MessageDigest digest = md5();
        List<Project> sorted = projects.stream()
                .sorted(Comparator.comparing(p -> p.getSlug() == null ? "" : p.getSlug()))
                .toList();
        for (Project p : sorted) {
            update(digest, nz(p.getSlug()) + "|" + nz(p.getName()) + "|" + hex(md5Bytes(nz(p.getDescription()))));
        }
        return hex(digest.digest());
    }

    /**
     * MD5 over all documents ordered by (projectSlug, slug):
     * {@code projectSlug|slug|md5(content||'')}. Instance-independent (slug-ordered, ID-free).
     */
    public String computeDocumentsChecksum(List<Document> documents) {
        MessageDigest digest = md5();
        List<Document> sorted = documents.stream()
                .sorted(Comparator
                        .comparing((Document d) -> projectSlug(d))
                        .thenComparing(d -> nz(d.getSlug())))
                .toList();
        for (Document d : sorted) {
            update(digest, projectSlug(d) + "|" + nz(d.getSlug()) + "|" + hex(md5Bytes(nz(d.getContent()))));
        }
        return hex(digest.digest());
    }

    private static String projectSlug(Document d) {
        return d.getProject() != null && d.getProject().getSlug() != null ? d.getProject().getSlug() : "";
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static MessageDigest md5() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 not available", e);
        }
    }

    private static void update(MessageDigest digest, String chunk) {
        digest.update(chunk.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] md5Bytes(String s) {
        return md5().digest(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
