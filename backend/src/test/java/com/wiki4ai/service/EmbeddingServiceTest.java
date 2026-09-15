package com.wiki4ai.service;

import com.wiki4ai.config.EmbeddingProperties;
import com.wiki4ai.model.Document;
import com.wiki4ai.model.Project;
import com.wiki4ai.repository.DocumentRepository;
import com.wiki4ai.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EmbeddingService} (per-document embed + async backfill).
 */
@ExtendWith(MockitoExtension.class)
class EmbeddingServiceTest {

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private EmbeddingClient embeddingClient;

    @Mock
    private TransactionTemplate transactionTemplate;

    private EmbeddingService embeddingService;

    private EmbeddingProperties properties;
    private Project project;

    @BeforeEach
    void setUp() {
        properties = new EmbeddingProperties();
        properties.setBatchSize(4);

        // Make the mocked TransactionTemplate actually run the consumer so
        // repository calls inside the transaction are observable. (lenient: not
        // every test exercises a transaction.)
        org.mockito.Mockito.lenient().doAnswer(invocation -> {
            Consumer<org.springframework.transaction.TransactionStatus> action = invocation.getArgument(0);
            action.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        project = Project.builder()
                .id(1L)
                .name("P")
                .slug("p")
                .build();

        // Explicit construction: EmbeddingProperties is a real POJO, not a mock.
        embeddingService = new EmbeddingService(documentRepository, userRepository,
                embeddingClient, properties, transactionTemplate);
    }

    private Document doc(long id, String title, String content) {
        return Document.builder()
                .id(id)
                .title(title)
                .content(content)
                .slug("doc-" + id)
                .project(project)
                .linkedDocuments(new ArrayList<>())
                .build();
    }

    private float[] vector(float x) {
        float[] v = new float[1024];
        v[0] = x;
        return v;
    }

    // ==================== documentText / embedAndSave ====================

    @Test
    @DisplayName("documentText is title + newline + content")
    void documentTextFormat() {
        Document d = doc(1L, "Title", "Body text");
        assertThat(embeddingService.documentText(d)).isEqualTo("Title\nBody text");
    }

    @Test
    @DisplayName("embedAndSave stores the vector literal for the document")
    void embedAndSaveSuccess() {
        Document d = doc(7L, "T", "C");
        when(embeddingClient.embedDocuments(List.of("T\nC"))).thenReturn(List.of(vector(0.25f)));

        embeddingService.embedAndSave(d);

        verify(documentRepository).updateEmbedding(7L, EmbeddingClient.toVectorLiteral(vector(0.25f)));
    }

    @Test
    @DisplayName("embedAndSave swallows sidecar failures (CRUD must not break)")
    void embedAndSaveFailureIsSwallowed() {
        Document d = doc(7L, "T", "C");
        when(embeddingClient.embedDocuments(any()))
                .thenThrow(new EmbeddingClient.EmbeddingUnavailableException("sidecar down"));

        assertThatCode(() -> embeddingService.embedAndSave(d)).doesNotThrowAnyException();
        verify(documentRepository, never()).updateEmbedding(anyLong(), anyString());
    }

    // ==================== backfill ====================

    @Test
    @DisplayName("backfill (force=false) skips documents that already have an embedding")
    void backfillSkipsEmbedded() {
        Document d1 = doc(1L, "A", "a");
        Document d2 = doc(2L, "B", "b");
        when(documentRepository.findAll()).thenReturn(List.of(d1, d2));
        when(documentRepository.findIdsWithEmbedding()).thenReturn(List.of(1L));
        when(embeddingClient.embedDocuments(List.of("B\nb"))).thenReturn(List.of(vector(0.5f)));

        embeddingService.runBackfill(false); // package-private, runs synchronously

        verify(embeddingClient).embedDocuments(List.of("B\nb"));
        verify(documentRepository).updateEmbedding(2L, EmbeddingClient.toVectorLiteral(vector(0.5f)));
        verify(documentRepository, never()).updateEmbedding(org.mockito.ArgumentMatchers.eq(1L), anyString());

        EmbeddingService.BackfillStatus status = embeddingService.getStatus();
        assertThat(status.running()).isFalse();
        assertThat(status.total()).isEqualTo(2);
        assertThat(status.skipped()).isEqualTo(1);
        assertThat(status.embedded()).isEqualTo(1);
        assertThat(status.failed()).isZero();
        assertThat(status.finishedAt()).isNotNull();
    }

    @Test
    @DisplayName("backfill (force=true) re-embeds every document")
    void backfillForceReembedsAll() {
        Document d1 = doc(1L, "A", "a");
        Document d2 = doc(2L, "B", "b");
        when(documentRepository.findAll()).thenReturn(List.of(d1, d2));
        when(embeddingClient.embedDocuments(any())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            return texts.stream().map(t -> vector(0.75f)).toList();
        });

        embeddingService.runBackfill(true);

        verify(documentRepository, never()).findIdsWithEmbedding();
        verify(documentRepository).updateEmbedding(1L, EmbeddingClient.toVectorLiteral(vector(0.75f)));
        verify(documentRepository).updateEmbedding(2L, EmbeddingClient.toVectorLiteral(vector(0.75f)));

        EmbeddingService.BackfillStatus status = embeddingService.getStatus();
        assertThat(status.force()).isTrue();
        assertThat(status.embedded()).isEqualTo(2);
        assertThat(status.skipped()).isZero();
    }

    @Test
    @DisplayName("backfill counts batch failures and continues with remaining batches")
    void backfillBatchFailureContinues() {
        List<Document> docs = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            docs.add(doc(i, "T" + i, "C" + i));
        }
        when(documentRepository.findAll()).thenReturn(docs);
        when(embeddingClient.embedDocuments(any()))
                .thenThrow(new EmbeddingClient.EmbeddingUnavailableException("first batch down"))
                .thenAnswer(inv -> {
                    List<String> texts = inv.getArgument(0);
                    return texts.stream().map(t -> vector(0.9f)).toList();
                });

        embeddingService.runBackfill(true);

        // 6 docs, batch size 4 → batch 1 (4 docs) fails, batch 2 (2 docs) succeeds
        EmbeddingService.BackfillStatus status = embeddingService.getStatus();
        assertThat(status.failed()).isEqualTo(4);
        assertThat(status.embedded()).isEqualTo(2);
        assertThat(status.lastError()).contains("first batch down");
        verify(documentRepository).updateEmbedding(org.mockito.ArgumentMatchers.eq(5L), anyString());
        verify(documentRepository).updateEmbedding(org.mockito.ArgumentMatchers.eq(6L), anyString());
    }

