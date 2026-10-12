-- A shared Core quota must not multiply when Core is replicated. Hashes contain no raw IP addresses.
CREATE TABLE discovery.rate_buckets (
    bucket_hash CHAR(64) PRIMARY KEY,
    tokens DOUBLE PRECISION NOT NULL CHECK (tokens >= 0),
    refilled_at TIMESTAMPTZ NOT NULL,
    touched_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX discovery_rate_idle ON discovery.rate_buckets(touched_at);
CREATE TABLE discovery.rate_events (
    bucket_hash CHAR(64) NOT NULL REFERENCES discovery.rate_buckets(bucket_hash) ON DELETE CASCADE,
    admitted_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX discovery_rate_window ON discovery.rate_events(bucket_hash,admitted_at);
