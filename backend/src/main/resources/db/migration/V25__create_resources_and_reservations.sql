-- WIKI4AI-155: resources + reservations (capacity ledger).
--
-- Time columns are TIMESTAMP without a time zone and map to java.time.LocalDateTime
-- in the entities (SPEC rule 11b). No TIMESTAMPTZ, no Instant: the postgres profile on
-- dev runs with ddl-auto: validate and a type mismatch stops the backend at startup.
--
-- The CHECK constraints below are the last line of defence for the capacity invariant
-- "0 <= active_slots <= capacity" and "slots >= 1". They are declared here AND mirrored
-- as @Check on the entities, because the test profile builds the schema from the entities
-- (Flyway is off on H2) and an invariant that only exists in PostgreSQL cannot be tested.
--
-- `key` is a keyword in H2 2.x (verified against h2-2.2.224: an unquoted `key` column is a
-- syntax error there) and a non-reserved keyword in PostgreSQL. The migration keeps the
-- unquoted spelling, which is legal for PostgreSQL; the entity maps the column as a quoted
-- identifier so the Hibernate-generated H2 test schema is valid too.

CREATE TABLE resources (
    id BIGSERIAL PRIMARY KEY,
    key VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(128) NOT NULL,
    kind VARCHAR(32) NOT NULL DEFAULT 'generic',
    capacity INTEGER NOT NULL DEFAULT 1,
    active_slots INTEGER NOT NULL DEFAULT 0,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    metadata TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT chk_resources_capacity CHECK (capacity >= 1),
    CONSTRAINT chk_resources_active_nonneg CHECK (active_slots >= 0),
    CONSTRAINT chk_resources_active_le_cap CHECK (active_slots <= capacity)
);

CREATE TABLE reservations (
    id BIGSERIAL PRIMARY KEY,
    resource_id BIGINT NOT NULL REFERENCES resources(id) ON DELETE CASCADE,
    actor_id BIGINT REFERENCES actors(id) ON DELETE SET NULL,
    work_item_id BIGINT REFERENCES work_items(id) ON DELETE SET NULL,
    slots INTEGER NOT NULL DEFAULT 1,
    reason VARCHAR(255),
    expires_at TIMESTAMP NOT NULL,
    released_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT chk_reservations_slots CHECK (slots >= 1)
);

-- The reaper reads "unreleased reservations whose expiry is in the past" (idx_reservations_expiry)
-- and the per-resource listing reads "unreleased reservations of one resource"
-- (idx_reservations_resource_active). Both are the only two access patterns of this table.
CREATE INDEX idx_reservations_resource_active ON reservations (resource_id, released_at);
CREATE INDEX idx_reservations_expiry ON reservations (expires_at);
