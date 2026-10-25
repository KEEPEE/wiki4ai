-- WIKI4AI-135: rework of the api_tokens table created by V5 (Flyway V18).
--
-- Why this migration exists
--   V5 stored the JWT in CLEAR TEXT in token_value (NOT NULL UNIQUE) and the
--   lookup went through findByTokenValue. That is not revocable: the auth filter
--   only verifies the JWT signature and never reads api_tokens, so deleting a row
--   revoked nothing. From now on a new token is an opaque secret (w4a_ + 32 base62
--   characters) and the database stores ONLY its SHA-256 hex digest plus a short
--   display prefix. The plaintext is returned once, at creation, and is never
--   stored or listed again.
--
-- Legacy rows are preserved, not rewritten
--   token_value is relaxed to nullable instead of being dropped, so every row
--   created by V5 keeps working: AuthService still issues legacy JWTs through
--   /api/v1/auth/token/named and those rows keep their token_value. There is no
--   UPDATE here on purpose — a migration that rewrote or nulled legacy token_value
--   would silently invalidate tokens people already handed out.
--
-- Semantics:
--   token_hash   - SHA-256 of the plaintext token, lowercase hex, 64 characters.
--                  UNIQUE: Postgres treats NULLs as distinct, so any number of
--                  legacy rows with a NULL hash coexist with the constraint.
--   token_prefix - first 8 characters of the random part (without the w4a_ prefix),
--                  shown in the UI so a user can recognise a token without being
--                  able to reconstruct it. Always exactly 8 characters for a hashed
--                  token, NULL for a legacy row — never an empty string. The column
--                  width, the CHECK below and the @Column(length = 8) of the
--                  ApiToken entity all say 8 on purpose: with ddl-auto=validate the
--                  entity and the column must agree, and a width that disagrees with
--                  the value the service writes is a startup failure on dev, not a
--                  cosmetic difference.
--   scopes       - space-separated scope names (e.g. "read write"). TEXT, no CHECK
--                  constraint: an unknown scope is rejected by ApiTokenService with
--                  400, which keeps the allowed set a code-level decision that can
--                  grow without another migration.
--   actor_id     - the actor this token acts as (V15 actors). Without it there is
--                  no way to tell an agent from a human on a request, which is why
--                  WIKI4AI-134 (attribution/whoami) is scheduled after this one.
--                  ON DELETE SET NULL: removing an actor keeps the token row for
--                  audit history.
--   revoked_at   - soft revoke. NOT NULL means the token is dead and the auth
--                  filter answers 401. Soft (not DELETE) so the row stays auditable.
--   last_used_at - last time the token authenticated a request. Written throttled
--                  (30 s window, same idea as the WIKI4AI-133 heartbeat) so the
--                  filter never adds a write per request.
--
-- Every timestamp column is TIMESTAMP (without a time zone), matching V1..V17 and
-- the java.time.LocalDateTime fields of the ApiToken entity: the postgres profile
-- runs with spring.jpa.hibernate.ddl-auto=validate, so the migration and the entity
-- must agree on the JDBC type or the backend fails at startup. A time-zone column
-- type would not validate against LocalDateTime and would crash the dev instance.
--
-- expires_at already exists (V5, made nullable by V4) — it is deliberately NOT
-- re-added here.
--
-- Tests run on H2 with Flyway disabled (schema created by Hibernate from the
-- entities), so this file is exercised by the pipeline on dev, not locally.

ALTER TABLE api_tokens ALTER COLUMN token_value DROP NOT NULL;

ALTER TABLE api_tokens ADD COLUMN token_hash VARCHAR(64) UNIQUE;
ALTER TABLE api_tokens ADD COLUMN token_prefix VARCHAR(8);
ALTER TABLE api_tokens ADD COLUMN scopes TEXT;
ALTER TABLE api_tokens ADD COLUMN actor_id BIGINT REFERENCES actors(id) ON DELETE SET NULL;
ALTER TABLE api_tokens ADD COLUMN revoked_at TIMESTAMP;
ALTER TABLE api_tokens ADD COLUMN last_used_at TIMESTAMP;

-- token_prefix is always the 8 characters following "w4a_", so a stored value is
-- either NULL (legacy row) or exactly 8 characters. The check documents that and
-- catches a truncated or empty value before it can make two tokens look identical
-- in the UI.
ALTER TABLE api_tokens ADD CONSTRAINT ck_api_tokens_token_prefix
    CHECK (token_prefix IS NULL OR char_length(token_prefix) = 8);

CREATE INDEX idx_api_tokens_hash ON api_tokens (token_hash);
CREATE INDEX idx_api_tokens_actor ON api_tokens (actor_id);
CREATE INDEX idx_api_tokens_user ON api_tokens (user_id);
