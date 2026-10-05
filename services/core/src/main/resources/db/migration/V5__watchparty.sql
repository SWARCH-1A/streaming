-- Watch Party (SPEC-14, ADR-007). Extends Core without rewriting applied migrations.
CREATE SCHEMA IF NOT EXISTS watchparty;

CREATE TABLE watchparty.parties (
    party_id VARCHAR(64) PRIMARY KEY CHECK (party_id ~ '^wp_[0-9a-f]{32}$'),
    owner_user_id VARCHAR(64) NOT NULL REFERENCES identity.accounts(user_id) ON DELETE CASCADE,
    title VARCHAR(100) NOT NULL CHECK (btrim(title) <> ''),
    -- Only the SHA-256 of the opaque access code is stored; the code itself is shown once.
    access_code_hash CHAR(64) NOT NULL UNIQUE,
    status VARCHAR(6) NOT NULL CHECK (status IN ('OPEN','CLOSED')),
    party_version BIGINT NOT NULL DEFAULT 0 CHECK (party_version >= 0),
    created_at_utc TIMESTAMPTZ NOT NULL,
    updated_at_utc TIMESTAMPTZ NOT NULL,
    closed_at_utc TIMESTAMPTZ,
    CHECK ((status = 'CLOSED') = (closed_at_utc IS NOT NULL))
);
CREATE INDEX watchparty_party_owner_idx ON watchparty.parties(owner_user_id);

CREATE TABLE watchparty.party_members (
    party_id VARCHAR(64) NOT NULL REFERENCES watchparty.parties(party_id) ON DELETE CASCADE,
    user_id VARCHAR(64) NOT NULL REFERENCES identity.accounts(user_id) ON DELETE CASCADE,
    joined_at_utc TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (party_id, user_id)
);
CREATE INDEX watchparty_member_user_idx ON watchparty.party_members(user_id);

-- stream_id and channel_id are opaque contract references (Streaming owns streams); no FK by design.
CREATE TABLE watchparty.party_streams (
    party_id VARCHAR(64) NOT NULL REFERENCES watchparty.parties(party_id) ON DELETE CASCADE,
    stream_id VARCHAR(64) NOT NULL CHECK (stream_id ~ '^[A-Za-z0-9_-]{1,64}$'),
    channel_id VARCHAR(64) NOT NULL CHECK (channel_id ~ '^[A-Za-z0-9_-]{1,64}$'),
    added_at_utc TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (party_id, stream_id)
);
