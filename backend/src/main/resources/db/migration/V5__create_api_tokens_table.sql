-- V5: Create api_tokens table for named API token management
-- This table stores named JWT access tokens so users can manage multiple tokens.
-- Each token has an optional expiration date — when null, it never expires (infinite lifetime).

CREATE TABLE IF NOT EXISTS api_tokens (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    name VARCHAR(255) NOT NULL,
    token_value VARCHAR(512) NOT NULL UNIQUE,
    expires_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
