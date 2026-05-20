-- V1: Create complete schema for all tables
-- This migration creates all tables if they don't already exist.
-- For existing databases, CREATE TABLE IF NOT EXISTS is a no-op.

-- Users table (with role column included)
CREATE TABLE IF NOT EXISTS users (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(255) NOT NULL UNIQUE,
    email VARCHAR(255) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    role VARCHAR(255) NOT NULL DEFAULT 'USER' CHECK (role IN ('USER', 'ADMIN')),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

-- Projects table
CREATE TABLE IF NOT EXISTS projects (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE,
    description VARCHAR(1000),
    slug VARCHAR(255) NOT NULL UNIQUE,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

-- Documents table
CREATE TABLE IF NOT EXISTS documents (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    content TEXT,
    slug VARCHAR(255) NOT NULL,
    project_id BIGINT NOT NULL REFERENCES projects(id),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    UNIQUE (project_id, slug)
);

-- Document links (many-to-many self-reference)
CREATE TABLE IF NOT EXISTS document_links (
    source_document_id BIGINT NOT NULL REFERENCES documents(id),
    target_document_id BIGINT NOT NULL REFERENCES documents(id),
    PRIMARY KEY (source_document_id, target_document_id)
);

-- Project permissions table
CREATE TABLE IF NOT EXISTS project_permissions (
    id BIGSERIAL PRIMARY KEY,
    project_id BIGINT NOT NULL REFERENCES projects(id),
    user_id BIGINT NOT NULL REFERENCES users(id),
    UNIQUE (project_id, user_id)
);

-- Project permission values (element collection)
CREATE TABLE IF NOT EXISTS project_permission_values (
    permission_id BIGINT NOT NULL REFERENCES project_permissions(id),
    permission SMALLINT
);
