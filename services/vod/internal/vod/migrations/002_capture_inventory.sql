ALTER TABLE vod_recordings ADD COLUMN start_snapshot jsonb;
UPDATE vod_recordings SET start_snapshot=data-'vodId'-'processingState'-'publicationState'-'restriction'-'version'-'mappingVersion'-'durationMs'-'renditions'-'createdAtUtc'-'playbackPath'-'failureCode';
CREATE TABLE vod_segments(identity text PRIMARY KEY,vod_id text NOT NULL REFERENCES vod_recordings(id),data jsonb NOT NULL);
