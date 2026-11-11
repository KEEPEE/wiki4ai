-- WIKI4AI-184: pin-to-top + manual order (sort_order) for projects AND documents.
--
-- Two additive columns per table, both NOT NULL with a constant default, so the
-- statement is safe on a populated table and needs no rewrite of the existing rows:
--
--   pinned     BOOLEAN NOT NULL DEFAULT FALSE  -- "keep this item at the top of the list"
--   sort_order INTEGER NOT NULL DEFAULT 0      -- position inside the manual ordering
--
-- The defaults are what makes the endpoint contract backward compatible: a row that
-- nobody has pinned or reordered carries (FALSE, 0), and the list endpoints order by
-- "pinned DESC, sort_order ASC, id ASC" only when the caller explicitly asks for
-- sort=manual. Every other sort keeps the pre-WIKI4AI-184 ordering, so an existing
-- client that sends no parameters sees exactly what it saw before.
--
-- Time columns are untouched here (SPEC rule 11b): the postgres profile on dev runs
-- with ddl-auto: validate and the entity mapping must keep matching the schema.
-- pinned/sort_order map to primitive boolean/int on the entities, which is exactly
-- BOOLEAN/INTEGER — no type can drift.
--
-- The backfill gives every existing row a dense 0..n-1 position that reproduces the
-- ordering the UI showed before this story: projects by created_at DESC, documents by
-- updated_at DESC within their project. Without it every row would carry 0 and the
-- first user to switch to sort=manual would see an arbitrary (id-based) order instead
-- of the one they were already looking at. `id` is the tie-break in both windows, so
-- the backfill is deterministic — the same input always produces the same order.

ALTER TABLE projects ADD COLUMN pinned BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE projects ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0;

ALTER TABLE documents ADD COLUMN pinned BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE documents ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0;

WITH ordered AS (
    SELECT id, ROW_NUMBER() OVER (ORDER BY created_at DESC, id DESC) - 1 AS position
    FROM projects
)
UPDATE projects p
   SET sort_order = ordered.position
  FROM ordered
 WHERE p.id = ordered.id;

-- Documents are ordered per project: sort_order is only ever compared inside one
-- project (the document list endpoint is always scoped to a project), so the window
-- is partitioned by project_id.
WITH ordered AS (
    SELECT id, ROW_NUMBER() OVER (PARTITION BY project_id ORDER BY updated_at DESC, id DESC) - 1 AS position
    FROM documents
)
UPDATE documents d
   SET sort_order = ordered.position
  FROM ordered
 WHERE d.id = ordered.id;

-- The two access patterns these columns serve:
--   projects:  "all projects, pinned first, then the manual order"
--   documents: "documents of ONE project, pinned first, then the manual order"
-- Both are plain b-tree indexes on the ordering key with `id` as the tie-break, so
-- the ORDER BY of the list query is index-backed and stable across pages.
CREATE INDEX idx_projects_pinned_sort ON projects (pinned DESC, sort_order, id);
CREATE INDEX idx_documents_project_pinned_sort ON documents (project_id, pinned DESC, sort_order, id);
