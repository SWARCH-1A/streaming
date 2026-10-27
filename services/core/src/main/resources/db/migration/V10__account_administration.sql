ALTER TABLE identity.accounts
    ADD COLUMN account_status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (account_status IN ('ACTIVE','SUSPENDED')),
    ADD COLUMN account_role VARCHAR(16) NOT NULL DEFAULT 'USER' CHECK (account_role IN ('USER','ADMIN')),
    ADD COLUMN account_version BIGINT NOT NULL DEFAULT 0 CHECK (account_version BETWEEN 0 AND 9007199254740991);

-- Only active identities are published to local consumers; administrative reads use their owner.
CREATE OR REPLACE VIEW identity.public_accounts AS
    SELECT user_id,handle FROM identity.accounts WHERE account_status='ACTIVE';

CREATE TABLE identity.admin_operations (
    actor_id VARCHAR(128) NOT NULL,
    operation_id UUID NOT NULL,
    target_user_id VARCHAR(64) NOT NULL REFERENCES identity.accounts(user_id),
    requested_status VARCHAR(16) NOT NULL,
    expected_version BIGINT NOT NULL,
    reason VARCHAR(500) NOT NULL,
    result_status VARCHAR(16) NOT NULL,
    result_version BIGINT NOT NULL,
    result_handle VARCHAR(25) NOT NULL,
    occurred_at_utc TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (actor_id,operation_id)
);

CREATE TABLE identity.admin_audit (
    audit_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_id VARCHAR(128) NOT NULL,
    target_user_id VARCHAR(64) NOT NULL REFERENCES identity.accounts(user_id),
    action VARCHAR(32) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    result VARCHAR(32) NOT NULL,
    occurred_at_utc TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE identity.account_status_outbox (
    event_id UUID PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL REFERENCES identity.accounts(user_id),
    account_version BIGINT NOT NULL,
    envelope TEXT NOT NULL,
    created_at_utc TIMESTAMPTZ NOT NULL,
    published_at_utc TIMESTAMPTZ,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at_utc TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id,account_version)
);
CREATE INDEX account_status_outbox_pending ON identity.account_status_outbox(next_attempt_at_utc)
    WHERE published_at_utc IS NULL;
