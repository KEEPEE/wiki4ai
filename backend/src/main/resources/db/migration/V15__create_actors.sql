-- WIKI4AI-131: Actors registry (Flyway V15) - first migration of the agent
-- coordination layer. Creates ONLY the `actors` table; `project_actors` is
-- created by V16 (WIKI4AI-132).
--
-- Semantics:
--   handle        - globally unique, stable, machine-friendly identifier
--                   ("agent-coder"). The service normalizes it to lowercase so
--                   the DB UNIQUE constraint and the service-level duplicate
--                   check (409) agree on what counts as a duplicate.
--   kind          - agent | human | service.
--   status        - active | paused | archived.
--   owner_user_id - user who registered the actor; NULL for system/service
--                   actors. ON DELETE SET NULL keeps the registry alive when the
--                   owner account is removed.
--
-- Every timestamp column is TIMESTAMP (without a time zone), matching V1..V14 and
-- the java.time.LocalDateTime fields of the Actor entity: the postgres profile runs
-- with spring.jpa.hibernate.ddl-auto=validate, so the migration and the entity must
-- agree on the JDBC type or the backend fails at startup. A time-zone column type
-- would not validate against LocalDateTime and would crash the dev instance.
--
-- Tests run on H2 with Flyway disabled (schema created by Hibernate from the
-- entities), so this file is exercised by the pipeline on dev, not locally.

CREATE TABLE actors (
    id BIGSERIAL PRIMARY KEY,
    handle VARCHAR(64) NOT NULL UNIQUE,
    display_name VARCHAR(128),
    kind VARCHAR(16) NOT NULL DEFAULT 'agent',      -- agent | human | service
    owner_user_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    system_label VARCHAR(128),
    capabilities TEXT,
    webhook_url VARCHAR(512),
    last_seen_at TIMESTAMP,
    status VARCHAR(16) NOT NULL DEFAULT 'active',   -- active | paused | archived
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT ck_actors_kind CHECK (kind IN ('agent', 'human', 'service')),
    CONSTRAINT ck_actors_status CHECK (status IN ('active', 'paused', 'archived'))
);

CREATE INDEX idx_actors_owner ON actors (owner_user_id);
CREATE INDEX idx_actors_status ON actors (status);
