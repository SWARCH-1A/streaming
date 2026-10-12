-- Extend the Core baseline without rewriting its applied Flyway history.
ALTER TABLE channels.channels ALTER COLUMN description TYPE VARCHAR(500);
ALTER TABLE channels.channels ADD COLUMN banner_key VARCHAR(100);

CREATE TABLE channels.banner_uploads (
    upload_id_hash CHAR(64) PRIMARY KEY,
    channel_id VARCHAR(64) NOT NULL,
    owner_user_id VARCHAR(64) NOT NULL,
    object_key VARCHAR(100) NOT NULL UNIQUE,
    content_type VARCHAR(32) NOT NULL CHECK (content_type IN ('image/jpeg','image/png','image/gif')),
    expires_at_utc TIMESTAMPTZ NOT NULL,
    created_at_utc TIMESTAMPTZ NOT NULL,
    CHECK (expires_at_utc > created_at_utc),
    FOREIGN KEY (channel_id,owner_user_id) REFERENCES channels.channels(channel_id,owner_user_id) ON DELETE CASCADE
);
CREATE INDEX channels_banner_upload_expiry_idx ON channels.banner_uploads(expires_at_utc);
