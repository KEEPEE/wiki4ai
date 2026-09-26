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

    /** Visibility value: visible to every user (legacy default). */
    public static final String VISIBILITY_PUBLIC = "public";

    /** Visibility value: visible only to the owner and ADMIN users. */
    public static final String VISIBILITY_PRIVATE = "private";

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
     * Parent project (self-reference). NULL for root projects.
     * Hierarchy depth is limited to {@link #MAX_HIERARCHY_DEPTH} levels and cycles are
     * prevented in the service layer (WIKI4AI-29).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Project parent;

    /**
     * Direct child projects. Deleting a project cascades to its children at the database
     * level (ON DELETE CASCADE on parent_id, V9 migration) and via JPA cascade REMOVE.
     */
    @OneToMany(mappedBy = "parent", cascade = {CascadeType.PERSIST, CascadeType.MERGE, CascadeType.REMOVE})
    @Builder.Default
    private List<Project> children = new ArrayList<>();

    /**
     * The user who created this project (WIKI4AI-99). NULL for legacy rows —
     * those predate the owner concept and are public by definition. Never set
     * from client input: it is always the authenticated creator, assigned in
     * the service layer, and never changed afterwards.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    private User owner;

    /**
     * 'public' or 'private' (DB CHECK constraint, V14). Legacy rows carry the
     * column default 'public'; new projects default to 'public' when the
     * create request omits the field (backward compatible).
     */
    @Column(nullable = false, length = 10)
    private String visibility;

    /** Maximum allowed hierarchy depth (root project = level 1). */
    public static final int MAX_HIERARCHY_DEPTH = 5;

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
     * Set the project name.
     * <p>
     * The slug is intentionally NOT regenerated here: renaming a project must not
     * change its URL ({@code /projects/:slug}), otherwise existing links, bookmarks
     * and cross-references would break (WIKI4AI-54). Slug generation for new
     * projects happens in {@link #onCreate()} (@PrePersist); an explicit slug
     * change goes through {@link #setSlug(String)}.
     */
    public void setName(String name) {
        this.name = name;
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

    /**
     * Set the parent project (null = root). Prefer {@link #addChild(Project)} /
     * {@link #removeChild(Project)} when both sides of the relationship should stay consistent.
     */
    public void setParent(Project parent) {
        this.parent = parent;
    }

    /**
     * Set the owner (WIKI4AI-99). Called only by the service layer at create
     * time with the authenticated creator — never from client input, and never
     * changed afterwards.
     */
    public void setOwner(User owner) {
        this.owner = owner;
    }

    /**
     * Set the visibility ('public' or 'private', WIKI4AI-99). The value is
     * validated and normalized by the service layer before it reaches here.
     */
    public void setVisibility(String visibility) {
        this.visibility = visibility;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        // Auto-generate slug from name if not set
        if (slug == null || slug.isBlank()) {
            this.slug = generateSlug(this.name);
        }
        // Defensive default: the DB column has DEFAULT 'public', keep Java side consistent.
        if (visibility == null || visibility.isBlank()) {
            this.visibility = VISIBILITY_PUBLIC;
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

    /**
     * Add a child project and maintain the bidirectional parent/children relationship.
     */
    public void addChild(Project child) {
        children.add(child);
        child.setParent(this);
    }

    /**
     * Remove a child project and maintain the bidirectional parent/children relationship.
     */
    public boolean removeChild(Project child) {
        boolean removed = children.remove(child);
        // Compare by id, not reference identity: lazy proxies and their initialized
        // counterparts are different Java objects for the same entity.
        if (removed && this.getId() != null && child.getParent() != null
                && this.getId().equals(child.getParent().getId())) {
            child.setParent(null);
        }
        return removed;
    }

    /**
     * Whether this project is a root (has no parent).
     */
    public boolean isRoot() {
        return parent == null;
    }

    /**
     * Compute the hierarchy depth of this project by walking up the parent chain.
     * Root projects have depth 1. Guards against accidental cycles with a visited set
     * (the service layer guarantees acyclicity; this is a defensive measure).
     */
    public int getDepth() {
        int depth = 1;
        java.util.Set<Long> visited = new java.util.HashSet<>();
        Project current = parent;
        while (current != null) {
            if (current.getId() != null && !visited.add(current.getId())) {
                break; // defensive cycle guard — should never happen after service validation
            }
            depth++;
            current = current.getParent();
        }
        return depth;
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
