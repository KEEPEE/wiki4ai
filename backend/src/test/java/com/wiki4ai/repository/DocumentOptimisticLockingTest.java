package com.wiki4ai.repository;

import com.wiki4ai.model.Document;
import com.wiki4ai.model.Project;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WIKI4AI-72: JPA-level tests for document optimistic locking (@Version).
 *
 * <p>Uses independent REQUIRES_NEW transactions with a controlled interleaving —
 * writer B holds its stale state open while writer A commits in between (nested
 * suspension, deterministic on any database, no thread timing involved). Proves that:</p>
 * <ul>
 *   <li>a new document starts at version 0 and every save increments it;</li>
 *   <li>a concurrent commit on a stale state fails with
 *       ObjectOptimisticLockingFailureException instead of silently overwriting;</li>
 *   <li>sequential updates without expectedVersion keep working (backward compat).</li>
 * </ul>
 *
 * <p>Test methods run WITHOUT the @DataJpaTest implicit transaction
 * (Propagation.NOT_SUPPORTED) so that every unit of work is a real, independently
 * committed transaction; created rows are removed in {@link #cleanup()}.</p>
 */
@DataJpaTest
@ActiveProfiles("test")
@DisplayName("Document optimistic locking (@Version, WIKI4AI-72)")
class DocumentOptimisticLockingTest {

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Long projectId;

    /** Each call returns a definition for an independent (REQUIRES_NEW) transaction. */
    private TransactionDefinition newTransaction() {
        DefaultTransactionDefinition def = new DefaultTransactionDefinition();
        def.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return def;
    }

    @AfterEach
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void cleanup() {
        if (projectId == null) {
            return;
        }
        runInNewTx(() -> {
            for (Document doc : documentRepository.findByProjectId(projectId)) {
                documentRepository.delete(doc);
            }
            projectRepository.findById(projectId).ifPresent(projectRepository::delete);
            return null;
        });
    }

    /** Create a project + one document in a committed transaction; returns the document id. */
    private Long createProjectAndDocument(String title, String content) {
        return runInNewTx(() -> {
            Project project = projectRepository.save(Project.builder()
                    .name("Optimistic Locking Project")
                    .slug("optimistic-locking-project-" + System.nanoTime())
                    .build());
            projectId = project.getId();
            Document doc = Document.builder()
                    .title(title)
                    .content(content)
                    .slug(title.toLowerCase().replace(' ', '-'))
                    .project(project)
                    .build();
            documentRepository.save(doc);
            return doc.getId();
        });
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("New document starts at version 0 and each save increments it")
    void versionStartsAtZeroAndIncrementsOnEachSave() {
        Long docId = createProjectAndDocument("Versioned Doc", "initial content");

        // initial version is 0 (Hibernate initialises a null @Version to 0 on insert)
        Long initial = runInNewTx(() -> documentRepository.findById(docId).orElseThrow().getVersion());
        assertThat(initial).isEqualTo(0L);

        // first update: 0 -> 1
        Long afterFirst = runInNewTx(() -> {
            Document doc = documentRepository.findById(docId).orElseThrow();
            doc.setContent("first update");
            documentRepository.save(doc);
            documentRepository.flush();
            return doc.getVersion();
        });
        assertThat(afterFirst).isEqualTo(1L);

        // second update: 1 -> 2
        Long afterSecond = runInNewTx(() -> {
            Document doc = documentRepository.findById(docId).orElseThrow();
            doc.setContent("second update");
            documentRepository.save(doc);
            documentRepository.flush();
            return doc.getVersion();
        });
        assertThat(afterSecond).isEqualTo(2L);

        // and the persisted row agrees
        Long persisted = runInNewTx(() -> documentRepository.findById(docId).orElseThrow().getVersion());
        assertThat(persisted).isEqualTo(2L);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("Concurrent writes without expectedVersion: second writer gets ObjectOptimisticLockingFailureException, no silent overwrite")
    void concurrentWriteSecondWriterFails() {
        Long docId = createProjectAndDocument("Race Doc", "base content");

        // Writer B opens a transaction and reads the version-0 state. It stays open
        // (holding its stale snapshot) while writer A commits in between.
        TransactionStatus txB = transactionManager.getTransaction(newTransaction());
        Document b = documentRepository.findById(docId).orElseThrow();
        assertThat(b.getVersion()).isEqualTo(0L);
        b.setContent("writer B content");
        documentRepository.save(b);

        // Writer A (nested REQUIRES_NEW — standard suspension) reads the SAME
        // version-0 state, modifies and commits first: version 0 -> 1.
        runInNewTx(() -> {
            Document a = documentRepository.findById(docId).orElseThrow();
            assertThat(a.getVersion()).isEqualTo(0L);
            a.setContent("writer A content");
            documentRepository.save(a);
            return null;
        });

        // B now flushes (what DocumentService does right after save): its
        // UPDATE ... WHERE id=? AND version=0 matches no row -> conflict.
        assertThatThrownBy(() -> documentRepository.flush())
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        transactionManager.rollback(txB);

        // The database holds A's write — B's stale write was rejected, not applied.
        Document persisted = runInNewTx(() -> documentRepository.findById(docId).orElseThrow());
        assertThat(persisted.getContent()).isEqualTo("writer A content");
        assertThat(persisted.getVersion()).isEqualTo(1L);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("Sequential updates without expectedVersion keep working (backward compatibility)")
    void sequentialUpdatesWithoutExpectedVersionWork() {
        Long docId = createProjectAndDocument("Sequential Doc", "v0");

        runInNewTx(() -> {
            Document doc = documentRepository.findById(docId).orElseThrow();
            doc.setContent("v1");
            documentRepository.save(doc);
            return null;
        });
        runInNewTx(() -> {
            Document doc = documentRepository.findById(docId).orElseThrow();
            doc.setContent("v2");
            documentRepository.save(doc);
            return null;
        });

        Document persisted = runInNewTx(() -> documentRepository.findById(docId).orElseThrow());
        assertThat(persisted.getContent()).isEqualTo("v2");
        assertThat(persisted.getVersion()).isEqualTo(2L);
    }

    /** Run a unit of work in its own REQUIRES_NEW transaction, committing on success. */
    private <T> T runInNewTx(Supplier<T> work) {
        TransactionStatus tx = transactionManager.getTransaction(newTransaction());
        try {
            T result = work.get();
            transactionManager.commit(tx);
            return result;
        } catch (RuntimeException e) {
            transactionManager.rollback(tx);
            throw e;
        }
    }
}
