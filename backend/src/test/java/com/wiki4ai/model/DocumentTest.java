package com.wiki4ai.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the Document JPA entity.
 * Tests entity validity, slug generation, equals/hashCode, and relationships.
 */
class DocumentTest {

    private Document docA;
    private Document docB;
    private Document docC;
    private Project project;

    @BeforeEach
    void setUp() {
        project = new Project();
        project.setId(1L);
        project.setName("Test Wiki");
        project.setSlug("test-wiki");

        docA = new Document();
        docA.setTitle("Getting Started");
        docA.setContent("# Welcome\nThis is the getting started guide.");

        docB = new Document();
        docB.setTitle("Getting Started");
        docB.setContent("# Different Content\nSome other content.");

        docC = new Document();
        docC.setTitle("API Reference");
        docC.setContent("## API Docs\nEndpoints and usage.");
    }

    @Nested
    @DisplayName("Slug Generation")
    class SlugGenerationTests {

        @Test
        @DisplayName("should generate slug from simple title")
        void shouldGenerateSlugFromSimpleTitle() {
            assertThat(Document.generateSlug("Getting Started")).isEqualTo("getting-started");
        }

        @Test
        @DisplayName("should handle multiple spaces")
        void shouldHandleMultipleSpaces() {
            assertThat(Document.generateSlug("Hello   World")).isEqualTo("hello-world");
        }

        @Test
        @DisplayName("should remove special characters")
        void shouldRemoveSpecialCharacters() {
            assertThat(Document.generateSlug("API v2.0!")).isEqualTo("api-v20");
            assertThat(Document.generateSlug("Test@#$%Doc")).isEqualTo("testdoc");
        }

        @Test
        @DisplayName("should handle mixed case")
        void shouldHandleMixedCase() {
            assertThat(Document.generateSlug("My DoCuMeNt TiTlE")).isEqualTo("my-document-title");
        }

        @Test
        @DisplayName("should return empty string for null input")
        void shouldReturnEmptyForNull() {
            assertThat(Document.generateSlug(null)).isEmpty();
        }

        @Test
        @DisplayName("should return empty string for blank input")
        void shouldReturnEmptyForBlank() {
            assertThat(Document.generateSlug("   ")).isEmpty();
            assertThat(Document.generateSlug("")).isEmpty();
        }

        @Test
        @DisplayName("should handle single word without spaces")
        void shouldHandleSingleWord() {
            assertThat(Document.generateSlug("Wiki")).isEqualTo("wiki");
        }

        @Test
        @DisplayName("should collapse multiple hyphens")
        void shouldCollapseMultipleHyphens() {
            assertThat(Document.generateSlug("a---b--c")).isEqualTo("a-b-c");
        }

        @Test
        @DisplayName("should transliterate Slovak diacritics to ASCII")
        void shouldTransliterateSlovakDiacritics() {
            assertThat(Document.generateSlug("Úvod do Wiki4AI")).isEqualTo("uvod-do-wiki4ai");
            assertThat(Document.generateSlug("Špeciálne znaky")).isEqualTo("specialne-znaky");
            assertThat(Document.generateSlug("Čeština ďakujem")).isEqualTo("cestina-dakujem");
        }

        @Test
        @DisplayName("should transliterate diacritics and strip special chars for copied docs")
        void shouldHandleCopiedDocumentTitles() {
            // Simulates copy document title: "Úvod do Wiki4AI (copy)"
            assertThat(Document.generateSlug("Úvod do Wiki4AI (copy)")).isEqualTo("uvod-do-wiki4ai-copy");
            assertThat(Document.generateSlug("Názov (copy 2)")).isEqualTo("nazov-copy-2");
        }
    }

    @Nested
    @DisplayName("Auto Slug Generation on Title Set")
    class AutoSlugGenerationTests {

        @Test
        @DisplayName("should auto-generate slug when title is set via setTitle")
        void shouldAutoGenerateSlugOnSetTitle() {
            docA.setTitle("New Document");
            assertThat(docA.getSlug()).isEqualTo("new-document");
        }

