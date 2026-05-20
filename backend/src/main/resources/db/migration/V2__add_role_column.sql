-- V2: Add role column to users table if missing
-- This handles existing databases where the users table was created before the role column existed.
-- On fresh databases (where V1 created the full schema), this is a no-op.

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'users' AND column_name = 'role'
    ) THEN
        ALTER TABLE users ADD COLUMN role VARCHAR(255) NOT NULL DEFAULT 'USER' CHECK (role IN ('USER', 'ADMIN'));
    END IF;
END $$;
