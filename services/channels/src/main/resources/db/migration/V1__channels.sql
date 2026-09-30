CREATE SCHEMA IF NOT EXISTS channels;

-- Un canal por cuenta. owner_user_id y registration_id son referencias externas de Identity, sin FK.
CREATE TABLE channels.channels (
    channel_id VARCHAR(64) PRIMARY KEY,
    owner_user_id VARCHAR(64) NOT NULL UNIQUE,
    registration_id VARCHAR(64) NOT NULL UNIQUE,
    description VARCHAR(500),
    banner_key VARCHAR(100),
    channel_version BIGINT NOT NULL DEFAULT 0,
    created_at_utc TIMESTAMPTZ NOT NULL,
    updated_at_utc TIMESTAMPTZ NOT NULL
);

-- Cerca de provisión por registrationId: recuerda el plazo recibido y los estados terminales
-- (ABSENT tras un lookup sin canal, DELETED tras la compensación de Identity).
CREATE TABLE channels.registration_fences (
    registration_id VARCHAR(64) PRIMARY KEY,
    owner_user_id VARCHAR(64),
    pending_until_utc TIMESTAMPTZ,
    state VARCHAR(16) NOT NULL CHECK (state IN ('OPEN','ABSENT','DELETED')),
    updated_at_utc TIMESTAMPTZ NOT NULL
);

CREATE TABLE channels.banner_uploads (
    upload_id_hash CHAR(64) PRIMARY KEY,
    channel_id VARCHAR(64) NOT NULL,
    owner_user_id VARCHAR(64) NOT NULL,
    object_key VARCHAR(100) NOT NULL UNIQUE,
    content_type VARCHAR(32) NOT NULL CHECK (content_type IN ('image/jpeg','image/png','image/gif')),
    expires_at_utc TIMESTAMPTZ NOT NULL,
    created_at_utc TIMESTAMPTZ NOT NULL
);
CREATE INDEX channels_banner_upload_expiry_idx ON channels.banner_uploads(expires_at_utc);

-- Proyección de lectura del estado de emisión; Streaming es la fuente de verdad.
CREATE TABLE channels.stream_projections (
    channel_id VARCHAR(64) PRIMARY KEY,
    stream_id VARCHAR(64),
    session_id VARCHAR(64),
    stream_generation BIGINT NOT NULL DEFAULT 0,
    session_version BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'OFFLINE',
    availability VARCHAR(16) NOT NULL DEFAULT 'OFFLINE',
    title VARCHAR(100),
    category_id VARCHAR(64),
    tag_ids VARCHAR(400) NOT NULL DEFAULT '',
    metadata_version BIGINT NOT NULL DEFAULT 0,
    viewer_count BIGINT NOT NULL DEFAULT 0,
    count_version BIGINT NOT NULL DEFAULT 0,
    updated_at_utc TIMESTAMPTZ NOT NULL
);
CREATE INDEX channels_stream_projection_session_idx ON channels.stream_projections(session_id);

CREATE TABLE channels.processed_events (
    event_id VARCHAR(80) PRIMARY KEY,
    event_type VARCHAR(80) NOT NULL,
    processed_at_utc TIMESTAMPTZ NOT NULL
);

CREATE TABLE channels.outbox_events (
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
CREATE INDEX channels_outbox_pending_idx ON channels.outbox_events(occurred_at_utc) WHERE published_at_utc IS NULL;
