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

    /** Visibility value: visible to every user (legacy default). */
    public static final String VISIBILITY_PUBLIC = "public";

    /** Visibility value: visible only to the owner and ADMIN users. */
    public static final String VISIBILITY_PRIVATE = "private";

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
     * Optimistic locking version (WIKI4AI-72). Hibernate appends
     * {@code WHERE version = ?} to every UPDATE and increments it on success;
     * a concurrent commit on a stale state fails with OptimisticLockException
     * (mapped to HTTP 409) instead of silently overwriting the other writer.
     * New entities are persisted with version 0 (Hibernate initialises a null
     * numeric @Version attribute); existing rows were backfilled to 0 by V11.
     */
    @Version
    @Column(name = "version")
    private Long version;

    /**
     * The user who created this document (WIKI4AI-99). NULL for legacy rows —
     * those predate the owner concept and are public by definition. Never set
     * from client input: it is always the authenticated creator, assigned in
     * the service layer, and never changed afterwards.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    private User owner;

    /**
     * The actor (V15 registry) that created this document (WIKI4AI-134). NULL for
     * every document written by a caller that carries no actor — legacy JWT
     * requests, and every document created before this column existed.
     * <p>
     * Plain id, not a JPA association: the audit reference must stay readable
     * after the actor is removed from the registry (the DB column is
     * {@code ON DELETE SET NULL}), and the value is never taken from client
     * input — it is resolved from the authenticated request in the service layer.
     * <p>
     * ADDITIVE: {@code owner_id} (the user, WIKI4AI-99) is unchanged and remains
     * the ownership column. Attribution supplements it, it does not replace it.
     */
    @Column(name = "created_by_actor_id")
    private Long createdByActorId;

    /**
     * The actor (V15 registry) that performed the last update of this document
     * (WIKI4AI-134). Overwritten on every update; NULL when the updater carried
     * no actor. Same rules as {@code createdByActorId}.
     */
    @Column(name = "updated_by_actor_id")
    private Long updatedByActorId;

    /**
     * 'public' or 'private' (DB CHECK constraint, V14). A private document is
     * visible only to its owner and ADMIN users — even when it lives in a
     * public project. Legacy rows carry the column default 'public'.
     */
    @Column(nullable = false, length = 10)
    private String visibility;

    /**
     * Whether this document is pinned to the top of the project's list (WIKI4AI-184).
     * A display concern only — it never affects visibility or permissions.
     * <p>
     * Written by a JPQL UPDATE in {@code DocumentRepository}, never by load-and-save:
     * this entity carries {@code @Version} (WIKI4AI-72), so saving it for a pin would
     * bump the version and make an editor's concurrent save fail with 409 for a change
     * that touched no content. The same reasoning keeps {@code updatedAt} unchanged —
     * pinning is not editing.
     * <p>
     * {@code updatable = false} (WIKI4AI-197): a content save writes the whole entity back,
     * and without this it would also write the {@code pinned} value read at load time,
     * silently reverting a pin made in between. See {@link #sortOrder} for the full story.
     */
    @Column(name = "pinned", nullable = false, updatable = false)
    @Builder.Default
    private boolean pinned = false;

    /**
     * Position inside the manual ordering of its project (WIKI4AI-184), dense 0..n-1
     * and maintained by the reorder endpoint. Scoped to the project: the document list
     * is always nested under one project, and so is the index that backs this column.
     * Only {@code sort=manual} reads it; ties are broken by {@code id} so the order is
     * total and stable across pages. Same {@code @Version} caveat as
     * {@link #pinned}: written by JPQL UPDATE.
     * <p>
     * Both columns are {@code updatable = false} (WIKI4AI-197). A content save loads the
     * whole entity and writes it back; without this, that UPDATE would carry the
     * {@code pinned}/{@code sort_order} values as they were when the row was read, and a
     * pin or a drag made in between would be silently reverted. The version check does not
     * catch it — pinning deliberately does not bump {@code @Version}. Hibernate leaves
     * non-updatable columns out of the UPDATE statement entirely, so the only writers are
     * the JPQL bulk updates in {@code DocumentRepository}.
     */
    @Column(name = "sort_order", nullable = false, updatable = false)
    @Builder.Default
    private int sortOrder = 0;

    /**
     * Generate a URL-friendly slug from the document title.
     * Transliterates diacritics (e.g., "ú" → "u") then strips remaining non-ASCII chars.
     * Examples: "My Document" → "my-document", "Úvod do Wiki4AI" → "uvod-do-wiki4ai"
     */
    public static String generateSlug(String title) {
        if (title == null || title.isBlank()) {
            return "";
        }
        String slug = title.toLowerCase();
        // Transliterate diacritics to ASCII equivalents
        slug = slug.replaceAll("[áäàâ]", "a")
                .replaceAll("č", "c")
                .replaceAll("[ďđ]", "d")
                .replaceAll("[éèêë]", "e")
                .replaceAll("[íìîï]", "i")
                .replaceAll("[ĺľ]", "l")
                .replaceAll("ň", "n")
                .replaceAll("[óòôöõ]", "o")
                .replaceAll("ŕ", "r")
                .replaceAll("š", "s")
                .replaceAll("ť", "t")
                .replaceAll("[úùûü]", "u")
                .replaceAll("[ýỳÿ]", "y")
                .replaceAll("ž", "z");
        // Strip remaining non-ASCII, normalize spaces and dashes
        return slug.replaceAll("[^a-z0-9\\s-]", "")
                .replaceAll("\\s+", "-")
                .replaceAll("-+", "-")
                .trim()
                .replaceAll("^-|-$", "");
    }

    /**
     * Set the document title and auto-generate slug from it.
     */
    public void setTitle(String title) {
        this.title = title;
        this.slug = generateSlug(title);
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

    /**
     * Set the pinned flag (WIKI4AI-184). Used by tests and when building a new row;
     * the production write path is the JPQL UPDATE in {@code DocumentRepository}, which
     * neither bumps {@code @Version} nor moves {@code updatedAt}. On a row Hibernate is
     * already tracking this setter changes the field in memory only — the column is
     * {@code updatable = false}, so it is not written back.
     */
    public void setPinned(boolean pinned) {
        this.pinned = pinned;
    }

    /**
     * Set the manual position (WIKI4AI-184). Same note as {@link #setPinned(boolean)}:
     * the reorder endpoint writes it with a JPQL UPDATE, and on a managed row this setter
     * is in-memory only.
     */
    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    /**
     * Set the creating actor id (WIKI4AI-134). Called only by the service layer,
     * only from the authenticated request context — never from a request body,
     * query parameter or header. Null means "this caller carries no actor".
     */
    public void setCreatedByActorId(Long createdByActorId) {
        this.createdByActorId = createdByActorId;
    }

    /**
     * Set the last-updating actor id (WIKI4AI-134). Same rule as
     * {@link #setCreatedByActorId}: service layer only, authenticated context only.
     */
    public void setUpdatedByActorId(Long updatedByActorId) {
        this.updatedByActorId = updatedByActorId;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        // Auto-generate slug from title if not set
        if (slug == null || slug.isBlank()) {
            this.slug = generateSlug(this.title);
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
