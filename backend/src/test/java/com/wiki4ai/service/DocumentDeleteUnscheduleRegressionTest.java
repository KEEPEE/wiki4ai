package com.wiki4ai.service;

import com.wiki4ai.model.Document;
import com.wiki4ai.model.Project;
import com.wiki4ai.repository.DocumentRepository;
import com.wiki4ai.repository.ProjectRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for WIKI4AI-37: "DELETE returns 204 but the row survives".
 *
 * <p>Root cause: {@code Project.documents} is mapped with {@code cascade = CascadeType.ALL,
 * orphanRemoval = true}. When a document is deleted via {@code em.remove()} while the parent
 * collection has already been initialized in the same session (as always happens in the API
 * flow, where project DTO conversion touches {@code getDocuments()} before the delete), the
 * flush-time PERSIST_ON_FLUSH cascade walks the collection, finds the just-deleted element
 * (its EntityEntry still exists with status DELETED) and calls
 * {@code ActionQueue.unScheduleDeletion()} — silently cancelling the pending delete. The flush
 * then executes zero deletions and the row survives the commit.</p>
 *
 * <p>The fix removes the document from {@code project.getDocuments()} before deleting it, so
 * the flush-time cascade no longer sees it.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class DocumentDeleteUnscheduleRegressionTest {

    @Autowired
    private DocumentService documentService;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Test
    @Transactional
    @DisplayName("deleteDocumentBySlug must remove the row when the parent collection was initialized first")
    void deleteAfterCollectionInitializationRemovesRow() {
        // Phase 1 (mirrors resolveProjectId/getProjectBySlug in the same OSIV session):
        Project project = projectRepository.save(Project.builder()
                .name("DelUnsched").slug("delunsched-" + System.nanoTime()).build());
        Document doc = Document.builder()
                .title("Delete me unsched")
                .content("body")
                .slug("delete-me-unsched-" + System.nanoTime())
                .project(project)
                .build();
        // Maintain the bidirectional association so the parent collection actually contains
        // the document (mirrors production, where the bag is hydrated from the DB):
        project.getDocuments().add(doc);
        documentRepository.save(doc);
        projectRepository.flush();

        // Initialize the parent collection in this session — this is what triggers the bug:
        // the flush-time PERSIST_ON_FLUSH cascade then walks the bag and un-schedules the delete.
        assertThat(project.getDocuments()).isNotEmpty();

        Long docId = doc.getId();
        String slug = doc.getSlug();

        // Phase 2 (the DELETE endpoint, same session):
        documentService.deleteDocumentBySlug(project.getId(), slug, null);

        // The existsById query triggers an auto-flush; with the bug present the deletion was
        // un-scheduled during flush, so the row would still be found.
        assertThat(documentRepository.existsById(docId))
                .as("document %d must be gone after deleteDocumentBySlug (un-schedule regression)", docId)
                .isFalse();
    }

    @Test
    @Transactional
    @DisplayName("deleteDocument by id must remove the row when the parent collection was initialized first")
    void deleteByIdAfterCollectionInitializationRemovesRow() {
        Project project = projectRepository.save(Project.builder()
                .name("DelUnsched2").slug("delunsched2-" + System.nanoTime()).build());
        Document doc = Document.builder()
                .title("Delete me unsched 2")
                .content("body")
                .slug("delete-me-unsched2-" + System.nanoTime())
                .project(project)
                .build();
        project.getDocuments().add(doc);
        documentRepository.save(doc);
        projectRepository.flush();

        assertThat(project.getDocuments()).isNotEmpty();

        Long docId = doc.getId();

        documentService.deleteDocument(docId, null);

        assertThat(documentRepository.existsById(docId))
                .as("document %d must be gone after deleteDocument (un-schedule regression)", docId)
                .isFalse();
    }
}
