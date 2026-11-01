-- Source generations increase per stream, including across distinct sessions.
-- Recover the immutable session boundary from committed ingest authorizations.
ALTER TABLE recording_sessions ADD COLUMN initial_source_generation BIGINT;

UPDATE recording_sessions recording
SET initial_source_generation = (
    SELECT MIN(ingest.source_generation)
    FROM ingest_authorizations ingest
    WHERE ingest.session_id = recording.session_id
);

ALTER TABLE recording_sessions
    ALTER COLUMN initial_source_generation SET NOT NULL,
    ADD CONSTRAINT recording_initial_generation_positive CHECK (initial_source_generation > 0);
