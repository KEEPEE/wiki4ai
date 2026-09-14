-- WIKI4AI-29: Subprojects — parent pointer on projects (self-reference).
-- A project may have at most one parent; hierarchy depth is limited to 5 levels
-- (root = level 1) and validated in the service layer (cycle prevention included).
-- Existing projects keep parent_id NULL and remain roots.

ALTER TABLE projects ADD COLUMN parent_id BIGINT REFERENCES projects(id) ON DELETE CASCADE;
CREATE INDEX idx_projects_parent ON projects(parent_id);
