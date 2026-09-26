-- WIKI4AI-96: Calendar — event types + calendar events (Flyway V13).
-- Semantics: start_time NULL = all-day event (date only); non-null = precise time.
-- Visibility: 'public' events are visible to every authenticated user,
-- 'private' events only to their creator and ADMIN users.

CREATE TABLE event_entity_types (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    color VARCHAR(20),
    created_at TIMESTAMP NOT NULL
);

INSERT INTO event_entity_types (name, color, created_at) VALUES
    ('Agent task', '#4f8cff', CURRENT_TIMESTAMP),
    ('Pripomienka', '#f59e0b', CURRENT_TIMESTAMP);

CREATE TABLE calendar_events (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    description TEXT,
    event_type_id BIGINT NOT NULL REFERENCES event_entity_types(id),
    event_date DATE NOT NULL,
    start_time TIME NULL,
    end_time TIME NULL,
    visibility VARCHAR(10) NOT NULL DEFAULT 'public' CHECK (visibility IN ('public', 'private')),
    created_by BIGINT NOT NULL REFERENCES users(id),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_calendar_events_date ON calendar_events(event_date);
CREATE INDEX idx_calendar_events_type ON calendar_events(event_type_id);
CREATE INDEX idx_calendar_events_created_by ON calendar_events(created_by);
