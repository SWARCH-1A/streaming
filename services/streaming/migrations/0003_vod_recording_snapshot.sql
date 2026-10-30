-- Recording preference and metadata are copied once at capacity reservation, never at close.
CREATE TABLE recording_sessions (
 session_id TEXT PRIMARY KEY REFERENCES stream_sessions(session_id),
 start_snapshot JSONB NOT NULL,
 closed BOOLEAN NOT NULL DEFAULT FALSE
);
