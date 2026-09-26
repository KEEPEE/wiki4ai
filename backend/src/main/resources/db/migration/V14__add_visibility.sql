-- WIKI4AI-99: Visibility (public/private) + owner for projects and documents (Flyway V14).
-- Semantics: 'public' records are visible to every user exactly as before;
-- 'private' records are visible only to their owner and ADMIN users.
-- Legacy rows: visibility = 'public' (column default), owner_id NULL -> automatically public.

ALTER TABLE projects ADD COLUMN owner_id BIGINT REFERENCES users(id);
ALTER TABLE projects ADD COLUMN visibility VARCHAR(10) NOT NULL DEFAULT 'public' CHECK (visibility IN ('public', 'private'));

ALTER TABLE documents ADD COLUMN owner_id BIGINT REFERENCES users(id);
ALTER TABLE documents ADD COLUMN visibility VARCHAR(10) NOT NULL DEFAULT 'public' CHECK (visibility IN ('public', 'private'));

CREATE INDEX idx_projects_visibility ON projects(visibility);
CREATE INDEX idx_documents_visibility ON documents(visibility);

-- Legacy rows already carry visibility='public' and owner_id=NULL via the column
-- defaults above; no data backfill is required. The explicit UPDATE below is a
-- defensive no-op kept for clarity (it can never match a row).
UPDATE projects SET visibility = 'public' WHERE visibility NOT IN ('public', 'private');
UPDATE documents SET visibility = 'public' WHERE visibility NOT IN ('public', 'private');
