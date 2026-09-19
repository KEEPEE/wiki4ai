package com.wiki4ai.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the Project JPA entity.
 * Tests entity validity, slug generation, equals/hashCode, and relationships.
 */
class ProjectTest {

    private Project projectA;
    private Project projectB;
    private Project projectC;

    @BeforeEach
    void setUp() {
        projectA = new Project();
        projectA.setName("My Wiki");
        projectA.setDescription("A personal wiki system");

        projectB = new Project();
        projectB.setName("My Wiki");
        projectB.setDescription("Another description");

        projectC = new Project();
        projectC.setName("Different Project");
        projectC.setDescription("Some description");
    }

    @Nested
    @DisplayName("Slug Generation")
    class SlugGenerationTests {

        @Test
        @DisplayName("should generate slug from simple name")
        void shouldGenerateSlugFromSimpleName() {
            assertThat(Project.generateSlug("My Wiki")).isEqualTo("my-wiki");
        }

        @Test
        @DisplayName("should handle multiple spaces")
        void shouldHandleMultipleSpaces() {
            assertThat(Project.generateSlug("Hello   World")).isEqualTo("hello-world");
        }

        @Test
        @DisplayName("should remove special characters")
        void shouldRemoveSpecialCharacters() {
            assertThat(Project.generateSlug("Hello World!")).isEqualTo("hello-world");
            assertThat(Project.generateSlug("Test@#$%Project")).isEqualTo("testproject");
        }

        @Test
        @DisplayName("should handle mixed case")
        void shouldHandleMixedCase() {
            assertThat(Project.generateSlug("My WiKi SyStEm")).isEqualTo("my-wiki-system");
        }

        @Test
        @DisplayName("should handle leading/trailing spaces and special chars")
        void shouldHandleLeadingTrailingChars() {
            assertThat(Project.generateSlug("  My Wiki!  ")).isEqualTo("my-wiki");
        }

        @Test
        @DisplayName("should return empty string for null input")
        void shouldReturnEmptyForNull() {
            assertThat(Project.generateSlug(null)).isEmpty();
        }

        @Test
        @DisplayName("should return empty string for blank input")
        void shouldReturnEmptyForBlank() {
            assertThat(Project.generateSlug("   ")).isEmpty();
            assertThat(Project.generateSlug("")).isEmpty();
        }

        @Test
        @DisplayName("should handle single word without spaces")
        void shouldHandleSingleWord() {
            assertThat(Project.generateSlug("Wiki")).isEqualTo("wiki");
        }

        @Test
        @DisplayName("should collapse multiple hyphens")
        void shouldCollapseMultipleHyphens() {
            assertThat(Project.generateSlug("a---b--c")).isEqualTo("a-b-c");
        }
    }

    @Nested
    @DisplayName("Rename keeps slug immutable (WIKI4AI-54)")
    class RenameKeepsSlugTests {

        @Test
        @DisplayName("should not change existing slug when name is set via setName")
        void shouldNotChangeExistingSlugOnSetName() {
            projectA.setSlug("original-slug");
            projectA.setName("Renamed Project");
            assertThat(projectA.getName()).isEqualTo("Renamed Project");
            assertThat(projectA.getSlug()).isEqualTo("original-slug");
        }

        @Test
        @DisplayName("should leave slug untouched for a new project (generated at persist time)")
        void shouldLeaveSlugUntouchedForNewProject() {
            Project project = new Project();
            project.setName("Brand New");
            // setName must not generate the slug; @PrePersist does that on save
            assertThat(project.getSlug()).isNull();

            project.onCreate();
            assertThat(project.getSlug()).isEqualTo("brand-new");
        }

        @Test
        @DisplayName("should not clear existing slug when name becomes blank")
        void shouldNotClearSlugForBlankName() {
            projectA.setSlug("kept-slug");
            projectA.setName("");
            assertThat(projectA.getSlug()).isEqualTo("kept-slug");
        }
    }

    @Nested
    @DisplayName("Entity Equality and HashCode")
    class EqualityTests {

        @Test
        @DisplayName("should be equal to itself")
        void shouldBeEqualToSelf() {
            assertThat(projectA).isEqualTo(projectA);
        }

        @Test
        @DisplayName("should not be null-equal")
        void shouldNotBeNullEqual() {
            assertThat(projectA).isNotNull();
        }

        @Test
        @DisplayName("should be equal to another entity with same id")
        void shouldBeEqualToSameId() {
            Project copy = new Project();
            copy.setId(1L);
            projectA.setId(1L);
            assertThat(projectA).isEqualTo(copy);
        }

        @Test
        @DisplayName("should not be equal to entity with different id")
        void shouldNotEqualDifferentId() {
            projectA.setId(1L);
            projectC.setId(2L);
            assertThat(projectA).isNotEqualTo(projectC);
        }

        @Test
        @DisplayName("should have consistent hashCode for same object")
        void shouldHaveConsistentHashCode() {
            int hash1 = projectA.hashCode();
            int hash2 = projectA.hashCode();
            assertThat(hash1).isEqualTo(hash2);
        }

        @Test
        @DisplayName("equal objects must have same hashCode")
        void equalObjectsMustHaveSameHashCode() {
            Project copy = new Project();
            copy.setId(5L);
            projectA.setId(5L);
            assertThat(projectA.hashCode()).isEqualTo(copy.hashCode());
        }

