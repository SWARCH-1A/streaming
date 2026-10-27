-- SPEC-03 P2: follow relation, its durable outbox, recipient cuts and the channel VOD preference.

-- One row per (follower, channel). Unfollow keeps the row and its version so an old event
-- can never resurrect a relation that was later cancelled.
CREATE TABLE channels.follows (
    follower_user_id VARCHAR(64) NOT NULL REFERENCES identity.accounts(user_id) ON DELETE CASCADE,
    channel_id VARCHAR(64) NOT NULL REFERENCES channels.channels(channel_id) ON DELETE CASCADE,
    following BOOLEAN NOT NULL,
    follow_version BIGINT NOT NULL CHECK (follow_version BETWEEN 1 AND 9007199254740991),
    followed_at_utc TIMESTAMPTZ,
    updated_at_utc TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (follower_user_id, channel_id),
    CHECK ((following AND followed_at_utc IS NOT NULL) OR (NOT following AND followed_at_utc IS NULL))
);
CREATE INDEX channel_follows_by_follower_idx ON channels.follows(follower_user_id, followed_at_utc DESC, channel_id DESC)
    WHERE following;
CREATE INDEX channel_follows_by_channel_idx ON channels.follows(channel_id, follower_user_id) WHERE following;

-- Idempotency-Key results, bound to the principal, operation and request fingerprint.
CREATE TABLE channels.follow_operations (
    user_id VARCHAR(64) NOT NULL REFERENCES identity.accounts(user_id) ON DELETE CASCADE,
    key_hash CHAR(64) NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    response TEXT NOT NULL,
    retain_until_utc TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, key_hash)
);
CREATE INDEX channel_follow_operation_expiry_idx ON channels.follow_operations(retain_until_utc);

-- Committed together with the relation; a separate dispatcher confirms it on the broker.
CREATE TABLE channels.follow_outbox (
    event_id UUID PRIMARY KEY,
    follower_user_id VARCHAR(64) NOT NULL,
    channel_id VARCHAR(64) NOT NULL,
    follow_version BIGINT NOT NULL,
    payload TEXT NOT NULL,
    created_at_utc TIMESTAMPTZ NOT NULL,
    published_at_utc TIMESTAMPTZ,
    lease_id UUID,
    lease_until_utc TIMESTAMPTZ,
    next_attempt_at_utc TIMESTAMPTZ NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    UNIQUE (follower_user_id, channel_id, follow_version)
);
CREATE INDEX channel_follow_outbox_pending_idx ON channels.follow_outbox(next_attempt_at_utc)
    WHERE published_at_utc IS NULL;

-- A fixed cut of followers per live session: later pages never mix inventories.
CREATE TABLE channels.recipient_snapshots (
    snapshot_id VARCHAR(64) PRIMARY KEY,
    channel_id VARCHAR(64) NOT NULL REFERENCES channels.channels(channel_id) ON DELETE CASCADE,
    session_id VARCHAR(128) NOT NULL,
    captured_at_utc TIMESTAMPTZ NOT NULL,
    expires_at_utc TIMESTAMPTZ NOT NULL,
    recipient_count INTEGER NOT NULL CHECK (recipient_count >= 0),
    UNIQUE (channel_id, session_id),
    CHECK (expires_at_utc > captured_at_utc)
);
CREATE INDEX channel_recipient_snapshot_expiry_idx ON channels.recipient_snapshots(expires_at_utc);
CREATE TABLE channels.recipient_snapshot_members (
    snapshot_id VARCHAR(64) NOT NULL REFERENCES channels.recipient_snapshots(snapshot_id) ON DELETE CASCADE,
    user_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (snapshot_id, user_id)
);

-- Single authoritative autopublication preference (P2-09); Streaming copies it when a session opens.
ALTER TABLE channels.channels
    ADD COLUMN autopublish_enabled BOOLEAN NOT NULL DEFAULT true,
    ADD COLUMN autopublish_version BIGINT NOT NULL DEFAULT 1
        CHECK (autopublish_version BETWEEN 1 AND 9007199254740991);
