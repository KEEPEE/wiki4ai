-- V4: Make expires_at nullable for infinite-expiry refresh tokens
-- When expires_at is NULL, the token never expires (infinite lifetime).

ALTER TABLE refresh_tokens ALTER COLUMN expires_at DROP NOT NULL;

