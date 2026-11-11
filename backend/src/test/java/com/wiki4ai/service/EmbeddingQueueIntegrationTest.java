package com.wiki4ai.service;

import com.wiki4ai.dto.DocumentCreateDTO;
import com.wiki4ai.dto.ProjectCreateDTO;
import com.wiki4ai.dto.ProjectDTO;
import com.wiki4ai.model.EmbeddingQueueEntry;
import com.wiki4ai.repository.DocumentRepository;
import com.wiki4ai.repository.EmbeddingQueueEntryRepository;
import com.wiki4ai.repository.ProjectPermissionRepository;
import com.wiki4ai.repository.ProjectRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The queue row and the document are one unit of work (WIKI4AI-197 commit 17).
 *
 * <p>Everything that makes this design worth building lives in this file:</p>
 * <ul>
 *   <li><b>The row is written with the document.</b> Not after it, and not in a second
 *       transaction — the same commit makes both visible, or neither happens.</li>
 *   <li><b>A rolled-back save leaves no phantom request.</b> Without this, a failed save
 *       would have the worker embedding a document that was never written.</li>
 *   <li><b>A row that outlives its trigger is still work.</b> The drainer finds it again,
 *       which is the difference between a durable queue and a thread pool with a memory.</li>
 * </ul>
 *
 * <p>The sidecar is not running in the test profile, so an embed attempt fails at the
 * client. That is not a limitation of these tests, it is the failure they are about: the
 * row must survive the failure with its count and its error. The success path — which
 * needs PostgreSQL's {@code CAST(... AS vector)} and has no H2 equivalent — is covered by
 * {@code EmbeddingQueueServiceTest}.</p>
 *
 * <p>{@code embedding.queue.async-enabled=false} in the test profile, so the after-commit
 * listener does not hand work to a background thread that would race the shared in-memory
 * database. The listener's own behaviour is asserted in the unit test; here the worker is
 * driven directly, on the test thread.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class EmbeddingQueueIntegrationTest {

    @Autowired
    private ProjectService projectService;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private EmbeddingQueueService embeddingQueueService;

    @Autowired
    private EmbeddingQueueEntryRepository queueRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private ProjectPermissionRepository projectPermissionRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    /**
     * For the two things a test needs that the production repository deliberately does not
     * offer: emptying the table between tests, and pulling a row's next attempt back into
     * the present so a test can reach the attempt ceiling without waiting out the backoff.
     * Keeping them out of the repository is the point — {@code deleteAll} on a queue
     * interface invites production code to wipe it.
     */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long projectId;

    @BeforeEach
    @AfterEach
    void startAndLeaveAnEmptyWiki() {
        jdbcTemplate.execute("DELETE FROM embedding_queue_entries");
        documentRepository.deleteAll();
        projectPermissionRepository.deleteAll();
        projectRepository.deleteAll();
        ProjectDTO project = projectService.createProject(
                ProjectCreateDTO.builder().name("Queue Wiki").description("d").build(), null);
        projectId = project.getId();
    }

    private Long createDocument(String title) {
        return documentService.createDocument(projectId,
                DocumentCreateDTO.builder().title(title).content("body").build(), null).getId();
    }

    private List<EmbeddingQueueEntry> rowsFor(Long documentId) {
        return queueRepository.findByDocumentIdAndStatusNot(documentId, EmbeddingQueueEntry.STATUS_DONE);
    }

    /**
     * The core promise. If the row were written in its own transaction after the commit,
     * a crash in the gap would lose the request; if it were written before the document, a
     * rolled-back save would leave a request for a document that does not exist.
     */
    @Test
    @DisplayName("a saved document leaves a PENDING queue row behind it")
    void savedDocumentLeavesAPendingRow() {
        Long documentId = createDocument("Queued Note");

        List<EmbeddingQueueEntry> rows = rowsFor(documentId);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getStatus()).isEqualTo(EmbeddingQueueEntry.STATUS_PENDING);
        assertThat(rows.get(0).getAttempts()).isZero();
        assertThat(rows.get(0).getCreatedAt()).isNotNull();
    }

    /**
     * The other half of the same transaction: the row is not merely present, it is present
     * exactly when the document is.
     */
    @Test
    @DisplayName("a rolled-back save leaves neither document nor queue row")
    void rolledBackSaveLeavesNothingBehind() {
        transactionTemplate.executeWithoutResult(status -> {
            documentService.createDocument(projectId,
                    DocumentCreateDTO.builder().title("Doomed Note").content("body").build(), null);
            status.setRollbackOnly();
        });

        assertThat(documentRepository.findAll())
                .extracting(document -> document.getTitle())
                .doesNotContain("Doomed Note");
        assertThat(queueRepository.countByStatus(EmbeddingQueueEntry.STATUS_PENDING))
                .as("a request for a document that was never written must not outlive it")
                .isZero();
    }

    /**
     * An update re-queues rather than reusing the row: the old request describes the old
     * text, and closing it separately is what keeps "which version is embedded" answerable.
     */
    @Test
    @DisplayName("editing a document queues another embed")
    void updateQueuesAgain() {
        Long documentId = createDocument("First Title");
        String slug = documentRepository.findById(documentId).orElseThrow().getSlug();

        documentService.updateDocumentBySlug(projectId, slug,
                com.wiki4ai.dto.DocumentUpdateDTO.builder().title("Second Title").content("new body").build(),
                null);

        assertThat(rowsFor(documentId)).hasSize(2);
        assertThat(queueRepository.countByStatus(EmbeddingQueueEntry.STATUS_PENDING)).isEqualTo(2);
    }

    /**
     * The durability half: nothing in memory refers to the row any more, and it is still
     * work. This is the scenario the queue exists for — the process died between the commit
     * and the embed, so the after-commit listener never ran.
     */
    @Test
    @DisplayName("the drainer picks up a row nobody triggered")
    void drainerPicksUpAnOrphanRow() {
        Long documentId = createDocument("Orphan Note");

        // No sidecar in the test profile, so the attempt fails at the client. What matters
        // is that the drainer reached the row and recorded the attempt rather than losing
        // it: the row is still there, one attempt older, still asking to be done.
        embeddingQueueService.drainDueEntries();

        List<EmbeddingQueueEntry> rows = rowsFor(documentId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getAttempts()).isEqualTo(1);
        assertThat(rows.get(0).getStatus()).isEqualTo(EmbeddingQueueEntry.STATUS_PENDING);
        assertThat(rows.get(0).getLastError()).isNotBlank();
    }

    /**
     * Deleting the document is a normal outcome, not a failure: the row closes instead of
     * being retried until it exhausts its attempts.
     */
    @Test
    @DisplayName("a row whose document was deleted is closed, not retried")
    void deletedDocumentClosesItsRow() {
        Long documentId = createDocument("Doomed From The Start");
        String slug = documentRepository.findById(documentId).orElseThrow().getSlug();
        documentService.deleteDocumentBySlug(projectId, slug, null);

        embeddingQueueService.drainDueEntries();

        assertThat(rowsFor(documentId))
                .as("the row must be DONE, and findByDocumentIdAndStatusNot excludes DONE")
                .isEmpty();
        assertThat(queueRepository.countByStatus(EmbeddingQueueEntry.STATUS_PENDING)).isZero();
    }

    /**
     * A row that has exhausted its attempts stops being due, so the drainer cannot spin on
     * it forever — and the reason it stopped stays readable on the row.
     */
    @Test
    @DisplayName("a row that gave up is FAILED and no longer due")
    void exhaustedRowStopsBeingDue() {
        Long documentId = createDocument("Stubborn Note");
        int maxAttempts = 5;   // EmbeddingProperties default, asserted rather than assumed

        for (int i = 0; i < maxAttempts; i++) {
            embeddingQueueService.drainDueEntries();
            // The backoff pushes the next attempt into the future; pull it back so this
            // test exercises the attempt ceiling and not the clock.
            jdbcTemplate.update("UPDATE embedding_queue_entries SET next_attempt_at = CURRENT_TIMESTAMP");
        }

        List<EmbeddingQueueEntry> all = queueRepository.findByDocumentIdOrderByIdAsc(documentId);
        assertThat(all).hasSize(1);
        assertThat(all.get(0).getStatus()).isEqualTo(EmbeddingQueueEntry.STATUS_FAILED);
        assertThat(all.get(0).getAttempts()).isEqualTo(maxAttempts);
        assertThat(all.get(0).getLastError()).isNotBlank();

        // FAILED rows are not PENDING, so the drainer's query must not return them.
        embeddingQueueService.drainDueEntries();
        assertThat(queueRepository.findByDocumentIdOrderByIdAsc(documentId).get(0).getAttempts())
                .as("a FAILED row must not be retried")
                .isEqualTo(maxAttempts);
    }

    /**
     * The timer is wired to the operation the tests above exercise. Asserted rather than
     * trusted because the timer is switched off in this profile, so nothing else in the
     * suite would notice if the annotation were dropped or pointed at the wrong method —
     * the same reasoning {@code WorkItemLeaseReaperTest} records.
     */
    @Test
    @DisplayName("the scheduled drainer is wired to drainDueEntries")
    void scheduledDrainerIsWired() throws NoSuchMethodException {
        java.lang.reflect.Method method =
                EmbeddingQueueDrainer.class.getMethod("drainDueEntries");

        Scheduled scheduled = method.getAnnotation(Scheduled.class);
        assertThat(scheduled)
                .as("the queue needs a timer, or a row left by a restart is never read again")
                .isNotNull();
        assertThat(scheduled.fixedDelayString())
                .isEqualTo("${wiki4ai.embedding.drain-interval-ms:30000}");
    }
}