    @Test
    @DisplayName("startBackfill is single-flight: second start while running returns false")
    void startBackfillSingleFlight() throws InterruptedException {
        Document d1 = doc(1L, "A", "a");
        when(documentRepository.findAll()).thenReturn(List.of(d1));
        CountDownLatch block = new CountDownLatch(1);
        when(embeddingClient.embedDocuments(any())).thenAnswer(inv -> {
            block.await(10, TimeUnit.SECONDS);
            return List.of(vector(0.1f));
        });

        assertThat(embeddingService.startBackfill(false)).isTrue();
        // The backfill thread is now blocked inside the first embed call.
        Thread.sleep(300);
        assertThat(embeddingService.startBackfill(false)).isFalse();
        assertThat(embeddingService.getStatus().running()).isTrue();

        block.countDown();
        long deadline = System.currentTimeMillis() + 10_000;
        while (embeddingService.getStatus().running() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(embeddingService.getStatus().running()).isFalse();
        assertThat(embeddingService.getStatus().embedded()).isEqualTo(1);
    }

    @Test
    @DisplayName("initial status is idle")
    void initialStatusIdle() {
        EmbeddingService.BackfillStatus status = embeddingService.getStatus();
        assertThat(status.running()).isFalse();
        assertThat(status.total()).isZero();
        assertThat(status.startedAt()).isNull();
    }

    // ==================== admin check ====================

    @Test
    @DisplayName("verifyAdminRole throws SecurityException for anonymous")
    void verifyAdminRoleAnonymous() {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(null);
        try {
            assertThatCode(embeddingService::verifyAdminRole)
                    .isInstanceOf(SecurityException.class)
                    .hasMessageContaining("Authentication required");
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }
}
