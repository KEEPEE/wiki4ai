-- WIKI4AI-197 (phase D1): vault crypto v2 — schema.
--
-- Purely additive. Nothing here drops a column, rewrites a value, or changes what the
-- v1 columns mean, because the rollback plan for D1 is "pin the previous image again"
-- and that only works if the image that is running today can still start and still
-- serve every vault request against the schema this file leaves behind.
--
-- ## Why the new columns are SEPARATE and not a re-use of the old ones
--
-- A v2 row keeps its v1 ciphertext until D3 drops it. That is the whole reason a
-- rollback is possible at all: the old image reads and writes password_encrypted /
-- iv and knows nothing about the v2 columns, so a row that has been re-encrypted
-- under v2 is still, from its point of view, an ordinary v1 row.
--
-- The alternative — putting v2 bytes into password_encrypted and telling the version
-- apart from a marker inside the blob — would show the old frontend a v2 blob it
-- cannot decrypt. It renders '[decryption failed]' for that (useVaultEntries.ts) and
-- the next save writes the literal back as the password. Silent, permanent loss.
--
-- ## crypto_version INTEGER, not SMALLINT
--
-- The entity maps this column to a java.lang.Integer. Hibernate's ddl-auto: validate
-- (application-postgres.yml) rejects int2 behind an Integer field and the backend
-- then refuses to start. The H2 test schema is generated from the entities, so it
-- cannot catch that; the postgres profile is the only place that would, and it is
-- only right if the DDL says INTEGER. Same spelling as every other numeric column in
-- this schema (V25, V28).
--
-- ## crypto_version DEFAULT 1
--
-- Every one of the existing rows was written by one of two writers, and both write
-- v1: the frontend (useVaultEntries.ts) and the MCP server (mcp_server.py). So 1 is
-- a true statement about all of them, and a DEFAULT is what lets an INSERT that
-- never mentions the column — the old image after a rollback, the old MCP — still
-- land on a valid row.
--
-- What 1 asserts is the FORMAT of the bytes, not that the current master password
-- can decrypt them. Rows encrypted under an older salt can exist (the browser keeps
-- its own copy of the salt in localStorage and prefers it), so per-row decryptability
-- is verified by the client at migration time, never inferred from this column.
--
-- ## entry_uuid
--
-- v2 binds the row's identity into the AES-GCM additional authenticated data, so the
-- id has to be known before the bytes are encrypted — which rules out the IDENTITY
-- primary key, assigned by the database during the INSERT. gen_random_uuid() is core
-- in PostgreSQL 13+ and the deployment runs pgvector/pgvector:pg16, so no extension
-- is needed.
--
-- ## The two CHECK constraints
--
-- ck_vault_v1_complete keeps the guarantee that password_encrypted NOT NULL used to
-- give. NOT NULL is dropped below because a v2 row has no v1 password to store, and
-- without the CHECK a title-only PUT — a request the current frontend really does
-- send when it changes nothing but the title — would quietly NULL the ciphertext
-- instead of failing. ck_vault_v2_complete is the same rule for the other format:
-- v2 bytes without the key they were wrapped under are not a readable row.
--
-- ## The trigger
--
-- This is the only enforcement of "v2 bytes are never overwritten by a v1 write"
-- that survives a rollback of the image, because it lives in the database rather
-- than in Java. It is PL/pgSQL, so the H2 suite never runs it; the postgres test
-- (VaultV2SchemaPostgresTests) is what covers it.
--
-- Two rules, both one-directional:
--   * crypto_version may never go backwards. 2 -> 1 would claim v1 bytes for a row
--     whose v1 copy may already be gone.
--   * a v2 row may not have its v1 columns changed, except by setting all four of
--     them to NULL at once. That exception is deliberate: it is the only way a v1
--     writer can release a row's v1 copy (phase D3) without tripping the guard, and
--     it cannot corrupt anything because the row's data lives in the v2 columns.
--
-- A v2 -> v2 update that only touches the v2 columns passes: the guard compares the
-- v1 columns alone.

ALTER TABLE vault_entries
    ADD COLUMN crypto_version INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN entry_uuid     UUID    NOT NULL DEFAULT gen_random_uuid(),
    ADD COLUMN key_id         VARCHAR(64),
    ADD COLUMN username_v2    BYTEA,
    ADD COLUMN password_v2    BYTEA,
    ADD COLUMN notes_v2       BYTEA;

ALTER TABLE vault_entries ALTER COLUMN password_encrypted DROP NOT NULL;
ALTER TABLE vault_entries ALTER COLUMN iv                 DROP NOT NULL;

ALTER TABLE vault_entries
    ADD CONSTRAINT uq_vault_entry_uuid  UNIQUE (entry_uuid),
    ADD CONSTRAINT ck_vault_crypto_ver  CHECK (crypto_version IN (1, 2)),
    ADD CONSTRAINT ck_vault_v1_complete CHECK (crypto_version <> 1
                 OR (password_encrypted IS NOT NULL AND iv IS NOT NULL)),
    ADD CONSTRAINT ck_vault_v2_complete CHECK (crypto_version <> 2
                 OR (password_v2 IS NOT NULL AND key_id IS NOT NULL));

ALTER TABLE users
    ADD COLUMN vault_v2_salt        VARCHAR(64),
    ADD COLUMN vault_v2_kdf         VARCHAR(255),
    ADD COLUMN vault_v2_wrapped_key TEXT,
    ADD COLUMN vault_v2_key_id      VARCHAR(64);

CREATE FUNCTION vault_entries_guard_v2() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.crypto_version = 2 AND NEW.crypto_version <> 2 THEN
        RAISE EXCEPTION 'vault entry %: crypto_version downgrade is forbidden', OLD.id;
    END IF;

    IF OLD.crypto_version = 2
       AND (NEW.password_encrypted, NEW.username_encrypted, NEW.notes_encrypted, NEW.iv)
           IS DISTINCT FROM (OLD.password_encrypted, OLD.username_encrypted, OLD.notes_encrypted, OLD.iv)
       AND NOT (NEW.password_encrypted IS NULL AND NEW.username_encrypted IS NULL
                AND NEW.notes_encrypted IS NULL AND NEW.iv IS NULL) THEN
        RAISE EXCEPTION 'vault entry %: v1 write onto a v2 row', OLD.id;
    END IF;

    RETURN NEW;
END $$;

CREATE TRIGGER trg_vault_entries_guard_v2 BEFORE UPDATE ON vault_entries
    FOR EACH ROW EXECUTE FUNCTION vault_entries_guard_v2();
