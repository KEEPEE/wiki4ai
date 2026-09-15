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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link MigrationService} (WIKI4AI-41, epic WIKI4AI-28) using mocked
 * repository layers: export shape, roundtrip identity, idempotency, ID mapping,
 * merge no-data-loss guarantee, full-restore confirmation and user mapping.
 */
@ExtendWith(MockitoExtension.class)
class MigrationServiceTest {

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ProjectPermissionRepository projectPermissionRepository;

    @Mock
    private PermissionService permissionService;

    @Mock
    private IntegrityService integrityService;

    @Mock
    private EmbeddingClient embeddingClient;

    @Mock
    private EmbeddingService embeddingService;

    @InjectMocks
    private MigrationService migrationService;

    // Source-side fixtures (as if read from the source instance)
    private Project sourceAlpha;
    private Project sourceBeta;
    private Document sourceDocOne;
    private Document sourceDocTwo;


    @BeforeEach
    void setUp() {
        sourceAlpha = Project.builder().name("Alpha").description("root project").build();
        sourceAlpha.setId(1L);
        sourceAlpha.setSlug("alpha");

        sourceBeta = Project.builder().name("Beta").description("child project").build();
        sourceBeta.setId(2L);
        sourceBeta.setSlug("beta");
        sourceBeta.setParent(sourceAlpha);

        sourceDocOne = Document.builder().title("Doc One").content("Hello world").project(sourceAlpha).build();
        sourceDocOne.setId(10L);
        sourceDocOne.setSlug("doc-one");

        sourceDocTwo = Document.builder().title("Doc Two").content("Second document body").project(sourceBeta).build();
        sourceDocTwo.setId(11L);
        sourceDocTwo.setSlug("doc-two");

        sourceDocOne.addLinkedDocument(sourceDocTwo);
    }

    private MigrationExportDTO sampleArchive() {
        return new MigrationExportDTO(
                MigrationExportDTO.CURRENT_FORMAT_VERSION,
                "2026-09-15T12:00:00Z",
                List.of("alice", "bob"),
                List.of(
                        new MigrationExportDTO.ProjectEntry(1L, "Alpha", "alpha", "root project", null, null, null),
                        new MigrationExportDTO.ProjectEntry(2L, "Beta", "beta", "child project", 1L, null, null)
                ),
                List.of(
                        new MigrationExportDTO.DocumentEntry(10L, 1L, "Doc One", "doc-one", "Hello world", null, null),
                        new MigrationExportDTO.DocumentEntry(11L, 2L, "Doc Two", "doc-two", "Second document body", null, null)
                ),
                List.of(new MigrationExportDTO.LinkEntry(10L, 11L))
        );
    }

    private IntegrityReportDTO report(long projects, long documents, long links) {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("projects", projects);
        counts.put("documents", documents);
        counts.put("documentLinks", links);
        return new IntegrityReportDTO("2026-09-15T12:00:00Z", counts, "psum", "dsum", 0L);
    }

    private IntegrityService.KeySnapshot snapshot(String... projectSlugs) {
        return new IntegrityService.KeySnapshot(new TreeSet<>(List.of(projectSlugs)), Set.of());
    }

    /** Stub an empty target: nothing exists yet, saves get sequential IDs. */
    private void stubEmptyTarget(long firstProjectId, long firstDocumentId) {
        given(projectRepository.findBySlug(anyString())).willReturn(Optional.empty());
        final long[] nextProject = {firstProjectId};
        given(projectRepository.save(any(Project.class))).willAnswer(inv -> {
            Project p = inv.getArgument(0);
            p.setId(nextProject[0]++);
            return p;
        });
        given(documentRepository.findBySlugAndProjectId(anyString(), anyLong())).willReturn(Optional.empty());
        given(documentRepository.findByProjectIdAndTitle(anyLong(), anyString())).willReturn(Optional.empty());
        final long[] nextDoc = {firstDocumentId};
        given(documentRepository.save(any(Document.class))).willAnswer(inv -> {
            Document d = inv.getArgument(0);
            d.setId(nextDoc[0]++);
            return d;
        });
    }