        @Test
        @DisplayName("should update slug when title changes")
        void shouldUpdateSlugWhenTitleChanges() {
            String initialSlug = docA.getSlug();
            docA.setTitle("Updated Title");
            assertThat(docA.getSlug()).isNotEqualTo(initialSlug);
            assertThat(docA.getSlug()).isEqualTo("updated-title");
        }

        @Test
        @DisplayName("should set empty slug when title is blank")
        void shouldSetEmptySlugForBlankTitle() {
            docA.setTitle("");
            assertThat(docA.getSlug()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Entity Equality and HashCode")
    class EqualityTests {

        @Test
        @DisplayName("should be equal to itself")
        void shouldBeEqualToSelf() {
            assertThat(docA).isEqualTo(docA);
        }

        @Test
        @DisplayName("should not be null-equal")
        void shouldNotBeNullEqual() {
            assertThat(docA).isNotNull();
        }

        @Test
        @DisplayName("should be equal to another entity with same id")
        void shouldBeEqualToSameId() {
            Document copy = new Document();
            copy.setId(1L);
            docA.setId(1L);
            assertThat(docA).isEqualTo(copy);
        }

        @Test
        @DisplayName("should not be equal to entity with different id")
        void shouldNotEqualDifferentId() {
            docA.setId(1L);
            docC.setId(2L);
            assertThat(docA).isNotEqualTo(docC);
        }

        @Test
        @DisplayName("should have consistent hashCode for same object")
        void shouldHaveConsistentHashCode() {
            int hash1 = docA.hashCode();
            int hash2 = docA.hashCode();
            assertThat(hash1).isEqualTo(hash2);
        }

        @Test
        @DisplayName("equal objects must have same hashCode")
        void equalObjectsMustHaveSameHashCode() {
            Document copy = new Document();
            copy.setId(5L);
            docA.setId(5L);
            assertThat(docA.hashCode()).isEqualTo(copy.hashCode());
        }

        @Test
        @DisplayName("should not be equal to unrelated type")
        void shouldNotEqualUnrelatedType() {
            assertThat(docA).isNotEqualTo("string");
        }
    }

    @Nested
    @DisplayName("Entity Fields and Validation")
    class FieldTests {

        @Test
        @DisplayName("should create entity with all fields via builder")
        void shouldCreateWithBuilder() {
            Document doc = Document.builder()
                    .id(1L)
                    .title("Test Doc")
                    .content("# Content")
                    .slug("test-doc")
                    .build();

            assertThat(doc.getId()).isEqualTo(1L);
            assertThat(doc.getTitle()).isEqualTo("Test Doc");
            assertThat(doc.getContent()).isEqualTo("# Content");
            assertThat(doc.getSlug()).isEqualTo("test-doc");
        }

        @Test
        @DisplayName("should have non-null createdAt and updatedAt after persistence")
        void shouldHaveTimestampsAfterPersistence() {
            // Simulate @PrePersist by calling onCreate manually
            docA.onCreate();

            assertThat(docA.getCreatedAt()).isNotNull();
            assertThat(docA.getUpdatedAt()).isNotNull();
            assertThat(docA.getCreatedAt()).isBeforeOrEqualTo(LocalDateTime.now());
        }

        @Test
        @DisplayName("should update updatedAt on preUpdate")
        void shouldUpdateUpdatedAtOnPreUpdate() {
            docA.onCreate();
            LocalDateTime originalUpdated = docA.getUpdatedAt();

            // Simulate time passing
            try { Thread.sleep(10); } catch (InterruptedException e) { /* ignore */ }

            docA.onUpdate();

            assertThat(docA.getUpdatedAt()).isAfter(originalUpdated);
        }

        @Test
        @DisplayName("should generate slug on prePersist if not set")
        void shouldGenerateSlugOnPrePersist() {
            Document doc = new Document();
            doc.setTitle("Auto Slug Test");
            // Don't set slug - it should be generated in onCreate

            doc.onCreate();

            assertThat(doc.getSlug()).isEqualTo("auto-slug-test");
        }

        @Test
        @DisplayName("should preserve existing slug on prePersist")
        void shouldPreserveExistingSlug() {
            Document doc = new Document();
            doc.setTitle("Some Title");
            doc.setSlug("custom-slug");

            doc.onCreate();

            assertThat(doc.getSlug()).isEqualTo("custom-slug");
        }

        @Test
        @DisplayName("should have correct toString output")
        void shouldHaveCorrectToString() {
            Document doc = new Document();
            doc.setId(1L);
            doc.setTitle("My Document");
            doc.setSlug("my-document");
            doc.setContent("# Hello");

            String str = doc.toString();

            assertThat(str).contains("id=1");
            assertThat(str).contains("title='My Document'");
            assertThat(str).contains("slug='my-document'");
        }

        @Test
        @DisplayName("should set and get all fields via setters")
        void shouldSetAndGetAllFields() {
            Document doc = new Document();
            doc.setId(42L);
            doc.setTitle("Full Test");
            doc.setContent("# Full content");
            doc.setSlug("full-test");

            assertThat(doc.getId()).isEqualTo(42L);
            assertThat(doc.getTitle()).isEqualTo("Full Test");
            assertThat(doc.getContent()).isEqualTo("# Full content");
            assertThat(doc.getSlug()).isEqualTo("full-test");
        }
    }

    @Nested
    @DisplayName("Linked Documents Relationship")
    class LinkedDocumentsTests {

        @Test
        @DisplayName("should start with empty linked documents list")
        void shouldStartWithEmptyLinkedDocuments() {
            Document doc = new Document();
            assertThat(doc.getLinkedDocuments()).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("should add linked document")
        void shouldAddLinkedDocument() {
            Document source = new Document();
            source.setId(1L);
            source.setTitle("Source");

            Document target = new Document();
            target.setId(2L);
            target.setTitle("Target");

            source.addLinkedDocument(target);

            assertThat(source.getLinkedDocuments()).hasSize(1);
            assertThat(source.getLinkedDocuments().get(0)).isEqualTo(target);
        }

        @Test
        @DisplayName("should remove linked document")
        void shouldRemoveLinkedDocument() {
            Document source = new Document();
            source.setId(1L);
            source.setTitle("Source");

            Document target = new Document();
            target.setId(2L);
            target.setTitle("Target");

            source.addLinkedDocument(target);
            source.removeLinkedDocument(target);

            assertThat(source.getLinkedDocuments()).isEmpty();
        }

        @Test
        @DisplayName("should handle multiple linked documents")
        void shouldHandleMultipleLinkedDocuments() {
            Document source = new Document();
            source.setId(1L);
            source.setTitle("Source");

            for (int i = 0; i < 5; i++) {
                Document target = new Document();
                target.setId((long) (i + 2));
                target.setTitle("Target " + i);
                source.addLinkedDocument(target);
            }

            assertThat(source.getLinkedDocuments()).hasSize(5);
        }

        @Test
        @DisplayName("should handle builder-created document with linked documents")
        void shouldHandleBuilderWithLinkedDocuments() {
            Document target = new Document();
            target.setId(2L);
            target.setTitle("Target");

            Document source = Document.builder()
                    .id(1L)
                    .title("Source")
                    .slug("source")
                    .linkedDocuments(List.of(target))
                    .build();

            assertThat(source.getLinkedDocuments()).hasSize(1);
            assertThat(source.getTitle()).isEqualTo("Source");
        }
    }

    @Nested
    @DisplayName("Project Relationship")
    class ProjectRelationshipTests {

        @Test
        @DisplayName("should set and get project reference")
        void shouldSetAndGetProject() {
            Document doc = new Document();
            doc.setId(1L);
            doc.setTitle("Doc");
            doc.setSlug("doc");

            doc.setProject(project);

            assertThat(doc.getProject()).isEqualTo(project);
            assertThat(doc.getProject().getId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("should create document with project via builder")
        void shouldCreateWithProjectViaBuilder() {
            Document doc = Document.builder()
                    .id(1L)
                    .title("Wiki Doc")
                    .content("# Content")
                    .slug("wiki-doc")
                    .project(project)
                    .build();

            assertThat(doc.getProject()).isEqualTo(project);
            assertThat(doc.getTitle()).isEqualTo("Wiki Doc");
        }
    }
}
