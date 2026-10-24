-- P2 inbox is independent from the one-hour P1 HTTP delivery window.
CREATE TABLE discovery.broker_inbox (
    consumer TEXT NOT NULL,
    event_id TEXT NOT NULL,
    fingerprint TEXT NOT NULL,
    received_at_utc TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    outcome TEXT NOT NULL,
    PRIMARY KEY (consumer, event_id)
);
CREATE TABLE discovery.session_lifecycle (
    stream_id TEXT PRIMARY KEY,
    channel_id TEXT NOT NULL,
    session_id TEXT NOT NULL,
    stream_generation BIGINT NOT NULL,
    session_version BIGINT NOT NULL,
    status TEXT NOT NULL,
    availability TEXT NOT NULL,
    first_live_at_utc TIMESTAMPTZ,
    occurred_at_utc TIMESTAMPTZ NOT NULL,
    state_fingerprint TEXT NOT NULL
);
