-- WIKI4AI-140 + WIKI4AI-141: work_items (Flyway V20) - the unit of work of the
-- agent coordination layer.
--
-- NOTE ON THE NUMBER: the Jira summary of WIKI4AI-140 says "Migracia V17". That
-- is stale. V17 is actor_presence (WIKI4AI-133) and V15..V19 are all taken; the
-- authoritative assignment in SPEC ("V15 actors . V16 project_actors . V17
-- actor_presence . V18 api_tokens . V19 documents attribution . V20 work_items")
-- puts work_items at V20. Reusing an existing version would break Flyway
-- validation and the dev backend at startup.
--
-- Semantics:
--   status          - backlog | ready | claimed | running | review | done
--                     | blocked | cancelled. 'claimed' is reachable ONLY through
--                     the atomic claim endpoint (WorkItemRepository.claim), never
--                     through a plain update. No CHECK constraint is added here:
--                     the state machine is enforced in WorkItemService, which is
--                     where every other ownership/business rule of this project
--                     lives (SPEC rule 7).
--   priority        - free-form ordering value, default 'medium'.
--   labels          - plain TEXT; the service owns the encoding/decoding, the
--                     entity stores a String (SPEC: labels are TEXT in the DB).
--   parent_id       - self reference, ON DELETE SET NULL: removing a parent never
--                     removes its children.
--   external_ref    - globally unique id of an item in an external tracker.
--   assignee_actor_id / claim_token / lease_expires_at - the claim triple written
--                     by the atomic claim: who holds the item, with which token
--                     and until when.
--   created_by_actor_id / updated_by_actor_id - attribution of the write to an
--                     actor of the V15 registry (same pattern as V19 on
--                     documents). Nullable: a caller that carries no actor (a
--                     legacy JWT) leaves them NULL. ON DELETE SET NULL keeps the
--                     work item alive when an actor leaves the registry.
--   version         - optimistic locking (WorkItem.version is @Version). New rows
--                     start at 0.
--
-- Every time column is TIMESTAMP (without a time zone), mapped to
-- java.time.LocalDateTime in the entity - SPEC rule 11b. V1..V19 contain no
-- TIMESTAMPTZ anywhere, and the postgres profile runs with
-- spring.jpa.hibernate.ddl-auto=validate: a time-zone-aware column type would
-- fail validation ("found [timestamp with time zone], but expecting
-- [timestamp(6)]") and the backend on dev .219 would not start. This has already
-- happened twice.
--
-- Tests run on H2 with Flyway disabled (the schema is created by Hibernate from
-- the entities), so this file is exercised by the pipeline on dev, not locally.

CREATE TABLE work_items (
    id BIGSERIAL PRIMARY KEY,
    project_id BIGINT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    title VARCHAR(255) NOT NULL,
    body TEXT,
    status VARCHAR(16) NOT NULL DEFAULT 'backlog',
    priority VARCHAR(16) NOT NULL DEFAULT 'medium',
    labels TEXT,
    parent_id BIGINT REFERENCES work_items(id) ON DELETE SET NULL,
    external_ref VARCHAR(128) UNIQUE,
    assignee_actor_id BIGINT REFERENCES actors(id) ON DELETE SET NULL,
    claim_token VARCHAR(64),
    lease_expires_at TIMESTAMP,
    created_by_user_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    created_by_actor_id BIGINT REFERENCES actors(id) ON DELETE SET NULL,
    updated_by_actor_id BIGINT REFERENCES actors(id) ON DELETE SET NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    version INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX idx_work_items_project_status ON work_items (project_id, status);
CREATE INDEX idx_work_items_assignee ON work_items (assignee_actor_id, status);
