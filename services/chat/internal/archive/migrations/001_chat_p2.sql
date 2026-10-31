-- Chat P2 (ADR-021): copia durable de mensajes para replay, moderación y vínculos VOD.
-- Base propia de Chat; ningún otro servicio lee ni escribe estas tablas.

CREATE TABLE chat_sessions (
    session_id TEXT PRIMARY KEY,
    channel_id TEXT NOT NULL,
    moderation_version BIGINT NOT NULL DEFAULT 0,
    first_message_at_utc TIMESTAMPTZ,
    last_message_at_utc TIMESTAMPTZ,
    created_at_utc TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Un mensaje retirado conserva identidad, autor y posición para el aviso del moderador, sin texto.
CREATE TABLE chat_messages (
    session_id TEXT NOT NULL REFERENCES chat_sessions (session_id) ON DELETE CASCADE,
    sequence BIGINT NOT NULL CHECK (sequence >= 1),
    message_id TEXT NOT NULL UNIQUE,
    author_user_id TEXT NOT NULL,
    author_handle TEXT NOT NULL,
    author_display_name TEXT NOT NULL,
    author_avatar_uri TEXT,
    text TEXT,
    server_created_at_utc TIMESTAMPTZ NOT NULL,
    stream_offset_ms BIGINT NOT NULL CHECK (stream_offset_ms >= 0),
    stream_generation BIGINT NOT NULL,
    removed_at_utc TIMESTAMPTZ,
    removed_by_user_id TEXT,
    removal_version BIGINT,
    PRIMARY KEY (session_id, sequence),
    CHECK ((removed_at_utc IS NULL) = (text IS NOT NULL)),
    CHECK ((removed_at_utc IS NULL) = (removal_version IS NULL))
);
CREATE INDEX chat_messages_timeline ON chat_messages (session_id, stream_offset_ms, sequence);
CREATE INDEX chat_messages_author ON chat_messages (session_id, author_user_id, sequence);

-- Un registro por canal/cuenta; mode NULL significa sin bloqueo. version crece en cada cambio.
CREATE TABLE chat_blocks (
    channel_id TEXT NOT NULL,
    user_id TEXT NOT NULL,
    mode TEXT CHECK (mode IN ('TEMPORARY', 'PERMANENT')),
    duration TEXT CHECK (duration IN ('PT10M', 'PT1H', 'PT24H', 'P7D')),
    expires_at_utc TIMESTAMPTZ,
    blocked_at_utc TIMESTAMPTZ,
    blocked_by_user_id TEXT,
    version BIGINT NOT NULL CHECK (version >= 1),
    updated_at_utc TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (channel_id, user_id),
    CHECK ((mode = 'TEMPORARY') = (expires_at_utc IS NOT NULL AND duration IS NOT NULL)),
    CHECK ((mode IS NULL) = (blocked_at_utc IS NULL))
);
CREATE INDEX chat_blocks_active ON chat_blocks (expires_at_utc) WHERE mode IS NOT NULL;

CREATE TABLE chat_moderation_operations (
    operation_id TEXT PRIMARY KEY,
    channel_id TEXT NOT NULL,
    session_id TEXT NOT NULL,
    target_user_id TEXT NOT NULL,
    requested_by_user_id TEXT NOT NULL,
    up_to_sequence BIGINT NOT NULL,
    removed_count INTEGER NOT NULL,
    moderation_version BIGINT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('COMPLETED')),
    created_at_utc TIMESTAMPTZ NOT NULL,
    completed_at_utc TIMESTAMPTZ NOT NULL
);

CREATE TABLE chat_idempotency (
    user_id TEXT NOT NULL,
    key_hash TEXT NOT NULL,
    fingerprint TEXT NOT NULL,
    status INTEGER NOT NULL,
    response TEXT NOT NULL,
    retain_until_utc TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, key_hash)
);

-- Vínculo VOD–sesión recibido por vod.availability.changed.v1. DELETED es terminal.
CREATE TABLE chat_vod_links (
    vod_id TEXT PRIMARY KEY,
    session_id TEXT NOT NULL,
    channel_id TEXT NOT NULL,
    vod_version BIGINT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('PROCESSING', 'PUBLISHED', 'UNPUBLISHED', 'SUSPENDED', 'DELETED')),
    timeline_start_ms BIGINT NOT NULL CHECK (timeline_start_ms >= 0),
    duration_ms BIGINT CHECK (duration_ms >= 0),
    updated_at_utc TIMESTAMPTZ NOT NULL
);
CREATE INDEX chat_vod_links_session ON chat_vod_links (session_id);

CREATE TABLE chat_broker_inbox (
    consumer TEXT NOT NULL,
    event_id TEXT NOT NULL,
    fingerprint TEXT NOT NULL,
    outcome TEXT NOT NULL,
    received_at_utc TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer, event_id)
);
