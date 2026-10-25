package com.wiki4ai.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Objects;

/**
 * JPA Entity representing an actor in the coordination registry (WIKI4AI-131).
 * <p>
 * An actor is anything that can take part in the coordination layer: an AI
 * agent, a human, or an automated service. {@code handle} is the globally
 * unique, stable identifier that later stories (project_actors, work items,
 * events, comments) use to refer to an actor.
 * <p>
 * Ownership: {@code ownerUserId} is the user who registered the actor; it is
 * {@code null} for system/service actors (the DB column is
 * {@code ON DELETE SET NULL}, so removing an account never removes the actor).
 * Access rules are enforced in {@code ActorService}, not in SecurityConfig:
 * only the owner or an ADMIN may modify an actor.
 * <p>
 * Timestamps use {@link LocalDateTime}, matching every other entity in this
 * project and the {@code TIMESTAMP} columns of the V15 migration — with
 * {@code spring.jpa.hibernate.ddl-auto=validate} the entity and the migration
 * must agree on the JDBC type or the backend fails at startup.
 */
@Entity
@Table(name = "actors")
@Getter
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Actor {

    /** Actor kind: an AI agent. */
    public static final String KIND_AGENT = "agent";

    /** Actor kind: a human being. */
    public static final String KIND_HUMAN = "human";

    /** Actor kind: an automated service (needs no human owner). */
    public static final String KIND_SERVICE = "service";

    /** Actor status: available for work. */
    public static final String STATUS_ACTIVE = "active";

    /** Actor status: temporarily unavailable. */
    public static final String STATUS_PAUSED = "paused";

    /** Actor status: kept for history, not usable for new work. */
    public static final String STATUS_ARCHIVED = "archived";

    /** Presence status: available and not doing anything. */
    public static final String PRESENCE_IDLE = "idle";

    /** Presence status: currently doing work. */
    public static final String PRESENCE_WORKING = "working";

    /** Presence status: present but occupied, do not hand new work over. */
    public static final String PRESENCE_BUSY = "busy";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Globally unique handle (DB UNIQUE constraint, V15). Stored normalized to
     * lowercase so the DB constraint and the service-level duplicate check
     * (409) agree on what counts as a duplicate.
     */
    @Column(name = "handle", nullable = false, unique = true, length = 64)
    private String handle;

    /** Optional human-friendly name shown in the UI. */
    @Column(name = "display_name", length = 128)
    private String displayName;

    /** 'agent' | 'human' | 'service'. */
    @Column(name = "kind", nullable = false, length = 16)
    private String kind;

    /**
     * Id of the user who registered this actor (users table), or null for
     * system/service actors. Kept as a plain id rather than a JPA association
     * so the registry stays readable after the owner account is deleted
     * (ON DELETE SET NULL).
     */
    @Column(name = "owner_user_id")
    private Long ownerUserId;

    /** Optional label for built-in/system actors (e.g. "wiki4ai-mcp"). */
    @Column(name = "system_label", length = 128)
    private String systemLabel;

    /**
     * Free-form capability description (comma-separated tags or JSON). Stored
     * as TEXT; the server does not interpret it.
     */
    @Column(name = "capabilities", columnDefinition = "TEXT")
    private String capabilities;

    /** Optional callback URL the coordination layer can notify. */
    @Column(name = "webhook_url", length = 512)
    private String webhookUrl;

    /** Last time this actor was seen (heartbeat / last API call). */
    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt;

    /** 'active' | 'paused' | 'archived'. */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /**
     * Presence reported by the last heartbeat: 'idle' | 'working' | 'busy'
     * (WIKI4AI-133, V17). Deliberately separate from {@code status}: 'status' is
     * the lifecycle state of the registry entry, 'presence_status' is what the
     * actor said about its current load. An archived actor is not usable for new
     * work; a 'busy' actor merely is currently occupied.
     */
    @Column(name = "presence_status", nullable = false, length = 16)
    private String presenceStatus;

    /** Optional free-text note sent with the heartbeat (max 256 characters). */
    @Column(name = "presence_note", length = 256)
    private String presenceNote;

    /**
     * Inbox marker of WIKI4AI-153 (migration V23): the highest {@code work_item_events.id}
     * this actor has been shown to have acknowledged.
     * <p>
     * <b>Nullable on purpose, and NULL is not 0.</b> {@code NULL} means "this actor has
     * never read its inbox". 0 would be a position in a sequence whose ids start at 1 —
     * a value somebody had to invent. The difference is visible in the API:
     * {@code UnseenEventsDTO.lastSeenEventId} is null for an actor that has never marked
     * anything, and its unseen count is then its whole event stream.
     * <p>
     * <b>On actors, not on users.</b> The AC of WIKI4AI-153 says "per user"; SPEC records
     * the deviation as a decision: the inbox is {@code GET /api/v1/actors/{id}/inbox}, it is
     * read by an actor, the cursor it returns belongs to the same actor, and a user who
     * registered several agents gets a marker per agent because each agent has its own work
     * stream and its own polling rhythm. There is also no user-preferences mechanism
     * anywhere in this repository to put it on.
     * <p>
     * Not a timestamp: a plain {@link Long} against the {@code BIGINT} column of V23, which
     * has no default. SPEC rule 11b is untouched by this field.
     */
    @Column(name = "last_seen_event_id")
    private Long lastSeenEventId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * Set the handle. Normalized to lowercase here as well as in the service,
     * so an entity built directly (tests, seeders) cannot bypass the
     * normalization the UNIQUE constraint relies on.
     */
    public void setHandle(String handle) {
        this.handle = handle == null ? null : handle.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Set the display name.
     */
    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    /**
     * Set the kind ('agent' | 'human' | 'service').
     */
    public void setKind(String kind) {
        this.kind = kind;
    }

    /**
     * Set the owning user id (null = system/service actor without an owner).
     */
    public void setOwnerUserId(Long ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    /**
     * Set the system label.
     */
    public void setSystemLabel(String systemLabel) {
        this.systemLabel = systemLabel;
    }

    /**
     * Set the capabilities text.
     */
    public void setCapabilities(String capabilities) {
        this.capabilities = capabilities;
    }

    /**
     * Set the webhook URL.
     */
    public void setWebhookUrl(String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }

    /**
     * Set the last-seen timestamp.
     */
    public void setLastSeenAt(LocalDateTime lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }

    /**
     * Set the status ('active' | 'paused' | 'archived').
     */
    public void setStatus(String status) {
        this.status = status;
    }

    /**
     * Set the presence status ('idle' | 'working' | 'busy'). The value is
     * validated by the service layer before it reaches here.
     */
    public void setPresenceStatus(String presenceStatus) {
        this.presenceStatus = presenceStatus;
    }

    /**
     * Set the presence note (free text, max 256 characters).
     */
    public void setPresenceNote(String presenceNote) {
        this.presenceNote = presenceNote;
    }

    /**
     * Set the inbox marker of WIKI4AI-153 (column {@code last_seen_event_id}, migration V23).
     * <p>
     * Null is a meaningful value here and is passed through as null: it means "this actor has
     * never read its inbox", which is not the same statement as 0. Advancing it is the job of
     * {@code ActorInboxService.markSeen}, which is the only place that decides the value.
     */
    public void setLastSeenEventId(Long lastSeenEventId) {
        this.lastSeenEventId = lastSeenEventId;
    }

    /**
     * Set the id. Used mainly for testing and entity comparison.
     */
    public void setId(Long id) {
        this.id = id;
    }

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        // Defensive defaults: the DB columns carry DEFAULT 'agent' / DEFAULT 'active'.
        // Keep the Java side consistent so no unexpected value reaches the CHECK
        // semantics of the API.
        if (kind == null || kind.isBlank()) {
            kind = KIND_AGENT;
        }
        if (status == null || status.isBlank()) {
            status = STATUS_ACTIVE;
        }
        if (presenceStatus == null || presenceStatus.isBlank()) {
            presenceStatus = PRESENCE_IDLE;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * Whether this actor has no owning user (system/service actor). Such actors
     * may only be modified by an ADMIN.
     */
    public boolean isSystemActor() {
        return ownerUserId == null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Actor that = (Actor) o;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return "Actor{" +
                "id=" + id +
                ", handle='" + handle + '\'' +
                ", displayName='" + displayName + '\'' +
                ", kind='" + kind + '\'' +
                ", status='" + status + '\'' +
                ", ownerUserId=" + ownerUserId +
                '}';
    }
}
