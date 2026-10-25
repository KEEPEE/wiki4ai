-- WIKI4AI-148 + WIKI4AI-149: work_item_comments (Flyway V22) - the structured,
-- immutable commentary of a work item.
--
-- NOTE ON THE NUMBER: the Jira summary of WIKI4AI-148 says "Migracia V19". That is
-- stale. V19 is the documents attribution (WIKI4AI-134) and V20/V21 are work_items
-- and work_item_events. The authoritative assignment in SPEC ("V15 actors . V16
-- project_actors . V17 actor_presence . V18 api_tokens . V19 documents attribution .
-- V20 work_items . V21 work_item_events . V22 work_item_comments . V23 resources")
-- puts work_item_comments at V22. Reusing an occupied version would break Flyway
-- validation and the dev backend at startup (the postgres profile runs with
-- spring.jpa.hibernate.ddl-auto=validate). V23 stays unused: it belongs to the
-- resources and reservations stories.
--
-- Semantics: one row per comment an actor leaves on an item. Like work_item_events,
-- the table is APPEND-ONLY - a comment is written once and never edited or removed.
-- The application layer refuses the write (WorkItemCommentRepository exposes no
-- working mutating method, WorkItemComment's @PreUpdate/@PreRemove throw) and the
-- trigger at the bottom of this file makes the database refuse an UPDATE too.
--
--   work_item_id    - the item the comment belongs to. ON DELETE CASCADE, exactly as
--                     SPEC's DDL: an item's discussion is removed with the item.
--   author_actor_id - WHO wrote it (V15 registry). Nullable in the schema for the
--                     same reason work_item_events.actor_id is: a row must survive
--                     the actor leaving the registry (ON DELETE SET NULL). It is NOT
--                     nullable through the API: WorkItemCommentService refuses a POST
--                     whose credential names no actor, because a comment nobody can
--                     be held to is not a comment. The value comes exclusively from
--                     RequestActorResolver - never from the body, a query parameter
--                     or a header.
--   kind            - 'status' | 'blocker' | 'result' | 'handoff' | 'note' |
--                     'decision'. The vocabulary and its per-kind payload contracts
--                     live in WorkItemCommentContracts.
--                     NO CHECK CONSTRAINT HERE, deliberately and for the same reason
--                     V21 has none for work_item_events.event: SPEC rule 7 puts
--                     business rules in the service layer, and a CHECK here would be
--                     a second copy of the same six values, free to drift away from
--                     the copy the API actually validates. An unknown kind is a 400
--                     from the service, and the 422 contract errors are produced by
--                     the same single place that knows the kinds.
--   body            - the comment text. NOT NULL: a comment with no text is not a
--                     comment. A missing or blank body is a 400 (a missing required
--                     field), which is a different failure from breaking a payload
--                     contract (422) - see WorkItemCommentService.
--   payload         - the kind's structured detail, as JSON TEXT - the same shape
--                     work_items.labels uses for its list (TEXT in the database,
--                     List<String> in the DTO). Here: TEXT in the database,
--                     Map<String,Object> in the DTO. The contract of each kind is
--                     enforced by the server in code, never by a prompt to the agent
--                     that writes the comment, and never by the database.
--   created_at      - when it was written. There is NO updated_at column: a comment
--                     is immutable, so there is no later moment to record. TIMESTAMP
--                     without a time zone, mapped to java.time.LocalDateTime - SPEC
--                     rule 11b. V1..V21 contain no TIMESTAMPTZ anywhere and the
--                     postgres profile runs with ddl-auto=validate: a time-zone-aware
--                     column would fail validation ("found [timestamp with time
--                     zone], but expecting [timestamp(6)]") and the backend on dev
--                     .219 would not start. This has already happened twice.
--
-- Tests run on H2 with Flyway disabled (the schema is created by Hibernate from the
-- entities), so this file is exercised by the pipeline on dev, not locally.

CREATE TABLE work_item_comments (
    id BIGSERIAL PRIMARY KEY,
    work_item_id BIGINT NOT NULL REFERENCES work_items(id) ON DELETE CASCADE,
    author_actor_id BIGINT REFERENCES actors(id) ON DELETE SET NULL,
    kind VARCHAR(16) NOT NULL,
    body TEXT NOT NULL,
    payload TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_wic_item_kind ON work_item_comments (work_item_id, kind);

-- The read the comments endpoint performs is "one item, oldest first", so the
-- timeline index is the one the query needs; idx_wic_item_kind serves the per-kind
-- reads (the 'done' invariant of WIKI4AI-150 asks "does this item have a result
-- comment"). Same pair of indexes V21 installs over work_item_events.
CREATE INDEX idx_wic_item_time ON work_item_comments (work_item_id, created_at);

-- WIKI4AI-148: append-only, enforced by the database and not only by the code.
-- UPDATE is refused outright. DELETE is NOT refused here, because the
-- ON DELETE CASCADE above is part of SPEC's DDL and a BEFORE DELETE trigger would
-- break it: deleting a work item must still be able to take its discussion with it.
-- Removal through JPA is refused instead, by WorkItemComment's @PreRemove and by
-- WorkItemCommentRepository, whose delete methods all throw.
CREATE OR REPLACE FUNCTION wiki4ai_work_item_comments_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $trigger$
BEGIN
    RAISE EXCEPTION 'work_item_comments is append-only: % is not allowed', TG_OP;
END;
$trigger$;

CREATE TRIGGER trg_work_item_comments_append_only
    BEFORE UPDATE ON work_item_comments
    FOR EACH ROW
    EXECUTE FUNCTION wiki4ai_work_item_comments_append_only();
