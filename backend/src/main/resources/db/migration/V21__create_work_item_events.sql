-- WIKI4AI-145 + WIKI4AI-146 + WIKI4AI-147: work_item_events (Flyway V21) - the
-- append-only audit trail of the coordination layer.
--
-- NOTE ON THE NUMBER: the Jira summary of WIKI4AI-145 says "Migracia V18". That
-- is stale. V18 is api_tokens (WIKI4AI-135..139). The authoritative assignment in
-- SPEC ("V15 actors . V16 project_actors . V17 actor_presence . V18 api_tokens .
-- V19 documents attribution . V20 work_items . V21 work_item_events . V22
-- work_item_comments . V23 resources") puts work_item_events at V21. Reusing an
-- existing version would break Flyway validation and the dev backend at startup.
-- V22 and V23 stay unused: they belong to the comment and resource stories.
--
-- Semantics: one row per thing that happened to a work item. The table is
-- APPEND-ONLY: a row is inserted and never modified. Nothing here updates or
-- deletes; the application layer enforces it (WorkItemEventRepository exposes no
-- mutating method and WorkItemEvent's @PreUpdate/@PreRemove refuse the write),
-- and the trigger at the bottom of this file makes the database refuse an UPDATE
-- too.
--
--   work_item_id - the item the event belongs to. ON DELETE CASCADE, exactly as
--                  SPEC's DDL: the timeline of an item is removed with the item.
--                  Consequence worth knowing: a 'deleted' event written in the
--                  same transaction as the DELETE is itself cascaded away on
--                  PostgreSQL, so the audit of a deleted item does not outlive it.
--                  Changing that is a schema decision outside this story.
--   actor_id     - who the event is about / who caused it, from the V15 registry.
--                  Nullable on purpose: the lease reaper acts without a request,
--                  so there is no credential to attribute it to. For the automatic
--                  events (lease_expired, stale_claim_cleared) it names the actor
--                  whose lease or claim was lost, which is what WIKI4AI-147 counts.
--                  ON DELETE SET NULL keeps the audit when an actor leaves the
--                  registry.
--   event        - 'created' | 'status_changed' | 'claimed' | 'claim_released'
--                  | 'lease_extended' | 'lease_expired' | 'stale_claim_cleared'
--                  | 'deleted'. The vocabulary is defined and documented in
--                  WorkItemEventService. No CHECK constraint is added here: every
--                  other business rule of this project is enforced in the service
--                  layer (SPEC rule 7), and a CHECK here would be a second copy
--                  of the same list free to drift.
--   from_status / to_status - the state before and after. NULL where the event has
--                  no other side: 'created' has no predecessor, 'deleted' has no
--                  successor.
--   payload      - optional JSON, TEXT like work_items.body. Never carries a
--                  claim token: the token is a secret that authorises work.
--   created_at   - when it happened. TIMESTAMP without a time zone, mapped to
--                  java.time.LocalDateTime - SPEC rule 11b. V1..V20 contain no
--                  TIMESTAMPTZ anywhere and the postgres profile runs with
--                  spring.jpa.hibernate.ddl-auto=validate: a time-zone-aware
--                  column would fail validation ("found [timestamp with time
--                  zone], but expecting [timestamp(6)]") and the backend on dev
--                  .219 would not start. This has already happened twice.
--
-- Tests run on H2 with Flyway disabled (the schema is created by Hibernate from
-- the entities), so this file is exercised by the pipeline on dev, not locally.

CREATE TABLE work_item_events (
    id BIGSERIAL PRIMARY KEY,
    work_item_id BIGINT NOT NULL REFERENCES work_items(id) ON DELETE CASCADE,
    actor_id BIGINT REFERENCES actors(id) ON DELETE SET NULL,
    event VARCHAR(32) NOT NULL,
    from_status VARCHAR(16),
    to_status VARCHAR(16),
    payload TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_wie_item_time ON work_item_events (work_item_id, created_at);
CREATE INDEX idx_wie_actor_time ON work_item_events (actor_id, created_at);

-- WIKI4AI-145: append-only, enforced by the database and not only by the code.
-- UPDATE is refused outright. DELETE is NOT refused here, because the
-- ON DELETE CASCADE above is part of SPEC's DDL and a BEFORE DELETE trigger would
-- break it: deleting a work item must still be able to take its timeline with it.
-- Removal through JPA is refused instead, by WorkItemEvent's @PreRemove.
CREATE OR REPLACE FUNCTION wiki4ai_work_item_events_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $trigger$
BEGIN
    RAISE EXCEPTION 'work_item_events is append-only: % is not allowed', TG_OP;
END;
$trigger$;

CREATE TRIGGER trg_work_item_events_append_only
    BEFORE UPDATE ON work_item_events
    FOR EACH ROW
    EXECUTE FUNCTION wiki4ai_work_item_events_append_only();

-- WIKI4AI-147: the metrics endpoint is derived from work_item_events alone, and
-- its acceptance criterion says it must return non-zero numbers for the data that
-- already exists on dev .219. Work items created before this table existed have no
-- events at all, so without this backfill every metric would be zero forever for
-- them. Two honest rows per existing item, both marked in the payload:
--   'created'      at work_items.created_at, attributed to created_by_actor_id,
--                  entering 'backlog' - the column default of V20 and the state a
--                  create produces when the caller asks for none.
--   'status_changed' at work_items.updated_at, attributed to updated_by_actor_id,
--                  entering the state the item is in now. The transition really
--                  happened; only its exact time is unknown, and updated_at is the
--                  newest moment it can have happened at.
-- No history is invented: no claim, no lease and no expiry is backfilled, because
-- nothing is known about them.
INSERT INTO work_item_events (work_item_id, actor_id, event, from_status, to_status, payload, created_at)
SELECT w.id,
       w.created_by_actor_id,
       'created',
       NULL,
       'backlog',
       '{"backfilled":true}',
       w.created_at
FROM work_items w;

INSERT INTO work_item_events (work_item_id, actor_id, event, from_status, to_status, payload, created_at)
SELECT w.id,
       w.updated_by_actor_id,
       'status_changed',
       'backlog',
       w.status,
       '{"backfilled":true}',
       w.updated_at
FROM work_items w
WHERE w.status <> 'backlog';
