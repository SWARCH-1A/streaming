CREATE TABLE core.security_audit_outbox (
 event_id uuid PRIMARY KEY,
 envelope text NOT NULL,
 created_at_utc timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 published_at_utc timestamptz,
 attempts integer NOT NULL DEFAULT 0,
 next_attempt_at_utc timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX security_audit_outbox_pending ON core.security_audit_outbox(next_attempt_at_utc,created_at_utc) WHERE published_at_utc IS NULL;
