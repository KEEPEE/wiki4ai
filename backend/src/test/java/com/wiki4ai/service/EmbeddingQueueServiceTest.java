package com.wiki4ai.service;

import com.wiki4ai.config.EmbeddingProperties;
import com.wiki4ai.model.Document;
import com.wiki4ai.model.EmbeddingQueueEntry;
import com.wiki4ai.repository.DocumentRepository;
import com.wiki4ai.repository.EmbeddingQueueEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for the embedding queue worker (WIKI4AI-197 commit 17).
 *
 * <p>The {@link TransactionTemplate} is mocked but stubbed to <i>run</i> its callback, so
 * the writes the service puts inside a transaction actually happen here; only the
 * transaction itself is absent. That keeps the interesting assertions — what is written,
 * in what order, and what the row looks like afterwards — available without a database,
 * and keeps them independent of the {@code CAST(... AS vector)} the embedding update needs
 * on PostgreSQL and H2 does not have.</p>
 *
 * <p>{@link EmbeddingProperties} is the real object, not a mock: its defaults are part of
 * the behaviour under test (the backoff, the attempt ceiling, the batch size), and a mock
 * would answer 0 for every one of them and quietly test a configuration no instance ever
 * runs with.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmbeddingQueueServiceTest {

    @Mock
    private EmbeddingQueueEntryRepository queueRepository;

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private EmbeddingClient embeddingClient;

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private final EmbeddingProperties properties = new EmbeddingProperties();

    private EmbeddingQueueService service;

    @BeforeEach
    void wireTheService() {
        // The mocked transaction template must actually invoke the callback, or every
        // write the service makes would silently vanish.
        org.mockito.BDDMockito.willAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<org.springframework.transaction.TransactionStatus> callback =
                    invocation.getArgument(0);
            callback.accept(null);
            return null;
        }).given(transactionTemplate).executeWithoutResult(any());
        // save() returns its argument, as a real repository does after assigning an id.
        given(queueRepository.save(any(EmbeddingQueueEntry.class))).willAnswer(invocation -> {
            EmbeddingQueueEntry entry = invocation.getArgument(0);
            if (entry.getId() == null) {
                // @Builder cannot set an id through a private all-args constructor without
                // reflection, so the stub stamps one the way IDENTITY generation would.
                org.springframework.test.util.ReflectionTestUtils.setField(entry, "id", 100L);
            }
            return entry;
        });
        service = new EmbeddingQueueService(
                queueRepository, documentRepository, embeddingClient, embeddingService,
                properties, transactionTemplate, eventPublisher);
    }

    private EmbeddingQueueEntry pendingEntry(Long id, Long documentId) {
        LocalDateTime now = LocalDateTime.now();
        return EmbeddingQueueEntry.builder()
                .id(id)
                .documentId(documentId)
                .status(EmbeddingQueueEntry.STATUS_PENDING)
                .attempts(0)
                .nextAttemptAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private Document document(Long id, String title, String content) {
        Document document = new Document();
        document.setId(id);
        document.setTitle(title);
        document.setContent(content);
        return document;
    }

    // ==================== enqueue ====================

    @Nested
    @DisplayName("enqueueFor — the request is written with the document")
    class EnqueueTests {

        @Test
        @DisplayName("writes a PENDING row and publishes its id")
        void writesPendingRowAndPublishes() {
            service.enqueueFor(document(7L, "Title", "Body"));

            ArgumentCaptor<EmbeddingQueueEntry> saved = ArgumentCaptor.forClass(EmbeddingQueueEntry.class);
            verify(queueRepository).save(saved.capture());
            assertThat(saved.getValue().getDocumentId()).isEqualTo(7L);
            assertThat(saved.getValue().getStatus()).isEqualTo(EmbeddingQueueEntry.STATUS_PENDING);
            assertThat(saved.getValue().getAttempts()).isZero();

            ArgumentCaptor<EmbeddingQueueService.EmbedRequested> event =
                    ArgumentCaptor.forClass(EmbeddingQueueService.EmbedRequested.class);
            verify(eventPublisher).publishEvent(event.capture());
            assertThat(event.getValue().entryId()).isEqualTo(100L);
        }

        @Test
        @DisplayName("does not embed anything itself")
        void doesNotCallTheSidecar() {
            service.enqueueFor(document(7L, "Title", "Body"));

            verify(embeddingClient, never()).embedDocuments(anyList());
            verify(embeddingService, never()).embedAndSave(any());
        }

        /**
         * The whole point of the row. A synchronous embed that fails is gone; a queued one
         * is a row that outlives the failure, the thread and the process.
         */
        @Test
        @DisplayName("with the queue switched off, falls back to the old synchronous embed")
        void fallsBackWhenQueueDisabled() {
            properties.getQueue().setEnabled(false);

            service.enqueueFor(document(7L, "Title", "Body"));

            verify(embeddingService).embedAndSave(any(Document.class));
            verify(queueRepository, never()).save(any(EmbeddingQueueEntry.class));
        }

        /**
         * A document save must not fail because the queue failed. Before this commit the
         * embed could not fail a save either (it swallowed everything); the queue has to
         * keep that promise, and it keeps it by falling back rather than by throwing.
         */
        @Test
        @DisplayName("a queue write failure never reaches the caller")
        void queueWriteFailureFallsBackAndNeverThrows() {
            willThrow(new RuntimeException("queue table is down"))
                    .given(queueRepository).save(any(EmbeddingQueueEntry.class));

            assertThatCode(() -> service.enqueueFor(document(7L, "Title", "Body")))
                    .doesNotThrowAnyException();

            verify(embeddingService).embedAndSave(any(Document.class));
        }

        @Test
        @DisplayName("an unsaved document produces nothing")
        void unsavedDocumentProducesNothing() {
            Document noId = document(null, "Title", "Body");

            service.enqueueFor(noId);
            service.enqueueFor(null);

            verify(queueRepository, never()).save(any(EmbeddingQueueEntry.class));
            verify(eventPublisher, never()).publishEvent(any());
        }
    }

    // ==================== the unit of work ====================

    @Nested
    @DisplayName("processEntry — one attempt, one row")
    class ProcessTests {

        @Test
        @DisplayName("writes the vector and closes the row")
        void successWritesVectorAndClosesRow() {
            EmbeddingQueueEntry entry = pendingEntry(1L, 7L);
            given(queueRepository.findById(1L)).willReturn(Optional.of(entry));
            given(documentRepository.findById(7L)).willReturn(Optional.of(document(7L, "Title", "Body")));
            given(embeddingClient.embedDocuments(anyList())).willReturn(List.of(new float[]{1f, 2f}));

            assertThat(service.processEntry(1L)).isTrue();

            verify(documentRepository).updateEmbedding(eq(7L), anyString());
            assertThat(entry.getStatus()).isEqualTo(EmbeddingQueueEntry.STATUS_DONE);
            assertThat(entry.getLastError()).isNull();
        }

        /**
         * The text the sidecar sees is the contract. Asserted literally because a silent
         * change here would make every stored vector inconsistent with the new ones, and
         * only a full backfill would notice.
         */
        @Test
        @DisplayName("embeds title, newline, body")
        void embedsTitleNewlineBody() {
            EmbeddingQueueEntry entry = pendingEntry(1L, 7L);
            given(queueRepository.findById(1L)).willReturn(Optional.of(entry));
            given(documentRepository.findById(7L)).willReturn(Optional.of(document(7L, "Title", "Body text")));
            given(embeddingClient.embedDocuments(anyList())).willReturn(List.of(new float[]{1f}));

            service.processEntry(1L);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<String>> texts = ArgumentCaptor.forClass(List.class);
            verify(embeddingClient).embedDocuments(texts.capture());
            assertThat(texts.getValue()).containsExactly("Title\nBody text");
        }

        @Test
        @DisplayName("a null body embeds as an empty body, not as the word null")
        void nullBodyBecomesEmpty() {
            EmbeddingQueueEntry entry = pendingEntry(1L, 7L);
            given(queueRepository.findById(1L)).willReturn(Optional.of(entry));
            given(documentRepository.findById(7L)).willReturn(Optional.of(document(7L, "Title", null)));
            given(embeddingClient.embedDocuments(anyList())).willReturn(List.of(new float[]{1f}));

            service.processEntry(1L);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<String>> texts = ArgumentCaptor.forClass(List.class);
            verify(embeddingClient).embedDocuments(texts.capture());
            assertThat(texts.getValue()).containsExactly("Title\n");
        }

        /**
         * Deleting a document before its embed ran is a normal state for a queue row, not
         * a failure. Closing the row is what stops the drainer from asking about a document
         * that will never come back.
         */
        @Test
        @DisplayName("a deleted document closes the row instead of retrying")
        void deletedDocumentClosesRow() {
            EmbeddingQueueEntry entry = pendingEntry(1L, 7L);
            given(queueRepository.findById(1L)).willReturn(Optional.of(entry));
            given(documentRepository.findById(7L)).willReturn(Optional.empty());

            assertThat(service.processEntry(1L)).isFalse();

            assertThat(entry.getStatus()).isEqualTo(EmbeddingQueueEntry.STATUS_DONE);
            verify(embeddingClient, never()).embedDocuments(anyList());
            verify(documentRepository, never()).updateEmbedding(anyLong(), anyString());
        }

        @Test
        @DisplayName("a row that is already DONE is left alone")
        void doneRowIsLeftAlone() {
            EmbeddingQueueEntry entry = pendingEntry(1L, 7L);
            entry.markDone();
            given(queueRepository.findById(1L)).willReturn(Optional.of(entry));

            assertThat(service.processEntry(1L)).isFalse();

            verify(embeddingClient, never()).embedDocuments(anyList());
        }

        @Test
        @DisplayName("a missing row is not an error")
        void missingRowIsNotAnError() {
            given(queueRepository.findById(99L)).willReturn(Optional.empty());

            assertThat(service.processEntry(99L)).isFalse();
            assertThat(service.processEntry(null)).isFalse();
        }

        /**
         * The failure path is the one the queue exists for. The row must survive with its
         * count, its message and a next attempt in the future — that is what makes the
         * retry outlive a restart, which an in-memory retry could not.
         */
        @Test
        @DisplayName("a failed attempt keeps the row PENDING with its count, error and next time")
        void failureKeepsRowPendingWithBackoff() {
            EmbeddingQueueEntry entry = pendingEntry(1L, 7L);
            LocalDateTime before = LocalDateTime.now();
            given(queueRepository.findById(1L)).willReturn(Optional.of(entry));
            given(documentRepository.findById(7L)).willReturn(Optional.of(document(7L, "Title", "Body")));
            willThrow(new EmbeddingClient.EmbeddingUnavailableException("sidecar refused"))
                    .given(embeddingClient).embedDocuments(anyList());

            assertThat(service.processEntry(1L)).isFalse();

            assertThat(entry.getAttempts()).isEqualTo(1);
            assertThat(entry.getStatus()).isEqualTo(EmbeddingQueueEntry.STATUS_PENDING);
            assertThat(entry.getLastError()).contains("sidecar refused");
            assertThat(entry.getNextAttemptAt()).isAfter(before);
            verify(documentRepository, never()).updateEmbedding(anyLong(), anyString());
        }

        @Test
        @DisplayName("the last row of retries stops at FAILED with its error kept")
        void givesUpAtMaxAttempts() {
            int max = properties.getQueue().getMaxAttempts();
            EmbeddingQueueEntry entry = pendingEntry(1L, 7L);
            given(queueRepository.findById(1L)).willReturn(Optional.of(entry));
            given(documentRepository.findById(7L)).willReturn(Optional.of(document(7L, "Title", "Body")));
            willThrow(new EmbeddingClient.EmbeddingUnavailableException("still down"))
                    .given(embeddingClient).embedDocuments(anyList());

            for (int i = 0; i < max; i++) {
                service.processEntry(1L);
            }

            assertThat(entry.getAttempts()).isEqualTo(max);
            assertThat(entry.getStatus()).isEqualTo(EmbeddingQueueEntry.STATUS_FAILED);
            assertThat(entry.getLastError()).contains("still down");

            // A FAILED row is not PENDING, so the drainer's query will not return it and
            // the next attempt is a no-op.
            assertThat(service.processEntry(1L)).isFalse();
            assertThat(entry.getAttempts()).isEqualTo(max);
        }

        /**
         * A long message must not blow up the write that records it. The stack trace goes
         * to the log; the row keeps what fits.
         */
        @Test
        @DisplayName("a long error message is truncated to the column width")
        void longErrorMessageIsTruncated() {
            EmbeddingQueueEntry entry = pendingEntry(1L, 7L);
            given(queueRepository.findById(1L)).willReturn(Optional.of(entry));
            given(documentRepository.findById(7L)).willReturn(Optional.of(document(7L, "Title", "Body")));
            willThrow(new RuntimeException("x".repeat(2000)))
                    .given(embeddingClient).embedDocuments(anyList());

            service.processEntry(1L);

            assertThat(entry.getLastError()).hasSize(512);
        }
    }

    // ==================== backoff ====================

    @Nested
    @DisplayName("backoff between attempts")
    class BackoffTests {

        @Test
        @DisplayName("doubles per attempt and stops at the cap")
        void doublesThenCaps() {
            EmbeddingProperties.Queue queue = properties.getQueue();
            queue.setRetryBackoffMs(1000);
            queue.setMaxBackoffMs(4000);
            queue.setMaxAttempts(50);

            EmbeddingQueueEntry entry = pendingEntry(1L, 7L);
            given(queueRepository.findById(1L)).willReturn(Optional.of(entry));
            given(documentRepository.findById(7L)).willReturn(Optional.of(document(7L, "Title", "Body")));
            willThrow(new RuntimeException("down")).given(embeddingClient).embedDocuments(anyList());

            // The row's nextAttemptAt is measured from the moment the attempt failed, so
            // each attempt is timed against a clock read immediately before it. The
            // expected delay is base << (attempts already made), capped.
            long[] expectedMillis = {1000, 2000, 4000, 4000, 4000};
            for (int i = 0; i < expectedMillis.length; i++) {
                LocalDateTime before = LocalDateTime.now();
                service.processEntry(1L);

                long delay = java.time.Duration.between(before, entry.getNextAttemptAt()).toMillis();
                assertThat(delay)
                        .as("delay before attempt %d", i + 2)
                        .isBetween(expectedMillis[i] - 200L, expectedMillis[i] + 500L);
                assertThat(entry.getAttempts()).isEqualTo(i + 1);
            }
        }
    }

    // ==================== the drainer ====================

    @Nested
    @DisplayName("drainDueEntries — the durability half")
    class DrainTests {

        @Test
        @DisplayName("processes every due row and reports how many it embedded")
        void processesDueRows() {
            EmbeddingQueueEntry first = pendingEntry(1L, 10L);
            EmbeddingQueueEntry second = pendingEntry(2L, 11L);
            given(queueRepository.findDueEntries(any(LocalDateTime.class), any(Pageable.class)))
                    .willReturn(List.of(first, second));
            given(queueRepository.findById(1L)).willReturn(Optional.of(first));
            given(queueRepository.findById(2L)).willReturn(Optional.of(second));
            given(documentRepository.findById(anyLong()))
                    .willReturn(Optional.of(document(10L, "T", "B")));
            given(embeddingClient.embedDocuments(anyList())).willReturn(List.of(new float[]{1f}));

            assertThat(service.drainDueEntries()).isEqualTo(2);

            verify(documentRepository, atLeastOnce()).updateEmbedding(anyLong(), anyString());
            assertThat(first.getStatus()).isEqualTo(EmbeddingQueueEntry.STATUS_DONE);
            assertThat(second.getStatus()).isEqualTo(EmbeddingQueueEntry.STATUS_DONE);
        }

        @Test
        @DisplayName("nothing due means no work and no sidecar call")
        void nothingDue() {
            given(queueRepository.findDueEntries(any(LocalDateTime.class), any(Pageable.class)))
                    .willReturn(List.of());

            assertThat(service.drainDueEntries()).isZero();
            verify(embeddingClient, never()).embedDocuments(anyList());
        }

        /**
         * One dead sidecar must not turn into one long transaction and a monopolised
         * connection pool: the batch is capped, and the rest waits for the next tick.
         */
        @Test
        @DisplayName("takes at most drainBatchSize rows per pass")
        void batchIsCapped() {
            properties.getQueue().setDrainBatchSize(3);
            given(queueRepository.findDueEntries(any(LocalDateTime.class), any(Pageable.class)))
                    .willReturn(List.of());

            service.drainDueEntries();

            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
            verify(queueRepository).findDueEntries(any(LocalDateTime.class), pageable.capture());
            assertThat(pageable.getValue().getPageSize()).isEqualTo(3);
        }

        @Test
        @DisplayName("a switched-off queue drains nothing")
        void disabledQueueDrainsNothing() {
            properties.getQueue().setEnabled(false);

            assertThat(service.drainDueEntries()).isZero();

            verify(queueRepository, never()).findDueEntries(any(LocalDateTime.class), any(Pageable.class));
        }

        /**
         * The scenario the queue exists for: the process died between the commit and the
         * embed, so no listener ever ran, and the row is the only trace of the request.
         */
        @Test
        @DisplayName("a row left PENDING by a restart is picked up again")
        void pendingRowSurvivesARestart() {
            EmbeddingQueueEntry orphan = pendingEntry(1L, 7L);
            // A fresh service, as after a restart: nothing in memory refers to the row.
            EmbeddingQueueService restarted = new EmbeddingQueueService(
                    queueRepository, documentRepository, embeddingClient, embeddingService,
                    properties, transactionTemplate, eventPublisher);
            given(queueRepository.findDueEntries(any(LocalDateTime.class), any(Pageable.class)))
                    .willReturn(List.of(orphan));
            given(queueRepository.findById(1L)).willReturn(Optional.of(orphan));
            given(documentRepository.findById(7L)).willReturn(Optional.of(document(7L, "Title", "Body")));
            given(embeddingClient.embedDocuments(anyList())).willReturn(List.of(new float[]{1f}));

            assertThat(restarted.drainDueEntries()).isEqualTo(1);
            assertThat(orphan.getStatus()).isEqualTo(EmbeddingQueueEntry.STATUS_DONE);
        }
    }

    // ==================== the after-commit trigger ====================

    @Nested
    @DisplayName("onEmbedRequested — runs only after the commit")
    class ListenerTests {

        /**
         * The test profile switches the worker thread off, and this is the assertion that
         * pins that behaviour: the listener must not touch the shared test database from
         * another thread.
         */
        @Test
        @DisplayName("with the worker thread off, the row is simply left for the drainer")
        void asyncOffLeavesTheRowToTheDrainer() {
            properties.getQueue().setAsyncEnabled(false);

            service.onEmbedRequested(new EmbeddingQueueService.EmbedRequested(1L));

            verify(queueRepository, never()).findById(anyLong());
        }

        @Test
        @DisplayName("with the worker thread on, the entry is processed")
        void asyncOnProcessesTheEntry() throws Exception {
            properties.getQueue().setAsyncEnabled(true);
            EmbeddingQueueEntry entry = pendingEntry(1L, 7L);
            given(queueRepository.findById(1L)).willReturn(Optional.of(entry));
            given(documentRepository.findById(7L)).willReturn(Optional.of(document(7L, "Title", "Body")));
            given(embeddingClient.embedDocuments(anyList())).willReturn(List.of(new float[]{1f}));

            service.onEmbedRequested(new EmbeddingQueueService.EmbedRequested(1L));

            // The listener hands the work to another thread, so wait for the row to close
            // rather than sleeping a fixed amount.
            long deadline = System.currentTimeMillis() + 5000;
            while (!EmbeddingQueueEntry.STATUS_DONE.equals(entry.getStatus())
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertThat(entry.getStatus()).isEqualTo(EmbeddingQueueEntry.STATUS_DONE);
        }

        @Test
        @DisplayName("an event with no entry id is ignored")
        void nullEventIsIgnored() {
            properties.getQueue().setAsyncEnabled(true);

            service.onEmbedRequested(null);
            service.onEmbedRequested(new EmbeddingQueueService.EmbedRequested(null));

            verify(queueRepository, never()).findById(anyLong());
        }
    }

    // ==================== reporting ====================

    @Nested
    @DisplayName("what the queue reports")
    class ReportingTests {

        @Test
        @DisplayName("pending count comes from the repository")
        void pendingCountDelegates() {
            given(queueRepository.countByStatus(EmbeddingQueueEntry.STATUS_PENDING)).willReturn(4L);

            assertThat(service.pendingCount()).isEqualTo(4L);
        }

        @Test
        @DisplayName("failed entries are read from the repository with a limit")
        void failedEntriesDelegate() {
            given(queueRepository.findByStatus(eq(EmbeddingQueueEntry.STATUS_FAILED), any(Pageable.class)))
                    .willReturn(List.of(pendingEntry(1L, 7L)));

            assertThat(service.failedEntries(5)).hasSize(1);

            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
            verify(queueRepository).findByStatus(eq(EmbeddingQueueEntry.STATUS_FAILED), pageable.capture());
            assertThat(pageable.getValue().getPageSize()).isEqualTo(5);
        }
    }
}
