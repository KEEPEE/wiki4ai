-- WIKI4AI-132: project_actors (Flyway V16) - role assignments linking the actor
-- registry (V15) to projects. One row per (project, actor) pair.
--
-- Semantics:
--   project_id - the project the actor is assigned to. ON DELETE CASCADE: removing
--                a project removes its assignments.
--   actor_id   - the assigned actor. ON DELETE CASCADE: removing an actor removes
--                its assignments (the registry owns the actor's lifecycle).
--   role       - viewer | worker | planner | admin. 'admin' is what grants the
--                right to manage the project's actors; it is enforced in
--                ProjectActorService, not in SecurityConfig.
--   uq_project_actor - a second assignment of the same actor to the same project
--                is a 409 Conflict (the service pre-checks it, the constraint
--                covers concurrent writes).
--
-- Every timestamp column is TIMESTAMP (without a time zone), matching V1..V15 and
-- the java.time.LocalDateTime field of the ProjectActor entity: the postgres profile
-- runs with spring.jpa.hibernate.ddl-auto=validate, so the migration and the entity
-- must agree on the JDBC type or the backend fails at startup.
--
-- Tests run on H2 with Flyway disabled (schema created by Hibernate from the
-- entities), so this file is exercised by the pipeline on dev, not locally.

CREATE TABLE project_actors (
    id BIGSERIAL PRIMARY KEY,
    project_id BIGINT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    actor_id BIGINT NOT NULL REFERENCES actors(id) ON DELETE CASCADE,
    role VARCHAR(16) NOT NULL DEFAULT 'viewer',    -- viewer | worker | planner | admin
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT uq_project_actor UNIQUE (project_id, actor_id),
    CONSTRAINT ck_project_actors_role CHECK (role IN ('viewer', 'worker', 'planner', 'admin'))
);

CREATE INDEX idx_project_actors_actor ON project_actors (actor_id);
CREATE INDEX idx_project_actors_project ON project_actors (project_id);