    // ==================== export ====================

    @Nested
    @DisplayName("export")
    class ExportTests {

        @Test
        @DisplayName("produces the full archive: hierarchy, documents, links, usernames")
        void producesFullArchive() {
            given(projectRepository.findAll()).willReturn(List.of(sourceAlpha, sourceBeta));
            given(documentRepository.findByProjectId(1L)).willReturn(List.of(sourceDocOne));
            given(documentRepository.findByProjectId(2L)).willReturn(List.of(sourceDocTwo));

            User alice = User.builder().username("alice").build();
            User bob = User.builder().username("bob").build();
            given(userRepository.findAll()).willReturn(List.of(bob, alice));

            MigrationExportDTO archive = migrationService.export();

            assertThat(archive.formatVersion()).isEqualTo(MigrationExportDTO.CURRENT_FORMAT_VERSION);
            assertThat(archive.users()).containsExactly("alice", "bob"); // sorted, no secrets
            assertThat(archive.projects()).hasSize(2);
            assertThat(archive.projects().get(0).slug()).isEqualTo("alpha");
            assertThat(archive.projects().get(0).parentId()).isNull();
            assertThat(archive.projects().get(1).slug()).isEqualTo("beta");
            assertThat(archive.projects().get(1).parentId()).isEqualTo(1L); // source project ID of parent
            assertThat(archive.documents()).hasSize(2);
            assertThat(archive.documents().get(0).projectId()).isEqualTo(1L);
            assertThat(archive.documents().get(0).content()).isEqualTo("Hello world");
            assertThat(archive.links()).containsExactly(new MigrationExportDTO.LinkEntry(10L, 11L));
        }

        @Test
        @DisplayName("deduplicates link pairs")
        void deduplicatesLinks() {
            given(projectRepository.findAll()).willReturn(List.of(sourceAlpha));
            // same target twice in the linked list (defensive fixture)
            sourceDocOne.addLinkedDocument(sourceDocTwo);
            given(documentRepository.findByProjectId(1L)).willReturn(List.of(sourceDocOne));
            given(userRepository.findAll()).willReturn(List.of());

            MigrationExportDTO archive = migrationService.export();

            assertThat(archive.links()).containsExactly(new MigrationExportDTO.LinkEntry(10L, 11L));
        }
    }

    // ==================== import: roundtrip identity ====================

    @Nested
    @DisplayName("import — roundtrip identity")
    class RoundtripTests {

