CREATE TABLE identity.recovery_operations (
    operation VARCHAR(12) NOT NULL CHECK (operation IN ('REQUEST','CONFIRM','CLAIM','RESULT')),
    key_hash CHAR(64) NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    retain_until_utc TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (operation, key_hash)
);
CREATE INDEX recovery_operation_expiry_idx ON identity.recovery_operations(retain_until_utc);

CREATE TABLE identity.credential_recoveries (
    recovery_id VARCHAR(64) PRIMARY KEY,
    delivery_id VARCHAR(64) NOT NULL UNIQUE,
    user_id VARCHAR(64) NOT NULL REFERENCES identity.accounts(user_id) ON DELETE CASCADE,
    token_hash CHAR(64) NOT NULL UNIQUE,
    encrypted_material TEXT,
    created_at_utc TIMESTAMPTZ NOT NULL,
    expires_at_utc TIMESTAMPTZ NOT NULL,
    used_at_utc TIMESTAMPTZ,
    invalidated_at_utc TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 1 CHECK (version BETWEEN 1 AND 9007199254740991),
    delivery_state VARCHAR(24) NOT NULL DEFAULT 'PENDING'
        CHECK (delivery_state IN ('PENDING','CLAIMED','DELIVERED','RETRYABLE_FAILURE','PERMANENT_FAILURE','UNKNOWN')),
    delivery_attempt INTEGER NOT NULL DEFAULT 0 CHECK (delivery_attempt >= 0),
    CHECK (expires_at_utc > created_at_utc)
);
CREATE INDEX recovery_account_idx ON identity.credential_recoveries(user_id);
CREATE INDEX recovery_expiry_idx ON identity.credential_recoveries(expires_at_utc)
    WHERE encrypted_material IS NOT NULL;

CREATE TABLE identity.recovery_outbox (
    event_id VARCHAR(64) PRIMARY KEY,
    recovery_id VARCHAR(64) NOT NULL REFERENCES identity.credential_recoveries(recovery_id) ON DELETE CASCADE,
    payload TEXT NOT NULL,
    created_at_utc TIMESTAMPTZ NOT NULL,
    published_at_utc TIMESTAMPTZ,
    lease_id UUID,
    lease_until_utc TIMESTAMPTZ,
    next_attempt_at_utc TIMESTAMPTZ NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0)
);
CREATE INDEX recovery_outbox_pending_idx ON identity.recovery_outbox(next_attempt_at_utc)
    WHERE published_at_utc IS NULL;
