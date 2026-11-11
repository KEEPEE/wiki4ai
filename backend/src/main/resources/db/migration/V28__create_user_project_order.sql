-- WIKI4AI-200 (PER-1): per-user project ordering and pinning.
--
-- What the bug was (Opus review, P1): projects.pinned and projects.sort_order are ONE
-- GLOBAL STATE. Every user who created a project holds MANAGE on it, so `POST
-- /projects/{slug}/pin` pinned that project to the top of EVERYBODY's dashboard, and a
-- reorder renumbered other people's projects too. One shared list, many writers, no
-- per-viewer state.
--
-- This table is that per-viewer state. One row means "this viewer has customised this
-- one project": their own pinned flag and their own position. A viewer with no row for a
-- project sees the global value from V26, which is why nothing changes for anybody on the
-- day this ships — the table starts empty and every list still renders from the global
-- columns until someone pins or drags for the first time.
--
-- ## Why the global columns STAY
--
-- They are the curated default order: what an administrator arranges once (see
-- GET/PUT /api/v1/admin/projects-order) and what every user who has customised nothing
-- inherits. Dropping them would mean inventing a row per (user, project) pair for every
-- user on every install just to keep the existing order, and would leave the administrator
-- with nothing to curate. So this migration is purely additive: no DROP, no ALTER of an
-- existing column, no UPDATE of an existing row. Rollback is `DROP TABLE
-- user_project_order` and the database is exactly as it was.
--
-- ## No backfill
--
-- Deliberate. A backfill would copy the global order into a row for every existing user
-- and every existing project, which (a) writes users*projects rows for a state nobody
-- asked for and (b) freezes that order for those users, so a later change of the global
-- default would stop reaching them. Reading "no row" as "use the global value" costs one
-- LEFT JOIN and keeps the default live.
--
-- ## Time columns
--
-- TIMESTAMP without time zone, mapping to java.time.LocalDateTime in the entity (SPEC
-- rule 11b): the postgres profile on dev runs with ddl-auto: validate and a type mismatch
-- stops the backend at startup. Same spelling as V25 and V22.
--
-- ## The two constraints
--
-- UNIQUE (user_id, project_id) is the whole point: a viewer has AT MOST ONE ordering row
-- per project. Every write is therefore an upsert of that one row, and a duplicate is a
-- database error rather than a list that shows the same project twice at two positions.
--
-- ON DELETE CASCADE on both sides is what keeps the table from accumulating rows for a
-- project that no longer exists, or for a deleted account. Deleting a project removes
-- every viewer's row for it; deleting a user removes that user's whole customisation.
-- Neither is reachable from the entity mapping, which carries plain ids (the convention
-- of WorkItem and Reservation), so the database is the only enforcer here.
--
-- ## The index
--
-- (user_id, pinned DESC, sort_order) is the ordering the project list is served with:
-- "this viewer's rows, pinned first, then their manual order". It is the same shape as
-- idx_projects_pinned_sort from V26, prefixed by user_id because the read is always
-- scoped to one viewer.

CREATE TABLE user_project_order (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    project_id BIGINT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    pinned BOOLEAN NOT NULL DEFAULT FALSE,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT uk_user_project_order UNIQUE (user_id, project_id)
);

CREATE INDEX idx_user_project_order_viewer
    ON user_project_order (user_id, pinned DESC, sort_order);