        @Test
        @DisplayName("fresh target: creates projects (with hierarchy), documents and links; maps all IDs")
        void roundtripOnFreshTarget() {
            stubEmptyTarget(100L, 200L);
            given(projectRepository.findById(anyLong())).willAnswer(inv -> {
                Long id = inv.getArgument(0);
                Project p = Project.builder().name("p" + id).build();
                p.setId(id);
                p.setSlug(id == 100L ? "alpha" : "beta");
                return Optional.of(p);
            });
            given(documentRepository.findById(anyLong())).willAnswer(inv -> {
                Long id = inv.getArgument(0);
                Document d = Document.builder().title("d" + id)
                        .content(id == 200L ? "Hello world" : "Second document body").build();
                d.setId(id);
                d.setSlug(id == 200L ? "doc-one" : "doc-two");
                return Optional.of(d);
            });
            given(integrityService.generateReport())
                    .willReturn(report(0, 0, 0))
                    .willReturn(report(2, 2, 1));
            given(integrityService.snapshotKeys())
                    .willReturn(snapshot())
                    .willReturn(snapshot("alpha", "beta"));

            ImportResultDTO result = migrationService.importData(sampleArchive(), "merge", null, "admin");

            // projects
            assertThat(result.projectsCreated()).isEqualTo(2);
            assertThat(result.projectsSkipped()).isZero();
            assertThat(result.projectIdMapping()).containsEntry(1L, 100L).containsEntry(2L, 101L);
            // hierarchy applied to the created child
            assertThat(result.hierarchyApplied()).isEqualTo(1);
            ArgumentCaptor<Project> projectSaves = ArgumentCaptor.forClass(Project.class);
            verify(projectRepository, atLeastOnce()).save(projectSaves.capture());
            Project savedBeta = projectSaves.getAllValues().stream()
                    .filter(p -> "beta".equals(p.getSlug()))
                    .reduce((a, b) -> b) // last save = after setParent
                    .orElseThrow();
            assertThat(savedBeta.getParent()).isNotNull();
            assertThat(savedBeta.getParent().getSlug()).isEqualTo("alpha");
            // documents
            assertThat(result.documentsCreated()).isEqualTo(2);
            assertThat(result.documentsSkipped()).isZero();
            assertThat(result.documentIdMapping()).containsEntry(10L, 200L).containsEntry(11L, 201L);
            ArgumentCaptor<Document> docSaves = ArgumentCaptor.forClass(Document.class);
            verify(documentRepository, atLeastOnce()).save(docSaves.capture());
            Document savedDocOne = docSaves.getAllValues().stream()
                    .filter(d -> "doc-one".equals(d.getSlug()))
                    .findFirst().orElseThrow();
            assertThat(savedDocOne.getTitle()).isEqualTo("Doc One");
            assertThat(savedDocOne.getContent()).isEqualTo("Hello world");
            // link recreated
            assertThat(result.linksCreated()).isEqualTo(1);
            assertThat(result.linksSkipped()).isZero();
            // content verified byte-for-byte against the archive
            assertThat(result.contentVerified()).isEqualTo(2);
            assertThat(result.contentMismatches()).isEmpty();

            assertThat(result.noDataLoss()).isTrue();
            assertThat(result.before().projects()).isZero();
            assertThat(result.after().projects()).isEqualTo(2);
        }

        @Test
        @DisplayName("content of every imported document is verified against the archive")
        void verifiesContentAfterImport() {
            stubEmptyTarget(100L, 200L);
            // findById for pass-2 hierarchy + link + verification returns the saved entities
            given(projectRepository.findById(anyLong())).willAnswer(inv -> {
                Long id = inv.getArgument(0);
                Project p = Project.builder().name("p" + id).build();
                p.setId(id);
                p.setSlug(id == 100L ? "alpha" : "beta");
                return Optional.of(p);
            });
            given(documentRepository.findById(anyLong())).willAnswer(inv -> {
                Long id = inv.getArgument(0);
                Document d = Document.builder().title("d" + id).content(id == 200L ? "Hello world" : "Second document body").build();
                d.setId(id);
                d.setSlug(id == 200L ? "doc-one" : "doc-two");
                return Optional.of(d);
            });
            given(integrityService.generateReport()).willReturn(report(0, 0, 0), report(2, 2, 1));
            given(integrityService.snapshotKeys()).willReturn(snapshot(), snapshot("alpha", "beta"));

            ImportResultDTO result = migrationService.importData(sampleArchive(), "merge", null, "admin");

            assertThat(result.contentVerified()).isEqualTo(2);
            assertThat(result.contentMismatches()).isEmpty();
        }

        @Test
        @DisplayName("tampered content after import is flagged as mismatch")
        void flagsContentMismatch() {
            stubEmptyTarget(100L, 200L);
            given(projectRepository.findById(anyLong())).willAnswer(inv -> {
                Long id = inv.getArgument(0);
                Project p = Project.builder().name("p" + id).build();
                p.setId(id);
                p.setSlug(id == 100L ? "alpha" : "beta");
                return Optional.of(p);
            });
            given(documentRepository.findById(anyLong())).willAnswer(inv -> {
                Long id = inv.getArgument(0);
                Document d = Document.builder().title("d" + id).content("TAMPERED").build();
                d.setId(id);
                d.setSlug(id == 200L ? "doc-one" : "doc-two");
                return Optional.of(d);
            });
            given(integrityService.generateReport()).willReturn(report(0, 0, 0), report(2, 2, 1));
            given(integrityService.snapshotKeys()).willReturn(snapshot(), snapshot("alpha", "beta"));

            ImportResultDTO result = migrationService.importData(sampleArchive(), "merge", null, "admin");

            assertThat(result.contentVerified()).isZero();
            assertThat(result.contentMismatches()).containsExactlyInAnyOrder("doc-one", "doc-two");
            assertThat(result.warnings()).anyMatch(w -> w.contains("Content mismatch"));
        }
    }

