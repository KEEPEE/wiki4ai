package com.wiki4ai.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * JPA Entity representing a Wiki Document.
 * A document contains markdown content and can link to other documents via [[Document]] syntax.
 */
@Entity
@Table(name = "documents", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"project_id", "slug"})
})
@Getter
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    @Column(name = "slug", nullable = false)
    private String slug;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "document_links",
        joinColumns = @JoinColumn(name = "source_document_id"),
        inverseJoinColumns = @JoinColumn(name = "target_document_id")
    )
    @Builder.Default
    private List<Document> linkedDocuments = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * Generate a URL-friendly slug from the document title.
     * Examples: "My Document" → "my-document", "Hello World!" → "hello-world"
     */
    public static String generateSlug(String title) {
        if (title == null || title.isBlank()) {
            return "";
        }
        return title.toLowerCase()
                .replaceAll("[^a-z0-9\\s-]", "")
                .replaceAll("\\s+", "-")
                .replaceAll("-+", "-")
                .trim()
                .replaceAll("^-|-$", "");
    }

    /**
     * Set the document title. Slug is preserved to maintain stable URLs.
     */
    public void setTitle(String title) {
        this.title = title;
    }

    /**
     * Set the slug directly (bypasses auto-generation).
     */
    public void setSlug(String slug) {
        this.slug = slug;
    }

    /**
     * Set the document content.
     */
    public void setContent(String content) {
        this.content = content;
    }

    /**
     * Set the document id. Used mainly for testing and entity comparison.
     */
    public void setId(Long id) {
        this.id = id;
    }

    /**
     * Set the project reference. Used by bidirectional relationship maintenance.
     */
    public void setProject(Project project) {
        this.project = project;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        // Auto-generate slug from title if not set
        if (slug == null || slug.isBlank()) {
            this.slug = generateSlug(this.title);
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * Add a document link and maintain the bidirectional relationship.
     */
    public void addLinkedDocument(Document document) {
        linkedDocuments.add(document);
    }

    /**
     * Remove a document link.
     */
    public void removeLinkedDocument(Document document) {
        linkedDocuments.remove(document);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Document document = (Document) o;
        return id != null && Objects.equals(id, document.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return "Document{" +
                "id=" + id +
                ", title='" + title + '\'' +
                ", slug='" + slug + '\'' +
                ", projectId=" + (project != null ? project.getId() : null) +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                '}';
    }
}
