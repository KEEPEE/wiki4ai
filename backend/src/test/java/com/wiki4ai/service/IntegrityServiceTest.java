package com.wiki4ai.service;

import com.wiki4ai.dto.IntegrityReportDTO;
import com.wiki4ai.model.Document;
import com.wiki4ai.model.Project;
import com.wiki4ai.repository.ApiTokenRepository;
import com.wiki4ai.repository.DocumentRepository;
import com.wiki4ai.repository.ProjectPermissionRepository;
import com.wiki4ai.repository.ProjectRepository;
import com.wiki4ai.repository.RefreshTokenRepository;
import com.wiki4ai.repository.UserRepository;
import com.wiki4ai.repository.VaultEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * Unit tests for {@link IntegrityService} (WIKI4AI-42, epic WIKI4AI-28):
 * per-table counts and canonical (instance-independent) content checksums.
 */
@ExtendWith(MockitoExtension.class)
class IntegrityServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private ProjectPermissionRepository projectPermissionRepository;

    @Mock
    private VaultEntryRepository vaultEntryRepository;

    @Mock
    private ApiTokenRepository apiTokenRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @InjectMocks
    private IntegrityService integrityService;

    private Project alpha;
    private Project beta;
    private Document docOne;
    private Document docTwo;

    @BeforeEach
    void setUp() {
        alpha = Project.builder().name("Alpha").description("root").build();
        alpha.setId(1L);
        alpha.setSlug("alpha");

        beta = Project.builder().name("Beta").description(null).build();
        beta.setId(2L);
        beta.setSlug("beta");

        docOne = Document.builder().title("Doc One").content("Hello world").project(alpha).build();
        docOne.setId(10L);
        docOne.setSlug("doc-one");

        docTwo = Document.builder().title("Doc Two").content("Second document body").project(beta).build();
        docTwo.setId(11L);
        docTwo.setSlug("doc-two");
    }

    @Test
    @DisplayName("report contains per-table counts and total content length")
    void reportCounts() {
        given(projectRepository.findAll()).willReturn(List.of(alpha, beta));
        given(documentRepository.findAll()).willReturn(List.of(docOne, docTwo));
        given(userRepository.count()).willReturn(4L);
        given(documentRepository.countLinks()).willReturn(248L);
        given(projectPermissionRepository.count()).willReturn(10L);
        given(vaultEntryRepository.count()).willReturn(88L);
        given(apiTokenRepository.count()).willReturn(2L);
        given(refreshTokenRepository.count()).willReturn(3L);

        IntegrityReportDTO report = integrityService.generateReport();

        assertThat(report.counts())
                .containsEntry("users", 4L)
                .containsEntry("projects", 2L)
                .containsEntry("documents", 2L)
                .containsEntry("documentLinks", 248L)
                .containsEntry("projectPermissions", 10L)
                .containsEntry("vaultEntries", 88L)
                .containsEntry("apiTokens", 2L)
                .containsEntry("refreshTokens", 3L);
        // "Hello world" (11) + "Second document body" (20)
        assertThat(report.documentTotalLength()).isEqualTo(31L);
        assertThat(report.generatedAt()).isNotBlank();
    }

    @Test
    @DisplayName("documents checksum is canonical: independent of ID order and values")
    void documentsChecksumCanonical() {
        // Same content, different IDs and list order → identical checksum
        Document otherOne = Document.builder().title("Doc One").content("Hello world").project(alpha).build();
        otherOne.setId(999L);
        otherOne.setSlug("doc-one");
        Document otherTwo = Document.builder().title("Doc Two").content("Second document body").project(beta).build();
        otherTwo.setId(1000L);
        otherTwo.setSlug("doc-two");

        String checksumA = integrityService.computeDocumentsChecksum(List.of(docOne, docTwo));
        String checksumB = integrityService.computeDocumentsChecksum(List.of(otherTwo, otherOne));

        assertThat(checksumB).isEqualTo(checksumA);
    }

    @Test
    @DisplayName("documents checksum changes when content changes")
    void documentsChecksumDetectsContentChange() {
        Document tampered = Document.builder().title("Doc One").content("Hello world TAMPERED").project(alpha).build();
        tampered.setId(10L);
        tampered.setSlug("doc-one");

        String original = integrityService.computeDocumentsChecksum(List.of(docOne, docTwo));
        String changed = integrityService.computeDocumentsChecksum(List.of(tampered, docTwo));

        assertThat(changed).isNotEqualTo(original);
    }

    @Test
    @DisplayName("projects checksum is canonical and detects changes")
    void projectsChecksum() {
        Project otherAlpha = Project.builder().name("Alpha").description("root").build();
        otherAlpha.setId(77L);
        otherAlpha.setSlug("alpha");
        Project otherBeta = Project.builder().name("Beta").description(null).build();
        otherBeta.setId(78L);
        otherBeta.setSlug("beta");

        assertThat(integrityService.computeProjectsChecksum(List.of(otherBeta, otherAlpha)))
                .isEqualTo(integrityService.computeProjectsChecksum(List.of(alpha, beta)));

        Project renamed = Project.builder().name("Alpha Renamed").description("root").build();
        renamed.setId(1L);
        renamed.setSlug("alpha");
        assertThat(integrityService.computeProjectsChecksum(List.of(renamed, beta)))
                .isNotEqualTo(integrityService.computeProjectsChecksum(List.of(alpha, beta)));
    }

    @Test
    @DisplayName("key snapshot containment detects missing entities")
    void keySnapshotContainment() {
        IntegrityService.KeySnapshot before = new IntegrityService.KeySnapshot(
                java.util.Set.of("alpha", "beta"),
                java.util.Set.of("alpha/doc-one", "beta/doc-two"));

        IntegrityService.KeySnapshot afterAllPresent = new IntegrityService.KeySnapshot(
                java.util.Set.of("alpha", "beta", "gamma"),
                java.util.Set.of("alpha/doc-one", "beta/doc-two", "gamma/doc-three"));
        assertThat(afterAllPresent.containsAll(before)).isTrue();

        IntegrityService.KeySnapshot afterDocMissing = new IntegrityService.KeySnapshot(
                java.util.Set.of("alpha", "beta"),
                java.util.Set.of("alpha/doc-one")); // beta/doc-two gone
        assertThat(afterDocMissing.containsAll(before)).isFalse();
    }
}