    // ==================== import: idempotency ====================

    @Nested
    @DisplayName("import — idempotency")
    class IdempotencyTests {

        @Test
        @DisplayName("second run of the same archive is a no-op: everything skipped, nothing saved")
        void secondRunIsNoOp() {
            // Target already holds the imported data (from a previous run)
            Project existingAlpha = Project.builder().name("Alpha").build();
            existingAlpha.setId(5L);
            existingAlpha.setSlug("alpha");
            Project existingBeta = Project.builder().name("Beta").build();
            existingBeta.setId(6L);
            existingBeta.setSlug("beta");

            Document existingDocOne = Document.builder().title("Doc One").content("Hello world").project(existingAlpha).build();
            existingDocOne.setId(50L);
            existingDocOne.setSlug("doc-one");
            Document existingDocTwo = Document.builder().title("Doc Two").content("Second document body").project(existingBeta).build();
            existingDocTwo.setId(51L);
            existingDocTwo.setSlug("doc-two");
            existingDocOne.addLinkedDocument(existingDocTwo);

            given(projectRepository.findBySlug("alpha")).willReturn(Optional.of(existingAlpha));
            given(projectRepository.findBySlug("beta")).willReturn(Optional.of(existingBeta));
            given(documentRepository.findBySlugAndProjectId("doc-one", 5L)).willReturn(Optional.of(existingDocOne));
            given(documentRepository.findBySlugAndProjectId("doc-two", 6L)).willReturn(Optional.of(existingDocTwo));
            given(documentRepository.findById(50L)).willReturn(Optional.of(existingDocOne));
            given(documentRepository.findById(51L)).willReturn(Optional.of(existingDocTwo));
            given(integrityService.generateReport()).willReturn(report(2, 2, 1), report(2, 2, 1));
            given(integrityService.snapshotKeys())
                    .willReturn(snapshot("alpha", "beta"))
                    .willReturn(snapshot("alpha", "beta"));

            ImportResultDTO result = migrationService.importData(sampleArchive(), "merge", null, "admin");

            assertThat(result.projectsCreated()).isZero();
            assertThat(result.projectsSkipped()).isEqualTo(2);
            assertThat(result.documentsCreated()).isZero();
            assertThat(result.documentsSkipped()).isEqualTo(2);
            assertThat(result.linksCreated()).isZero();
            assertThat(result.linksSkipped()).isEqualTo(1);
            assertThat(result.projectIdMapping()).containsEntry(1L, 5L).containsEntry(2L, 6L);
            assertThat(result.documentIdMapping()).containsEntry(10L, 50L).containsEntry(11L, 51L);
            assertThat(result.noDataLoss()).isTrue();

            verify(projectRepository, never()).save(any(Project.class));
            verify(documentRepository, never()).save(any(Document.class));
        }
    }

    // ==================== import: no-data-loss guarantee ====================

    @Nested
    @DisplayName("import — no-data-loss guarantee (merge)")
    class NoDataLossTests {

