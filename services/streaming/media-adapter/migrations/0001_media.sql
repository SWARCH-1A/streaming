CREATE TABLE media_sources (
    publisher_id UUID PRIMARY KEY,
    stream_id TEXT NOT NULL,
    session_id TEXT NOT NULL,
    stream_generation BIGINT NOT NULL CHECK (stream_generation > 0),
    source_generation BIGINT NOT NULL CHECK (source_generation > 0),
    ingest_path TEXT NOT NULL,
    request_hash BYTEA NOT NULL CHECK (octet_length(request_hash) = 32),
    connected_at TIMESTAMPTZ,
    callback_alerted_at TIMESTAMPTZ,
    playback_at TIMESTAMPTZ,
    lost_at TIMESTAMPTZ,
    retired_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(session_id, source_generation)
);
CREATE INDEX media_sources_current ON media_sources(session_id, source_generation DESC);
CREATE TABLE media_callback_outbox (
    event_id UUID PRIMARY KEY,
    publisher_id UUID NOT NULL REFERENCES media_sources(publisher_id),
    kind TEXT NOT NULL CHECK (kind IN ('source-connected', 'playback-ready', 'source-lost')),
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    available_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    attempts INTEGER NOT NULL DEFAULT 0,
    first_attempt_at TIMESTAMPTZ,
    alerted_at TIMESTAMPTZ,
    delivered_at TIMESTAMPTZ,
    closed_at TIMESTAMPTZ,
    dead_letter_at TIMESTAMPTZ,
    last_error_code TEXT,
    claim_token UUID,
    claim_until TIMESTAMPTZ,
    UNIQUE(publisher_id, kind),
    CHECK ((claim_token IS NULL) = (claim_until IS NULL))
);
CREATE INDEX media_callback_pending ON media_callback_outbox(available_at, created_at)
    WHERE delivered_at IS NULL AND dead_letter_at IS NULL;
CREATE TABLE media_callback_operations (
    operation_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id UUID NOT NULL REFERENCES media_callback_outbox(event_id),
    action TEXT NOT NULL CHECK (action IN ('REDRIVE', 'CLOSE')),
    operator_id TEXT NOT NULL,
    note TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
