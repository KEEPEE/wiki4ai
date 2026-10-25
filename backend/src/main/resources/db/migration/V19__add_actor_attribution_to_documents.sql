-- WIKI4AI-134: agent attribution on documents.
--
-- Two nullable columns that record WHICH ACTOR (agent / human / service from the
-- V15 registry) performed the write. They are an ADDITION, not a replacement:
-- documents.owner_id (the user, WIKI4AI-99) is untouched by this migration.
--
-- Both columns are nullable on purpose:
--   * a legacy JWT request carries no actor -> the columns stay NULL,
--   * ON DELETE SET NULL keeps the document alive when an actor is removed from
--     the registry (the audit reference disappears, the content does not).
--
-- Timestamps: this migration adds none. Every time column in this project is
-- TIMESTAMP (no time zone) mapped to java.time.LocalDateTime — see SPEC rule 11b.
-- A time-zone-aware column type is used nowhere in V1..V19 and would break the
-- postgres profile, which runs with spring.jpa.hibernate.ddl-auto=validate.

ALTER TABLE documents ADD COLUMN created_by_actor_id BIGINT REFERENCES actors(id) ON DELETE SET NULL;

ALTER TABLE documents ADD COLUMN updated_by_actor_id BIGINT REFERENCES actors(id) ON DELETE SET NULL;

CREATE INDEX idx_documents_created_by_actor ON documents (created_by_actor_id);
