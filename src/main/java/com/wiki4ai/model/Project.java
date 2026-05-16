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
 * JPA Entity representing a Wiki Project.
 * A project contains multiple documents and serves as the top-level organizational unit.
 */
@Entity
@Table(name = "projects")
@Getter
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(length = 1000)
    private String description;

    @Column(name = "slug", nullable = false, unique = true)
    private String slug;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "project", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<Document> documents = new ArrayList<>();

    /**
     * Generate a URL-friendly slug from the project name.
     * Examples: "My Wiki" → "my-wiki", "Hello World!" → "hello-world"
     */
    public static String generateSlug(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        return name.toLowerCase()
                .replaceAll("[^a-z0-9\\s-]", "")
                .replaceAll("\\s+", "-")
                .replaceAll("-+", "-")
                .trim()
                .replaceAll("^-|-$", "");
    }

    /**
     * Set the project name and automatically generate a slug from it.
     */
    public void setName(String name) {
        this.name = name;
        if (name != null && !name.isBlank()) {
            this.slug = generateSlug(name);
        } else {
            this.slug = "";
        }
    }

    /**
     * Set the project description.
     */
    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * Set the slug directly (bypasses auto-generation).
     */
    public void setSlug(String slug) {
        this.slug = slug;
    }

    /**
     * Set the project id. Used mainly for testing and entity comparison.
     */
    public void setId(Long id) {
        this.id = id;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        // Auto-generate slug from name if not set
        if (slug == null || slug.isBlank()) {
            this.slug = generateSlug(this.name);
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * Add a document to this project and maintain the bidirectional relationship.
     */
    public void addDocument(Document document) {
        documents.add(document);
        document.setProject(this);
    }

    /**
     * Remove a document from this project and maintain the bidirectional relationship.
     */
    public void removeDocument(Document document) {
        documents.remove(document);
        document.setProject(null);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Project project = (Project) o;
        return id != null && Objects.equals(id, project.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return "Project{" +
                "id=" + id +
                ", name='" + name + '\'' +
                ", slug='" + slug + '\'' +
                ", description='" + (description != null && description.length() > 50 ? description.substring(0, 50) + "..." : description) + '\'' +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                '}';
    }
}
