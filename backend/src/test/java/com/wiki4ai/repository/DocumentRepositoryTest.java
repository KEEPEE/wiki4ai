package com.wiki4ai.repository;

import com.wiki4ai.model.Document;
import com.wiki4ai.model.Project;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Data JPA tests for DocumentRepository.
 */
@DataJpaTest
@ActiveProfiles("test")
class DocumentRepositoryTest {

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private ProjectRepository projectRepository;

    private Project createAndSaveProject(String name, String slug) {
        return projectRepository.save(Project.builder()
                .name(name)
                .slug(slug)
                .build());
    }

    @Test
    @DisplayName("Should find document by slug and project ID")
    void shouldFindBySlugAndProjectId() {
        // given
        Project project = createAndSaveProject("Wiki", "wiki");
        Document doc = Document.builder()
                .title("Home Page")
                .content("# Welcome to Wiki")
                .slug("home-page")
                .project(project)
                .build();
        documentRepository.save(doc);

        // when
        Optional<Document> found = documentRepository.findBySlugAndProjectId("home-page", project.getId());

        // then
        assertThat(found).isPresent();
        assertThat(found.get().getTitle()).isEqualTo("Home Page");
    }

    @Test
    @DisplayName("Should return empty when slug and project ID not found")
    void shouldReturnEmptyWhenSlugAndProjectIdNotFound() {
        // given
        Project project = createAndSaveProject("Wiki", "wiki");

        // when
        Optional<Document> found = documentRepository.findBySlugAndProjectId("non-existent", project.getId());

        // then
        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("Should find documents by project ID ordered by update date descending")
    void shouldFindByProjectIdOrderByUpdatedAtDesc() {
        // given
        Project project = createAndSaveProject("Wiki", "wiki");
        Document older = Document.builder()
                .title("Older Doc")
                .content("# Older")
                .slug("older-doc")
                .project(project)
                .build();
        Document newer = Document.builder()
                .title("Newer Doc")
                .content("# Newer")
                .slug("newer-doc")
                .project(project)
                .build();

        documentRepository.save(older);
        try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        documentRepository.save(newer);

        // when
        List<Document> docs = documentRepository.findByProjectIdOrderByUpdatedAtDesc(project.getId());

        // then
        assertThat(docs).hasSize(2);
        assertThat(docs.get(0).getTitle()).isEqualTo("Newer Doc");
        assertThat(docs.get(1).getTitle()).isEqualTo("Older Doc");
    }

    @Test
    @DisplayName("Should find documents by project ID")
    void shouldFindByProjectId() {
        // given
        Project project = createAndSaveProject("Wiki", "wiki");
        Document doc1 = Document.builder().title("Doc 1").content("# Doc 1").slug("doc-1").project(project).build();
        Document doc2 = Document.builder().title("Doc 2").content("# Doc 2").slug("doc-2").project(project).build();
        documentRepository.save(doc1);
        documentRepository.save(doc2);

        // when
        List<Document> docs = documentRepository.findByProjectId(project.getId());

        // then
        assertThat(docs).hasSize(2);
    }

    @Test
    @DisplayName("Should find document by project ID and title")
    void shouldFindByProjectIdAndTitle() {
        // given
        Project project = createAndSaveProject("Wiki", "wiki");
        Document doc = Document.builder()
                .title("Specific Title")
                .content("# Content")
                .slug("specific-title")
                .project(project)
                .build();
        documentRepository.save(doc);

        // when
        Optional<Document> found = documentRepository.findByProjectIdAndTitle(project.getId(), "Specific Title");

        // then
        assertThat(found).isPresent();
        assertThat(found.get().getContent()).isEqualTo("# Content");
    }

    @Test
    @DisplayName("Should search documents by content keyword")
    void shouldFindByProjectIdAndContentContaining() {
        // given
        Project project = createAndSaveProject("Wiki", "wiki");
        Document doc1 = Document.builder().title("Java Doc").content("# Java Tutorial").slug("java-doc").project(project).build();
        Document doc2 = Document.builder().title("Python Doc").content("# Python Guide").slug("python-doc").project(project).build();
        documentRepository.save(doc1);
        documentRepository.save(doc2);

        // when
        List<Document> results = documentRepository.findByProjectIdAndContentContaining(project.getId(), "java");

        // then
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getTitle()).isEqualTo("Java Doc");
    }

    @Test
    @DisplayName("Should count documents by project ID")
    void shouldCountByProjectId() {
        // given
        Project project = createAndSaveProject("Wiki", "wiki");
        documentRepository.save(Document.builder().title("D1").content("# D1").slug("d1").project(project).build());
        documentRepository.save(Document.builder().title("D2").content("# D2").slug("d2").project(project).build());
        documentRepository.save(Document.builder().title("D3").content("# D3").slug("d3").project(project).build());

        // when & then
        assertThat(documentRepository.countByProjectId(project.getId())).isEqualTo(3);
    }

    @Test
    @DisplayName("Should save and retrieve document with timestamps")
    void shouldSaveAndRetrieveWithTimestamps() {
        // given
        Project project = createAndSaveProject("Wiki", "wiki");
        Document doc = Document.builder()
                .title("Timestamped Doc")
                .content("# Content")
                .slug("timestamped-doc")
                .project(project)
                .build();

        // when
        Document saved = documentRepository.save(doc);

        // then
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("Should delete document by id")
    void shouldDeleteDocumentById() {
        // given
        Project project = createAndSaveProject("Wiki", "wiki");
        Document saved = documentRepository.save(Document.builder()
                .title("To Delete")
                .content("# Delete me")
                .slug("to-delete")
                .project(project)
                .build());
        Long id = saved.getId();

        // when
        documentRepository.deleteById(id);

        // then
        assertThat(documentRepository.findById(id)).isEmpty();
    }
}
