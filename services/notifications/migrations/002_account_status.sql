-- SPEC-20 CA-09 / SPEC-23: local copy of account.status.changed.v1 and the owner of each notice's channel.
CREATE TABLE account_status (
    user_id TEXT PRIMARY KEY,
    status TEXT NOT NULL CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    account_version BIGINT NOT NULL CHECK (account_version >= 0)
);
ALTER TABLE live_sessions ADD COLUMN owner_user_id TEXT;
ALTER TABLE notifications ADD COLUMN owner_user_id TEXT;
