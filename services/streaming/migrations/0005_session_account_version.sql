-- A reactivation cannot revive a source authorized before its owner's suspension.
-- Existing sessions are fenced (-1) until explicitly restarted with current authority.
ALTER TABLE stream_sessions ADD COLUMN owner_account_version BIGINT NOT NULL DEFAULT -1
    CHECK (owner_account_version >= -1);

CREATE TABLE streaming_account_inbox (
    event_id UUID PRIMARY KEY,
    body_hash BYTEA NOT NULL CHECK (octet_length(body_hash)=32),
    applied_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
