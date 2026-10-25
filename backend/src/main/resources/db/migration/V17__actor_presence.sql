-- WIKI4AI-133: actor presence (Flyway V17) - explicit heartbeat state on the
-- actors table created by V15.
--
-- Semantics:
--   presence_status - idle | working | busy. This is PRESENCE, deliberately kept
--                   separate from actors.status (active | paused | archived),
--                   which is the lifecycle state of the registry entry. The two
--                   must never be conflated: an archived actor is not usable for
--                   new work, a 'busy' actor is simply currently occupied.
--   presence_note   - optional free-text note sent with the heartbeat (max 256).
--
-- last_seen_at already exists (V15) and is what the heartbeat throttling window
-- compares against; it stays TIMESTAMP + java.time.LocalDateTime like every other
-- timestamp in this project (V1..V16), because the postgres profile runs with
-- spring.jpa.hibernate.ddl-auto=validate and a time-zone column type would not
-- validate against LocalDateTime and would crash the dev instance.
--
-- Tests run on H2 with Flyway disabled (schema created by Hibernate from the
-- entities), so this file is exercised by the pipeline on dev, not locally.

ALTER TABLE actors ADD COLUMN presence_status VARCHAR(16) NOT NULL DEFAULT 'idle';
ALTER TABLE actors ADD COLUMN presence_note VARCHAR(256);
ALTER TABLE actors ADD CONSTRAINT ck_actors_presence_status CHECK (presence_status IN ('idle', 'working', 'busy'));