        @Test
        @DisplayName("merge never deletes anything and passes when pre-existing keys survive")
        void mergeNeverDeletes() {
            stubEmptyTarget(100L, 200L);
            given(projectRepository.findById(anyLong())).willAnswer(inv -> {
                Long id = inv.getArgument(0);
                Project p = Project.builder().name("p" + id).build();
                p.setId(id);
                p.setSlug(id == 100L ? "alpha" : "beta");
                return Optional.of(p);
            });
            given(documentRepository.findById(anyLong())).willAnswer(inv -> {
                Long id = inv.getArgument(0);
                Document d = Document.builder().title("d" + id).content("x").build();
                d.setId(id);
                d.setSlug(id == 200L ? "doc-one" : "doc-two");
                return Optional.of(d);
            });
            // target already has an unrelated project+document that must survive
            IntegrityService.KeySnapshot before = new IntegrityService.KeySnapshot(
                    Set.of("existing-p"), Set.of("existing-p/existing-d"));
            IntegrityService.KeySnapshot after = new IntegrityService.KeySnapshot(
                    Set.of("existing-p", "alpha", "beta"), Set.of("existing-p/existing-d"));
            given(integrityService.generateReport()).willReturn(report(1, 1, 0), report(3, 3, 1));
            given(integrityService.snapshotKeys()).willReturn(before, after);

            ImportResultDTO result = migrationService.importData(sampleArchive(), "merge", null, "admin");

            assertThat(result.noDataLoss()).isTrue();
            assertThat(result.dataLossNote()).contains("no data loss");
            verify(projectRepository, never()).deleteAll(anyList());
            verify(projectRepository, never()).deleteById(anyLong());
            verify(documentRepository, never()).deleteAll(anyList());
        }

        @Test
        @DisplayName("merge reports noDataLoss=false when a pre-existing entity vanished")
        void mergeDetectsLoss() {
            stubEmptyTarget(100L, 200L);
            given(projectRepository.findById(anyLong())).willAnswer(inv -> {
                Long id = inv.getArgument(0);
                Project p = Project.builder().name("p" + id).build();
                p.setId(id);
                p.setSlug(id == 100L ? "alpha" : "beta");
                return Optional.of(p);
            });
            given(documentRepository.findById(anyLong())).willAnswer(inv -> {
                Long id = inv.getArgument(0);
                Document d = Document.builder().title("d" + id).content("x").build();
                d.setId(id);
                d.setSlug(id == 200L ? "doc-one" : "doc-two");
                return Optional.of(d);
            });
            IntegrityService.KeySnapshot before = new IntegrityService.KeySnapshot(
                    Set.of("existing-p"), Set.of("existing-p/existing-d"));
            // after: the pre-existing document is GONE
            IntegrityService.KeySnapshot after = new IntegrityService.KeySnapshot(
                    Set.of("existing-p", "alpha", "beta"), Set.of());
            given(integrityService.generateReport()).willReturn(report(1, 1, 0), report(3, 2, 1));
            given(integrityService.snapshotKeys()).willReturn(before, after);

            ImportResultDTO result = migrationService.importData(sampleArchive(), "merge", null, "admin");

            assertThat(result.noDataLoss()).isFalse();
            assertThat(result.dataLossNote()).contains("DATA LOSS DETECTED");
        }
    }

    // ==================== import: full restore ====================

    @Nested
    @DisplayName("import — full restore")
    class FullRestoreTests {

        @Test
        @DisplayName("full mode without confirmFullRestore is refused (400)")
        void fullRequiresConfirmation() {
            assertThatThrownBy(() -> migrationService.importData(sampleArchive(), "full", null, "admin"))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("confirmFullRestore=true");
            verify(projectRepository, never()).deleteAll(anyList());
        }