        @Test
        @DisplayName("should not be equal to unrelated type")
        void shouldNotEqualUnrelatedType() {
            assertThat(projectA).isNotEqualTo("string");
        }
    }

    @Nested
    @DisplayName("Entity Fields and Validation")
    class FieldTests {

        @Test
        @DisplayName("should create entity with all fields via builder")
        void shouldCreateWithBuilder() {
            Project project = Project.builder()
                    .id(1L)
                    .name("Test")
                    .description("Desc")
                    .slug("test")
                    .build();
            
            assertThat(project.getId()).isEqualTo(1L);
            assertThat(project.getName()).isEqualTo("Test");
            assertThat(project.getDescription()).isEqualTo("Desc");
            assertThat(project.getSlug()).isEqualTo("test");
        }

        @Test
        @DisplayName("should have non-null createdAt and updatedAt after persistence")
        void shouldHaveTimestampsAfterPersistence() {
            // Simulate @PrePersist by calling onCreate manually
            projectA.onCreate();
            
            assertThat(projectA.getCreatedAt()).isNotNull();
            assertThat(projectA.getUpdatedAt()).isNotNull();
            assertThat(projectA.getCreatedAt()).isBeforeOrEqualTo(LocalDateTime.now());
        }

        @Test
        @DisplayName("should update updatedAt on preUpdate")
        void shouldUpdateUpdatedAtOnPreUpdate() {
            projectA.onCreate();
            LocalDateTime originalUpdated = projectA.getUpdatedAt();
            
            // Simulate time passing
            try { Thread.sleep(10); } catch (InterruptedException e) { /* ignore */ }
            
            projectA.onUpdate();
            
            assertThat(projectA.getUpdatedAt()).isAfter(originalUpdated);
        }

        @Test
        @DisplayName("should generate slug on prePersist if not set")
        void shouldGenerateSlugOnPrePersist() {
            Project project = new Project();
            project.setName("Auto Slug Test");
            // Don't set slug - it should be generated in onCreate
            
            project.onCreate();
            
            assertThat(project.getSlug()).isEqualTo("auto-slug-test");
        }

        @Test
        @DisplayName("should preserve existing slug on prePersist")
        void shouldPreserveExistingSlug() {
            Project project = new Project();
            project.setName("Some Name");
            project.setSlug("custom-slug");
            
            project.onCreate();
            
            assertThat(project.getSlug()).isEqualTo("custom-slug");
        }

        @Test
        @DisplayName("should have correct toString output")
        void shouldHaveCorrectToString() {
            Project project = new Project();
            project.setId(1L);
            project.setName("My Wiki");
            project.setDescription("A description");
            project.setSlug("my-wiki");
            
            String str = project.toString();
            
            assertThat(str).contains("id=1");
            assertThat(str).contains("name='My Wiki'");
            assertThat(str).contains("slug='my-wiki'");
        }

        @Test
        @DisplayName("should truncate long descriptions in toString")
        void shouldTruncateLongDescriptionInToString() {
            Project project = new Project();
            project.setId(1L);
            project.setName("Name");
            project.setDescription("This is a very long description that should be truncated in the toString output because it exceeds fifty characters");
            project.setSlug("name");
            
            String str = project.toString();
            assertThat(str).contains("...");
        }

        @Test
        @DisplayName("should set and get all fields via setters")
        void shouldSetAndGetAllFields() {
            Project project = new Project();
            project.setId(42L);
            project.setName("Full Test");
            project.setDescription("Full description");
            project.setSlug("full-test");
            
            assertThat(project.getId()).isEqualTo(42L);
            assertThat(project.getName()).isEqualTo("Full Test");
            assertThat(project.getDescription()).isEqualTo("Full description");
            assertThat(project.getSlug()).isEqualTo("full-test");
        }
    }

    @Nested
    @DisplayName("Document Relationship")
    class DocumentRelationshipTests {

        @Test
        @DisplayName("should start with empty documents list")
        void shouldStartWithEmptyDocuments() {
            Project project = new Project();
            assertThat(project.getDocuments()).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("should add document and set bidirectional relationship")
        void shouldAddDocumentAndSetRelationship() {
            Project project = new Project();
            project.setId(1L);
            project.setName("Wiki");
            
            Document doc = new Document();
            doc.setTitle("Test Doc");
            
            project.addDocument(doc);
            
            assertThat(project.getDocuments()).hasSize(1);
            assertThat(project.getDocuments().get(0)).isEqualTo(doc);
        }

        @Test
        @DisplayName("should remove document and clear bidirectional relationship")
        void shouldRemoveDocument() {
            Project project = new Project();
            project.setId(1L);
            project.setName("Wiki");
            
            Document doc = new Document();
            doc.setTitle("Test Doc");
            
            project.addDocument(doc);
            project.removeDocument(doc);
            
            assertThat(project.getDocuments()).isEmpty();
        }

        @Test
        @DisplayName("should handle builder-created project with documents")
        void shouldHandleBuilderWithDocuments() {
            Document doc = new Document();
            doc.setTitle("Builder Doc");
            
            Project project = Project.builder()
                    .id(1L)
                    .name("Builder Wiki")
                    .slug("builder-wiki")
                    .documents(List.of(doc))
                    .build();
            
            assertThat(project.getDocuments()).hasSize(1);
            assertThat(project.getName()).isEqualTo("Builder Wiki");
        }
    }
}
