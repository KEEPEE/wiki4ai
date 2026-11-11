package com.wiki4ai.service;

import com.wiki4ai.dto.DocumentCreateDTO;
import com.wiki4ai.dto.DocumentDTO;
import com.wiki4ai.dto.ProjectCreateDTO;
import com.wiki4ai.dto.ProjectDTO;
import com.wiki4ai.dto.ReorderRequestDTO;
import com.wiki4ai.model.Document;
import com.wiki4ai.model.Project;
import com.wiki4ai.repository.DocumentRepository;
import com.wiki4ai.repository.ProjectPermissionRepository;
import com.wiki4ai.repository.ProjectRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WIKI4AI-197 commit 11: two writers of {@code sort_order} must not lose each other.
 *
 * <h2>What the bug was</h2>
 * A reorder read the whole list, permuted it in memory and wrote the new positions back.
 * Two drags that read the same list permute the same snapshot, so the second writer
 * renumbers from a state that no longer exists and the first drag silently disappears.
 * The same race sits under pin and create, which both take {@code min(sort_order) - 1}:
 * two concurrent pins can be handed the same slot.
 *
 * <h2>Why these tests are not {@code @Transactional}</h2>
 * A {@code @Transactional} test method rolls back instead of committing, so a second
 * thread could never see — or fight with — the first one's locks. Every write here goes
 * through a service method that opens its own transaction, either on the calling thread
 * or on one of the two pool threads.
 *
 * <h2>Why the concurrency assertions are about shape, not about who won</h2>
 * Which drag wins a race is timing, so it is not asserted. What must hold no matter how
 * the two interleave is that no two rows claim the same position and no row was skipped —
 * losing a writer is exactly what breaks those, and they are what makes a manual order
 * usable.
 *
 * <p>Density itself is a stronger claim than it looks, and the two kinds of race differ
 * in whether it holds:</p>
 * <ul>
 *   <li><b>reorder vs reorder</b> — both writers renumber the whole list densely, so the
 *   result is dense 0..n-1 whichever one goes second. Those tests assert density, and
 *   they can.</li>
 *   <li><b>reorder vs pin</b> — a pin is not a renumbering. It writes
 *   {@code min(sort_order) - 1} on purpose (WIKI4AI-184), which is outside the dense range
 *   by construction, and {@code createDocument} does the same thing. So a pin that commits
 *   after a reorder leaves one row at -1 and a hole where it stood; a pin that commits
 *   before one gets renumbered back inside. Both are correct, and the lock decides which
 *   happens. Asserting density there means asserting the timing — which is why
 *   {@link #reorderRacingPinKeepsBothEffects()} asserts distinctness and range instead,
 *   and {@link #aPinAfterAReorderLeavesADeliberateHoleWithNoRaceAtAll()} reproduces the
 *   "unexpected" shape with no threads at all.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class OrderingConcurrencyTest {

    @Autowired
    private ProjectService projectService;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private ProjectPermissionRepository projectPermissionRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    @AfterEach
    void leaveNothingBehind() {
        // Shared H2 across contexts: clean before AND after, the way
        // PinningReorderIntegrationTests does, so a leftover row cannot skew the next class.
        documentRepository.deleteAll();
        projectPermissionRepository.deleteAll();
        projectRepository.deleteAll();
    }

    private Long createProject(String name) {
        ProjectCreateDTO dto = new ProjectCreateDTO();
        dto.setName(name);
        dto.setDescription("concurrency playground");
        return projectService.createProject(dto, null).getId();
    }

    private List<String> createDocs(Long projectId, int count) {
        List<String> slugs = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            DocumentCreateDTO dto = new DocumentCreateDTO();
            dto.setTitle("Doc " + i);
            dto.setContent("body " + i);
            slugs.add(documentService.createDocument(projectId, dto, null).getSlug());
        }
        return slugs;
    }

    /** The stored manual order of a project's documents, as positions. */
    private List<Integer> documentPositions(Long projectId) {
        return documentRepository.findByProjectId(projectId).stream()
                .map(Document::getSortOrder)
                .sorted()
                .collect(Collectors.toList());
    }

    /** The stored position of one document. */
    private int documentPosition(Long projectId, String slug) {
        return documentRepository.findBySlugAndProjectId(slug, projectId)
                .orElseThrow().getSortOrder();
    }

    private List<Integer> projectPositions() {
        return projectRepository.findAll().stream()
                .map(Project::getSortOrder)
                .sorted()
                .collect(Collectors.toList());
    }

    /**
     * Run two reorder calls on two threads that are released at the same instant, and
     * return the throwables (null entries mean the call succeeded).
     */
    private List<Throwable> race(Runnable first, Runnable second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
            Future<?> a = pool.submit(() -> {
                awaitQuietly(barrier);
                try {
                    first.run();
                } catch (Throwable t) {
                    failures.add(t);
                }
            });
            Future<?> b = pool.submit(() -> {
                awaitQuietly(barrier);
                try {
                    second.run();
                } catch (Throwable t) {
                    failures.add(t);
                }
            });
            a.get(30, TimeUnit.SECONDS);
            b.get(30, TimeUnit.SECONDS);
            return failures;
        } finally {
            pool.shutdownNow();
        }
    }

    private static void awaitQuietly(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // A broken barrier still runs the body; the assertions below decide.
        }
    }

    // =======================================================================

    @Test
    @DisplayName("two concurrent document reorders both succeed and leave a dense 0..n-1 order")
    void concurrentDocumentReordersKeepADenseOrder() throws Exception {
        Long projectId = createProject("Race Docs");
        List<String> slugs = createDocs(projectId, 6);

        List<Throwable> failures = race(
                () -> documentService.reorderDocument(projectId, slugs.get(0),
                        ReorderRequestDTO.builder().position(5).build(), null),
                () -> documentService.reorderDocument(projectId, slugs.get(5),
                        ReorderRequestDTO.builder().position(0).build(), null));

        assertThat(failures).isEmpty();
        assertThat(documentPositions(projectId))
                .as("positions must stay a dense permutation — a lost writer duplicates one and skips another")
                .containsExactly(0, 1, 2, 3, 4, 5);
    }

    @Test
    @DisplayName("a reorder racing a pin leaves no duplicate order and the pin in place")
    void reorderRacingPinKeepsBothEffects() throws Exception {
        Long projectId = createProject("Race Pin");
        List<String> slugs = createDocs(projectId, 5);
        String toPin = slugs.get(2);
        String toMove = slugs.get(0);

        List<Throwable> failures = race(
                () -> documentService.reorderDocument(projectId, toMove,
                        ReorderRequestDTO.builder().position(4).build(), null),
                () -> documentService.pinDocument(projectId, toPin, null));

        assertThat(failures).isEmpty();

        // WHY THIS ASSERTION CHANGED (WIKI4AI-200 follow-up).
        //
        // It used to be `containsExactly(0, 1, 2, 3, 4)`, and that was never an invariant
        // of this pair of writers — the test failed 6 times in 10 runs because of it.
        //
        // pinDocument writes `min(sort_order) - 1` on purpose (WIKI4AI-184), and
        // createDocument does the same, so the two legal orders give two different shapes:
        //   pin commits first → the reorder renumbers everything and pulls the pinned
        //                       document back inside: dense 0..n-1;
        //   reorder first     → the pin then lifts its document to min-1 = -1, outside the
        //                       dense range, and vacates the slot it held: one hole.
        // The project-row lock decides which order happens, so the old assertion was
        // asserting the timing. The values it printed, [-1, 0, 1, 3, 4], are exactly what a
        // single-threaded reorder-then-pin produces — see
        // aPinAfterAReorderLeavesADeliberateHoleWithNoRaceAtAll, which reproduces that
        // shape with zero threads. It is not a lost writer.
        //
        // What is asserted instead is the invariant in assertNoWriterWasLost, and
        // theInvariantRefusesEachShapeALostWriterLeavesBehind proves that invariant still
        // fires on each shape a lost writer actually leaves behind.
        assertNoWriterWasLost(documentRepository.findByProjectId(projectId), 5);

        assertThat(documentRepository.findBySlugAndProjectId(toPin, projectId).orElseThrow().isPinned())
                .as("a reorder must not undo a pin that committed alongside it")
                .isTrue();
    }

    @Test
    @DisplayName("a pin after a reorder leaves a hole and a -1 — with no race at all")
    void aPinAfterAReorderLeavesADeliberateHoleWithNoRaceAtAll() {
        // The control for the test above. No threads, no barrier, no timing: reorder, then
        // pin. If the shape the concurrency test blamed on a lost writer appears here, it
        // is not a race, it is what WIKI4AI-184 designed.
        Long projectId = createProject("Serial Pin After Reorder");
        List<String> slugs = createDocs(projectId, 5);

        documentService.reorderDocument(projectId, slugs.get(0),
                ReorderRequestDTO.builder().position(4).build(), null);
        assertThat(documentPositions(projectId))
                .as("a reorder on its own does produce the dense order")
                .containsExactly(0, 1, 2, 3, 4);

        String toPin = slugs.get(2);
        int slotBefore = documentPosition(projectId, toPin);

        documentService.pinDocument(projectId, toPin, null);

        assertThat(documentPosition(projectId, toPin))
                .as("pin takes min(sort_order) - 1 by design, which is outside 0..n-1")
                .isEqualTo(-1);
        List<Integer> expected = new ArrayList<>();
        expected.add(-1);
        for (int p = 0; p < 5; p++) {
            if (p != slotBefore) {
                expected.add(p);
            }
        }
        assertThat(documentPositions(projectId))
                .as("one document at -1 plus the dense range minus the slot it vacated — the "
                        + "exact shape [-1, 0, 1, 3, 4] that the racing test printed when it "
                        + "failed, produced here with zero concurrency")
                .doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    @DisplayName("two concurrent project reorders both succeed and leave a dense 0..n-1 order")
    void concurrentProjectReordersKeepADenseOrder() throws Exception {
        Long first = createProject("Race Project A");
        Long second = createProject("Race Project B");
        createProject("Race Project C");
        String slugA = projectRepository.findById(first).orElseThrow().getSlug();
        String slugB = projectRepository.findById(second).orElseThrow().getSlug();

        List<Throwable> failures = race(
                () -> projectService.reorderProject(slugA,
                        ReorderRequestDTO.builder().position(2).build(), null),
                () -> projectService.reorderProject(slugB,
                        ReorderRequestDTO.builder().position(0).build(), null));

        assertThat(failures).isEmpty();
        assertThat(projectPositions()).containsExactly(0, 1, 2);
    }

    // =======================================================================

    @Test
    @DisplayName("the compare-and-set refuses a position the row no longer holds")
    void compareAndSetRefusesAStalePosition() {
        Long projectId = createProject("CAS Docs");
        String slug = createDocs(projectId, 2).get(0);
        Document doc = documentRepository.findBySlugAndProjectId(slug, projectId).orElseThrow();
        int current = doc.getSortOrder();

        Integer written = transactionTemplate.execute(status ->
                documentRepository.updateSortOrderIfCurrent(doc.getId(), 4242, current + 1000));

        assertThat(written)
                .as("an expected position that is not the row's current one writes nothing")
                .isZero();
        assertThat(documentRepository.findBySlugAndProjectId(slug, projectId).orElseThrow()
                .getSortOrder()).isEqualTo(current);

        Integer applied = transactionTemplate.execute(status ->
                documentRepository.updateSortOrderIfCurrent(doc.getId(), 7, current));
        assertThat(applied).isEqualTo(1);
        assertThat(documentRepository.findBySlugAndProjectId(slug, projectId).orElseThrow()
                .getSortOrder()).isEqualTo(7);
    }

    @Test
    @DisplayName("the project compare-and-set refuses a position the row no longer holds")
    void compareAndSetRefusesAStaleProjectPosition() {
        createProject("CAS Project");
        Project project = projectRepository.findBySlug("cas-project").orElseThrow();
        int current = project.getSortOrder();

        Integer written = transactionTemplate.execute(status ->
                projectRepository.updateSortOrderIfCurrent(project.getId(), 4242, current + 1000));

        assertThat(written).isZero();
        assertThat(projectRepository.findBySlug("cas-project").orElseThrow().getSortOrder())
                .isEqualTo(current);
    }

    // =======================================================================
    //
    // The invariant the concurrency tests share, and the test that proves it still bites.

    /**
     * The invariant a manual order must satisfy after any pair of concurrent writers.
     *
     * <p>Three clauses, each chosen because a lost writer violates it and a legal pin does
     * not:</p>
     * <ol>
     *   <li><b>no two rows share a position.</b> A duplicate is the corruption that
     *   matters: it makes {@code ORDER BY sort_order, id} answer a question the user did
     *   not ask. A stale renumbering writes the same slot to two rows.</li>
     *   <li><b>every position is inside [-1, n-1].</b> A row the renumbering never reached
     *   still carries the value it was created with, and creates stack at -1, -2, -3 …, so
     *   anything below -1 is a row that was skipped.</li>
     *   <li><b>at most one row sits outside the dense range, and if one does it is the
     *   pinned one.</b> That is the single deviation a pin is allowed to make, and it
     *   forbids the second hole a stale renumbering leaves behind.</li>
     * </ol>
     *
     * <p>Density itself is deliberately not asserted here: a pin legitimately breaks it.
     * The reorder-vs-reorder tests do assert density, because there both writers renumber
     * the whole list and the result is dense whichever one goes second.</p>
     */
    private void assertNoWriterWasLost(List<Document> docs, int expectedCount) {
        List<Integer> positions = docs.stream()
                .map(Document::getSortOrder)
                .sorted()
                .collect(Collectors.toList());

        assertThat(positions).hasSize(expectedCount);
        assertThat(positions)
                .as("no duplicate sort_order - a lost writer duplicates a slot")
                .doesNotHaveDuplicates();
        assertThat(positions)
                .as("no document escaped the range a reorder or a pin can write; a row left "
                        + "at its creation value sits at -2 or lower")
                .allMatch(p -> p >= -1 && p < docs.size());

        List<Document> outsideDenseRange = docs.stream()
                .filter(d -> d.getSortOrder() < 0)
                .collect(Collectors.toList());
        assertThat(outsideDenseRange)
                .as("at most one document may sit outside 0..n-1")
                .hasSizeLessThanOrEqualTo(1);
        for (Document escaped : outsideDenseRange) {
            assertThat(escaped.isPinned())
                    .as("the only document allowed outside the dense range is the one the pin "
                            + "deliberately lifted to min-1; '%s' is not pinned, so the "
                            + "renumbering left it behind", escaped.getSlug())
                    .isTrue();
        }
    }

    /** Force one document's stored (pinned, sort_order) pair, bypassing the service. */
    private void forceState(Long projectId, int index, boolean pinned, int sortOrder) {
        Document doc = documentRepository.findByProjectId(projectId).get(index);
        transactionTemplate.executeWithoutResult(status ->
                documentRepository.updatePinning(doc.getId(), pinned, sortOrder));
    }

    @Test
    @DisplayName("the invariant refuses each shape a lost writer leaves behind")
    void theInvariantRefusesEachShapeALostWriterLeavesBehind() {
        // An assertion that cannot fail is not a test. Each block below writes a state by
        // hand and says whether the invariant must accept or reject it.
        Long projectId = createProject("Invariant Guard");
        createDocs(projectId, 3);

        // Legal: the dense output of a reorder.
        forceState(projectId, 0, false, 0);
        forceState(projectId, 1, false, 1);
        forceState(projectId, 2, false, 2);
        assertNoWriterWasLost(documentRepository.findByProjectId(projectId), 3);

        // Legal: a pin that committed after the reorder - one row at min-1, one hole.
        forceState(projectId, 0, true, -1);
        forceState(projectId, 1, false, 1);
        forceState(projectId, 2, false, 2);
        assertNoWriterWasLost(documentRepository.findByProjectId(projectId), 3);

        // Illegal: two documents claim slot 1 - the duplicate a stale renumbering writes.
        forceState(projectId, 0, false, 0);
        forceState(projectId, 1, false, 1);
        forceState(projectId, 2, false, 1);
        assertThatThrownBy(() ->
                assertNoWriterWasLost(documentRepository.findByProjectId(projectId), 3))
                .hasMessageContaining("duplicate");

        // Illegal: a document the renumbering never reached, still at its creation slot.
        forceState(projectId, 0, false, 0);
        forceState(projectId, 1, false, 1);
        forceState(projectId, 2, false, -3);
        assertThatThrownBy(() ->
                assertNoWriterWasLost(documentRepository.findByProjectId(projectId), 3))
                .hasMessageContaining("escaped the range");

        // Illegal: a second row below zero that the pin did not lift there.
        forceState(projectId, 0, true, -1);
        forceState(projectId, 1, false, -2);
        forceState(projectId, 2, false, 0);
        assertThatThrownBy(() ->
                assertNoWriterWasLost(documentRepository.findByProjectId(projectId), 3))
                .satisfiesAnyOf(
                        e -> assertThat(e).hasMessageContaining("escaped the range"),
                        e -> assertThat(e).hasMessageContaining("outside 0..n-1"));

        // Illegal: a row below zero that is not the pinned one - left behind, not lifted.
        forceState(projectId, 0, false, -1);
        forceState(projectId, 1, false, 1);
        forceState(projectId, 2, false, 2);
        assertThatThrownBy(() ->
                assertNoWriterWasLost(documentRepository.findByProjectId(projectId), 3))
                .hasMessageContaining("not pinned");
    }
}
