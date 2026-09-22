-- WIKI4AI-72: optimistic locking for documents (conflict detection on concurrent updates)
-- Adds a version column used as the JPA @Version attribute on Document. Hibernate
-- appends "WHERE version = ?" to every UPDATE and increments it on success; a
-- concurrent commit on a stale state fails with OptimisticLockException -> 409
-- instead of silently overwriting the other writer (last-write-wins).
-- Existing rows are backfilled with 0 (their first update through the app bumps to 1).
ALTER TABLE documents ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