        @Test
        @DisplayName("full mode with confirmation wipes wiki content first, then recreates")
        void fullWipesThenRecreates() {
            // pre-existing target data to be wiped
            Project old = Project.builder().name("Old").build();
            old.setId(1L);
            old.setSlug("old");
            Document oldDoc = Document.builder().title("Old Doc").content("old content").project(old).build();
            oldDoc.setId(9L);
            oldDoc.setSlug("old-doc");
            old.addDocument(oldDoc);

            given(projectRepository.findAll()).willReturn(List.of(old));
            stubEmptyTarget(100L, 200L);
            given(projectRepository.findById(anyLong())).willAnswer(inv -> {
                Long id = inv.getArgument(0);
                Project p = Project.builder().name("p" + id).build();
                p.setId(id);
                p.setSlug(id == 100L ? "alpha" : "beta");
                return Optional.of(p);
            });
            given(documentRepository.findById(anyLong())).willAnswer(inv -> {
                Long id = inv.getArgument(0);
                Document d = Document.builder().title("d" + id).content("x").build();
                d.setId(id);
                d.setSlug(id == 200L ? "doc-one" : "doc-two");
                return Optional.of(d);
            });
            given(integrityService.generateReport()).willReturn(report(1, 1, 0), report(2, 2, 1));
            given(integrityService.snapshotKeys()).willReturn(snapshot("old"), snapshot("alpha", "beta"));

            ImportResultDTO result = migrationService.importData(sampleArchive(), "full", true, "admin");

            // wipe happened before creation
            verify(projectPermissionRepository).deleteAll();
            verify(documentRepository).deleteAll(anyList());
            ArgumentCaptor<List<Project>> wipedProjects = ArgumentCaptor.forClass(List.class);
            verify(projectRepository).deleteAll(wipedProjects.capture());
            assertThat(wipedProjects.getValue()).extracting(Project::getSlug).containsExactly("old");
            // and the archive content was recreated
            assertThat(result.mode()).isEqualTo("full");
            assertThat(result.projectsCreated()).isEqualTo(2);
            assertThat(result.documentsCreated()).isEqualTo(2);
            assertThat(result.noDataLoss()).isTrue();
            assertThat(result.dataLossNote()).contains("intentionally wiped");
        }

        @Test
        @DisplayName("unknown mode is rejected")
        void unknownModeRejected() {
            assertThatThrownBy(() -> migrationService.importData(sampleArchive(), "nuke", null, "admin"))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Unknown import mode");
        }

        @Test
        @DisplayName("unsupported archive format version is rejected")
        void unsupportedFormatVersionRejected() {
            MigrationExportDTO bad = new MigrationExportDTO(99, "t", List.of(), List.of(), List.of(), List.of());
            assertThatThrownBy(() -> migrationService.importData(bad, "merge", null, "admin"))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Unsupported export format version");
        }
    }

    // ==================== import: users ====================

    @Nested
    @DisplayName("import — user mapping")
    class UserMappingTests {

        @Test
        @DisplayName("users are mapped by username, never created (no duplicates)")
        void mapsUsersWithoutCreating() {
            stubEmptyTarget(100L, 200L);
            given(projectRepository.findById(anyLong())).willAnswer(inv -> {
                Long id = inv.getArgument(0);
                Project p = Project.builder().name("p" + id).build();
                p.setId(id);
                p.setSlug(id == 100L ? "alpha" : "beta");
                return Optional.of(p);
            });
            given(documentRepository.findById(anyLong())).willAnswer(inv -> {
                Long id = inv.getArgument(0);
                Document d = Document.builder().title("d" + id).content("x").build();
                d.setId(id);
                d.setSlug(id == 200L ? "doc-one" : "doc-two");
                return Optional.of(d);
            });
            User alice = User.builder().username("alice").build();
            alice.setId(7L);
            given(userRepository.findByUsername("alice")).willReturn(Optional.of(alice));
            given(userRepository.findByUsername("bob")).willReturn(Optional.empty());
            given(integrityService.generateReport()).willReturn(report(0, 0, 0), report(2, 2, 1));
            given(integrityService.snapshotKeys()).willReturn(snapshot(), snapshot("alpha", "beta"));

            ImportResultDTO result = migrationService.importData(sampleArchive(), "merge", null, "admin");

            assertThat(result.userMapping()).containsEntry("alice", 7L);
            assertThat(result.unresolvedUsers()).containsExactly("bob");
            assertThat(result.warnings()).anyMatch(w -> w.contains("'bob'"));
            verify(userRepository, never()).save(any(User.class));
        }
    }

    // ==================== import: hierarchy edge cases ====================

    @Nested
    @DisplayName("import — hierarchy edge cases")
    class HierarchyTests {

