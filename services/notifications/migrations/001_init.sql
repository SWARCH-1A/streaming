-- SPEC-20: inbox, preferences, devices, logical notices and per-medium delivery attempts.

-- Broker inbox: one row per (consumer, eventId); same id with another body is a conflict.
CREATE TABLE inbox_events (
    consumer TEXT NOT NULL,
    event_id TEXT NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    outcome TEXT NOT NULL,
    received_at_utc TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer, event_id)
);

-- Durable record per live session, written before selecting recipients. Reconnecting never selects again.
CREATE TABLE live_sessions (
    session_id TEXT PRIMARY KEY,
    channel_id TEXT NOT NULL,
    first_live_at_utc TIMESTAMPTZ NOT NULL,
    title TEXT,
    link_path TEXT,
    state TEXT NOT NULL CHECK (state IN ('SELECTING', 'SELECTED')),
    recipients_cursor TEXT,
    selected_count INTEGER NOT NULL DEFAULT 0,
    created_at_utc TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at_utc TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One logical notice per (kind, user, session).
CREATE TABLE notifications (
    notification_id TEXT PRIMARY KEY,
    kind TEXT NOT NULL CHECK (kind IN ('LIVE_STARTED')),
    user_id TEXT NOT NULL,
    channel_id TEXT NOT NULL,
    session_id TEXT NOT NULL,
    title TEXT NOT NULL,
    link_path TEXT NOT NULL,
    created_at_utc TIMESTAMPTZ NOT NULL,
    read_at_utc TIMESTAMPTZ,
    UNIQUE (kind, user_id, session_id)
);
CREATE INDEX notifications_by_user_idx ON notifications (user_id, created_at_utc DESC, notification_id DESC);

-- Each medium and target has its own id and outcome; one failing medium never repeats another.
CREATE TABLE deliveries (
    delivery_id TEXT PRIMARY KEY,
    notification_id TEXT NOT NULL REFERENCES notifications (notification_id) ON DELETE CASCADE,
    medium TEXT NOT NULL CHECK (medium IN ('INBOX', 'EMAIL', 'PUSH')),
    target_id TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('PENDING', 'IN_PROGRESS', 'DELIVERED', 'RETRYABLE_FAILURE',
                                         'PERMANENT_FAILURE', 'UNKNOWN')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    last_cause TEXT,
    updated_at_utc TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (notification_id, medium, target_id)
);

-- Delivery jobs are committed with their delivery row and published afterwards.
CREATE TABLE outbox (
    event_id UUID PRIMARY KEY,
    routing_key TEXT NOT NULL,
    payload TEXT NOT NULL,
    created_at_utc TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at_utc TIMESTAMPTZ,
    lease_until_utc TIMESTAMPTZ,
    next_attempt_at_utc TIMESTAMPTZ NOT NULL DEFAULT now(),
    attempts INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX outbox_pending_idx ON outbox (next_attempt_at_utc) WHERE published_at_utc IS NULL;

-- General switches: email and push are off until the user enables them. Missing row = version 1, both off.
CREATE TABLE medium_preferences (
    user_id TEXT PRIMARY KEY,
    email_enabled BOOLEAN NOT NULL,
    push_enabled BOOLEAN NOT NULL,
    version BIGINT NOT NULL CHECK (version >= 1),
    updated_at_utc TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE channel_preferences (
    user_id TEXT NOT NULL,
    channel_id TEXT NOT NULL,
    email_enabled BOOLEAN NOT NULL,
    push_enabled BOOLEAN NOT NULL,
    version BIGINT NOT NULL CHECK (version >= 1),
    updated_at_utc TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, channel_id)
);

-- A push installation belongs to one account at a time; the provider token is never returned.
CREATE TABLE devices (
    installation_id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    platform TEXT NOT NULL CHECK (platform IN ('ANDROID', 'IOS', 'WEB')),
    provider_token TEXT NOT NULL,
    enabled BOOLEAN NOT NULL,
    version BIGINT NOT NULL CHECK (version >= 1),
    updated_at_utc TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX devices_by_user_idx ON devices (user_id) WHERE enabled;

-- Local copy of channel.follow.changed.v1, applied only when the version increases.
CREATE TABLE follow_projection (
    user_id TEXT NOT NULL,
    channel_id TEXT NOT NULL,
    following BOOLEAN NOT NULL,
    follow_version BIGINT NOT NULL,
    PRIMARY KEY (user_id, channel_id)
);

-- Recovery email attempts: identifiers and phase only, never address, token or link.
CREATE TABLE recovery_attempts (
    delivery_id TEXT PRIMARY KEY,
    recovery_id TEXT NOT NULL,
    attempt INTEGER NOT NULL CHECK (attempt BETWEEN 1 AND 2147483647),
    phase TEXT NOT NULL CHECK (phase IN ('CLAIMING', 'SENDING', 'RESULT_PENDING', 'REPORTED', 'EXPIRED')),
    outcome TEXT CHECK (outcome IN ('DELIVERED', 'RETRYABLE_FAILURE', 'PERMANENT_FAILURE', 'UNKNOWN')),
    claim_key UUID NOT NULL,
    result_key UUID,
    updated_at_utc TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Idempotency-Key results for public mutations, bound to user, operation and fingerprint.
CREATE TABLE idempotency (
    user_id TEXT NOT NULL,
    key_hash CHAR(64) NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    status INTEGER NOT NULL,
    response TEXT NOT NULL,
    retain_until_utc TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, key_hash)
);
