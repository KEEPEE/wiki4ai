CREATE TABLE vault_entries (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    title VARCHAR(255) NOT NULL,
    username_encrypted BYTEA,
    password_encrypted BYTEA NOT NULL,
    notes_encrypted BYTEA,
    url VARCHAR(2048),
    group_path VARCHAR(512),
    iv BYTEA NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_vault_user_id ON vault_entries(user_id);
CREATE INDEX idx_vault_group_path ON vault_entries(group_path);