        @Test
        @DisplayName("parent listed after its child in the archive is still linked (two-pass)")
        void outOfOrderHierarchy() {
            MigrationExportDTO archive = new MigrationExportDTO(
                    MigrationExportDTO.CURRENT_FORMAT_VERSION,
                    "t", List.of(),
                    // child FIRST (source id 1), parent SECOND (source id 2)
                    List.of(
                            new MigrationExportDTO.ProjectEntry(1L, "Child", "child", null, 2L, null, null),
                            new MigrationExportDTO.ProjectEntry(2L, "Parent", "parent", null, null, null, null)
                    ),
                    List.of(), List.of());

            // projects-only empty target (this archive has no documents)
            given(projectRepository.findBySlug(anyString())).willReturn(Optional.empty());
            final long[] nextProject = {300L};
            given(projectRepository.save(any(Project.class))).willAnswer(inv -> {
                Project p = inv.getArgument(0);
                p.setId(nextProject[0]++);
                return p;
            });
            given(projectRepository.findById(anyLong())).willAnswer(inv -> {
                Long id = inv.getArgument(0);
                Project p = Project.builder().name("p" + id).build();
                p.setId(id);
                p.setSlug(id == 300L ? "child" : "parent");
                return Optional.of(p);
            });
            given(integrityService.generateReport()).willReturn(report(0, 0, 0), report(2, 0, 0));
            given(integrityService.snapshotKeys()).willReturn(snapshot(), snapshot("child", "parent"));

            ImportResultDTO result = migrationService.importData(archive, "merge", null, "admin");

            assertThat(result.hierarchyApplied()).isEqualTo(1);
            ArgumentCaptor<Project> saves = ArgumentCaptor.forClass(Project.class);
            verify(projectRepository, atLeastOnce()).save(saves.capture());
            Project savedChild = saves.getAllValues().stream()
                    .filter(p -> "child".equals(p.getSlug()))
                    .reduce((a, b) -> b).orElseThrow();
            assertThat(savedChild.getParent()).isNotNull();
            assertThat(savedChild.getParent().getSlug()).isEqualTo("parent");
        }

        @Test
        @DisplayName("existing (skipped) projects keep their current hierarchy — not re-parented")
        void existingProjectsNotReparsed() {
            Project existing = Project.builder().name("Beta").build();
            existing.setId(6L);
            existing.setSlug("beta");
            given(projectRepository.findBySlug("alpha")).willReturn(Optional.empty());
            given(projectRepository.findBySlug("beta")).willReturn(Optional.of(existing));
            final long[] nextProject = {100L};
            given(projectRepository.save(any(Project.class))).willAnswer(inv -> {
                Project p = inv.getArgument(0);
                p.setId(nextProject[0]++);
                return p;
            });
            given(documentRepository.findBySlugAndProjectId(anyString(), anyLong())).willReturn(Optional.empty());
            given(documentRepository.findByProjectIdAndTitle(anyLong(), anyString())).willReturn(Optional.empty());
            given(projectRepository.findById(anyLong())).willAnswer(inv -> {
                Long id = inv.getArgument(0);
                Project p = Project.builder().name("p" + id).build();
                p.setId(id);
                p.setSlug(id == 100L ? "alpha" : "beta");
                return Optional.of(p);
            });
            final long[] nextDoc = {200L};
            given(documentRepository.save(any(Document.class))).willAnswer(inv -> {
                Document d = inv.getArgument(0);
                d.setId(nextDoc[0]++);
                return d;
            });
            given(integrityService.generateReport()).willReturn(report(1, 0, 0), report(2, 2, 1));
            given(integrityService.snapshotKeys()).willReturn(snapshot("beta"), snapshot("alpha", "beta"));

            ImportResultDTO result = migrationService.importData(sampleArchive(), "merge", null, "admin");

            assertThat(result.projectsSkipped()).isEqualTo(1);
            assertThat(result.hierarchyApplied()).isZero(); // beta existed → its parent is not touched
            verify(projectRepository, never()).save(eq(existing));
        }
    }

    // ==================== import: embeddings backfill ====================

    @Nested
    @DisplayName("import — embedding backfill trigger")
    class BackfillTests {

