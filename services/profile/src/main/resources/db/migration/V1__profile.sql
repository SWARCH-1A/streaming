CREATE SCHEMA IF NOT EXISTS profile;

CREATE TABLE profile.profiles (
    user_id VARCHAR(64) PRIMARY KEY,
    display_name VARCHAR(50) NOT NULL,
    bio VARCHAR(300) NOT NULL DEFAULT '',
    avatar_key VARCHAR(100),
    profile_version BIGINT NOT NULL DEFAULT 0,
    created_at_utc TIMESTAMPTZ NOT NULL,
    updated_at_utc TIMESTAMPTZ NOT NULL
);

CREATE TABLE profile.avatar_uploads (
    upload_id_hash CHAR(64) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    object_key VARCHAR(100) NOT NULL UNIQUE,
    content_type VARCHAR(32) NOT NULL CHECK (content_type IN ('image/jpeg','image/png','image/gif')),
    expires_at_utc TIMESTAMPTZ NOT NULL,
    created_at_utc TIMESTAMPTZ NOT NULL
);
CREATE INDEX profile_avatar_upload_expiry_idx ON profile.avatar_uploads(expires_at_utc);

CREATE TABLE profile.outbox_events (
    event_id UUID PRIMARY KEY,
    event_type VARCHAR(80) NOT NULL,
    schema_version INTEGER NOT NULL,
    aggregate_id VARCHAR(128) NOT NULL,
    sequence_no BIGINT NOT NULL,
    occurred_at_utc TIMESTAMPTZ NOT NULL,
    payload JSONB NOT NULL,
    published_at_utc TIMESTAMPTZ,
    attempts INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX profile_outbox_pending_idx ON profile.outbox_events(occurred_at_utc) WHERE published_at_utc IS NULL;