        @Test
        @DisplayName("backfill starts when documents were created and the sidecar is available")
        void startsBackfillWhenDocsCreated() {
            stubEmptyTarget(100L, 200L);
            given(projectRepository.findById(anyLong())).willAnswer(inv -> {
                Long id = inv.getArgument(0);
                Project p = Project.builder().name("p" + id).build();
                p.setId(id);
                p.setSlug(id == 100L ? "alpha" : "beta");
                return Optional.of(p);
            });
            given(documentRepository.findById(anyLong())).willAnswer(inv -> {
                Long id = inv.getArgument(0);
                Document d = Document.builder().title("d" + id).content("x").build();
                d.setId(id);
                d.setSlug(id == 200L ? "doc-one" : "doc-two");
                return Optional.of(d);
            });
            given(embeddingClient.isAvailable()).willReturn(true);
            given(embeddingService.startBackfill(false)).willReturn(true);
            given(integrityService.generateReport()).willReturn(report(0, 0, 0), report(2, 2, 1));
            given(integrityService.snapshotKeys()).willReturn(snapshot(), snapshot("alpha", "beta"));

            ImportResultDTO result = migrationService.importData(sampleArchive(), "merge", null, "admin");

            assertThat(result.backfillStarted()).isTrue();
            verify(embeddingService).startBackfill(false);
        }

        @Test
        @DisplayName("no backfill when nothing was created (idempotent re-run)")
        void noBackfillWhenNothingCreated() {
            Project existingAlpha = Project.builder().name("Alpha").build();
            existingAlpha.setId(5L);
            existingAlpha.setSlug("alpha");
            Project existingBeta = Project.builder().name("Beta").build();
            existingBeta.setId(6L);
            existingBeta.setSlug("beta");
            Document existingDocOne = Document.builder().title("Doc One").content("Hello world").project(existingAlpha).build();
            existingDocOne.setId(50L);
            existingDocOne.setSlug("doc-one");
            Document existingDocTwo = Document.builder().title("Doc Two").content("Second document body").project(existingBeta).build();
            existingDocTwo.setId(51L);
            existingDocTwo.setSlug("doc-two");
            existingDocOne.addLinkedDocument(existingDocTwo);

            given(projectRepository.findBySlug("alpha")).willReturn(Optional.of(existingAlpha));
            given(projectRepository.findBySlug("beta")).willReturn(Optional.of(existingBeta));
            given(documentRepository.findBySlugAndProjectId("doc-one", 5L)).willReturn(Optional.of(existingDocOne));
            given(documentRepository.findBySlugAndProjectId("doc-two", 6L)).willReturn(Optional.of(existingDocTwo));
            given(documentRepository.findById(50L)).willReturn(Optional.of(existingDocOne));
            given(documentRepository.findById(51L)).willReturn(Optional.of(existingDocTwo));
            given(integrityService.generateReport()).willReturn(report(2, 2, 1), report(2, 2, 1));
            given(integrityService.snapshotKeys()).willReturn(snapshot("alpha", "beta"), snapshot("alpha", "beta"));

            ImportResultDTO result = migrationService.importData(sampleArchive(), "merge", null, "admin");

            assertThat(result.backfillStarted()).isFalse();
            verify(embeddingService, never()).startBackfill(anyBoolean());
        }
    }

    // ==================== admin check ====================

    @Test
    @DisplayName("verifyAdminRole: rejects anonymous, unknown user and non-admin")
    void verifyAdminRoleRejects() {
        assertThatThrownBy(() -> migrationService.verifyAdminRole("anonymous"))
                .isInstanceOf(SecurityException.class);
        given(userRepository.findByUsername("ghost")).willReturn(Optional.empty());
        assertThatThrownBy(() -> migrationService.verifyAdminRole("ghost"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("not found in database");

        User plain = User.builder().username("plain").role(Role.USER).build();
        given(userRepository.findByUsername("plain")).willReturn(Optional.of(plain));
        assertThatThrownBy(() -> migrationService.verifyAdminRole("plain"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Admin access required");

        User admin = User.builder().username("admin").role(Role.ADMIN).build();
        given(userRepository.findByUsername("admin")).willReturn(Optional.of(admin));
        // must not throw
        migrationService.verifyAdminRole("admin");
    }
}
