CREATE TABLE media_nodes (
    media_node_id TEXT PRIMARY KEY,
    display_name TEXT NOT NULL,
    control_api_url TEXT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE stream_configs (
    stream_id TEXT PRIMARY KEY,
    channel_id TEXT NOT NULL UNIQUE,
    owner_user_id TEXT NOT NULL,
    title VARCHAR(100) NOT NULL,
    category_id TEXT NOT NULL,
    tag_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    catalog_labels JSONB NOT NULL CHECK (jsonb_typeof(catalog_labels) = 'array'),
    metadata_version BIGINT NOT NULL DEFAULT 1 CHECK (metadata_version > 0),
    stream_generation BIGINT NOT NULL DEFAULT 0 CHECK (stream_generation >= 0),
    source_generation BIGINT NOT NULL DEFAULT 0 CHECK (source_generation >= 0),
    ingest_key_hash BYTEA NOT NULL,
    ingest_key_version BIGINT NOT NULL DEFAULT 1 CHECK (ingest_key_version > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT stream_configs_title_length CHECK (char_length(title) BETWEEN 1 AND 100),
    CONSTRAINT stream_configs_tags_array CHECK (jsonb_typeof(tag_ids) = 'array'),
    CONSTRAINT stream_configs_tags_limit CHECK (jsonb_array_length(tag_ids) <= 5),
    CONSTRAINT stream_configs_key_hash_size CHECK (octet_length(ingest_key_hash) = 32),
    CONSTRAINT stream_configs_key_hash_unique UNIQUE (ingest_key_hash)
);

CREATE TABLE stream_sessions (
    session_id TEXT PRIMARY KEY,
    stream_id TEXT NOT NULL REFERENCES stream_configs (stream_id),
    channel_id TEXT NOT NULL,
    stream_generation BIGINT NOT NULL CHECK (stream_generation > 0),
    source_generation BIGINT NOT NULL CHECK (source_generation > 0),
    source_claimed BOOLEAN NOT NULL DEFAULT TRUE,
    status TEXT NOT NULL CHECK (status IN ('PREPARING', 'LIVE', 'RECONNECT_GRACE', 'ENDED')),
    availability TEXT NOT NULL CHECK (availability IN ('OFFLINE', 'RECONNECTING', 'PLAYABLE')),
    session_version BIGINT NOT NULL DEFAULT 1 CHECK (session_version > 0),
    media_node_id TEXT REFERENCES media_nodes (media_node_id),
    owner_instance_id TEXT,
    fencing_token BIGINT NOT NULL DEFAULT 1 CHECK (fencing_token > 0),
    owner_lease_expires_at TIMESTAMPTZ,
    preparing_deadline_at TIMESTAMPTZ NOT NULL,
    grace_deadline_at TIMESTAMPTZ,
    playback_path TEXT,
    started_at TIMESTAMPTZ,
    ended_at TIMESTAMPTZ,
    timeline_position_ms BIGINT NOT NULL DEFAULT 0 CHECK (timeline_position_ms >= 0),
    timeline_sampled_at TIMESTAMPTZ,
    viewer_count BIGINT NOT NULL DEFAULT 0 CHECK (viewer_count >= 0),
    count_version BIGINT NOT NULL DEFAULT 0 CHECK (count_version >= 0),
    viewer_count_observed_at TIMESTAMPTZ,
    viewer_count_changed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT session_availability_matches_state CHECK (
        (status = 'LIVE' AND availability = 'PLAYABLE') OR
        (status = 'RECONNECT_GRACE' AND availability = 'RECONNECTING') OR
        (status IN ('PREPARING', 'ENDED') AND availability = 'OFFLINE')
    ),
    CONSTRAINT session_ended_timestamp_matches_state CHECK (
        (status = 'ENDED' AND ended_at IS NOT NULL) OR
        (status <> 'ENDED' AND ended_at IS NULL)
    )
);

CREATE UNIQUE INDEX stream_sessions_one_active_per_channel
    ON stream_sessions (channel_id)
    WHERE status <> 'ENDED';

CREATE UNIQUE INDEX stream_sessions_one_active_per_stream
    ON stream_sessions (stream_id)
    WHERE status <> 'ENDED';

CREATE INDEX stream_sessions_active_deadlines
    ON stream_sessions (preparing_deadline_at, grace_deadline_at)
    WHERE status IN ('PREPARING', 'RECONNECT_GRACE');

CREATE INDEX stream_sessions_active_by_media_node
    ON stream_sessions (media_node_id)
    INCLUDE (session_id)
    WHERE status <> 'ENDED' AND media_node_id IS NOT NULL;

-- Every capacity reservation locks this single row before counting active sessions.
-- That makes the platform-wide limit safe across multiple service replicas.
CREATE TABLE streaming_capacity_lock (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    revision BIGINT NOT NULL DEFAULT 0
);

INSERT INTO streaming_capacity_lock (singleton) VALUES (TRUE);

CREATE TABLE ingest_authorizations (
    ingest_attempt_id UUID PRIMARY KEY,
    payload_hash BYTEA NOT NULL,
    stream_id TEXT NOT NULL REFERENCES stream_configs (stream_id),
    session_id TEXT NOT NULL REFERENCES stream_sessions (session_id),
    stream_generation BIGINT NOT NULL,
    source_generation BIGINT NOT NULL,
    response_payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ingest_authorizations_payload_hash_size CHECK (octet_length(payload_hash) = 32)
);

CREATE TABLE streaming_idempotency (
    operation_scope TEXT NOT NULL,
    idempotency_key UUID NOT NULL,
    request_fingerprint BYTEA NOT NULL,
    response_status SMALLINT,
    response_payload JSONB,
    resource_id TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (operation_scope, idempotency_key),
    CONSTRAINT idempotency_fingerprint_size CHECK (octet_length(request_fingerprint) = 32),
    CONSTRAINT idempotency_response_pair CHECK (
        (response_status IS NULL AND response_payload IS NULL) OR
        (response_status IS NOT NULL AND response_payload IS NOT NULL)
    )
);

CREATE TABLE media_callback_inbox (
    event_id UUID PRIMARY KEY,
    event_type TEXT NOT NULL CHECK (event_type IN ('SOURCE_CONNECTED', 'PLAYBACK_READY', 'SOURCE_LOST')),
    stream_id TEXT NOT NULL,
    session_id TEXT NOT NULL,
    stream_generation BIGINT NOT NULL,
    source_generation BIGINT NOT NULL,
    payload_hash BYTEA NOT NULL,
    payload JSONB NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    available_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    processing_attempts INTEGER NOT NULL DEFAULT 0 CHECK (processing_attempts >= 0),
    processing_owner_instance_id TEXT,
    processing_lease_expires_at TIMESTAMPTZ,
    processed_at TIMESTAMPTZ,
    processing_error_code TEXT,
    CONSTRAINT callback_processing_lease_pair CHECK (
        (processing_owner_instance_id IS NULL AND processing_lease_expires_at IS NULL) OR
        (processing_owner_instance_id IS NOT NULL AND processing_lease_expires_at IS NOT NULL)
    ),
    CONSTRAINT callback_generation_positive CHECK (stream_generation > 0 AND source_generation > 0)
);

CREATE INDEX media_callback_inbox_unprocessed
    ON media_callback_inbox (available_at, received_at)
    WHERE processed_at IS NULL;

CREATE INDEX media_callback_inbox_pending_by_session
    ON media_callback_inbox (session_id, received_at, event_id)
    WHERE processed_at IS NULL;

CREATE TABLE viewer_leases (
    lease_id TEXT PRIMARY KEY,
    session_id TEXT NOT NULL REFERENCES stream_sessions (session_id),
    idempotency_key UUID NOT NULL,
    token_hash BYTEA NOT NULL,
    heartbeat_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    closed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (session_id, idempotency_key),
    CONSTRAINT viewer_lease_token_hash_size CHECK (octet_length(token_hash) = 32)
);

CREATE INDEX viewer_leases_active_by_session
    ON viewer_leases (session_id, heartbeat_at)
    WHERE closed_at IS NULL;

CREATE TABLE streaming_outbox (
    consumer TEXT NOT NULL DEFAULT 'chat' CHECK (consumer IN ('chat', 'discovery')),
    first_attempt_at TIMESTAMPTZ,
    dead_letter_at TIMESTAMPTZ,
    closed_at TIMESTAMPTZ,
    alerted_at TIMESTAMPTZ,
    event_id UUID PRIMARY KEY,
    aggregate_id TEXT NOT NULL,
    sequence BIGINT NOT NULL CHECK (sequence > 0),
    event_type TEXT NOT NULL,
    schema_version INTEGER NOT NULL DEFAULT 1 CHECK (schema_version > 0),
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    available_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    published_at TIMESTAMPTZ,
    last_error_code TEXT,
    claim_owner TEXT,
    claim_token UUID,
    claim_expires_at TIMESTAMPTZ,
    CONSTRAINT streaming_outbox_claim_state_consistent CHECK (
        (claim_owner IS NULL AND claim_token IS NULL AND claim_expires_at IS NULL)
        OR
        (claim_owner IS NOT NULL AND claim_token IS NOT NULL AND claim_expires_at IS NOT NULL)
    ),
    UNIQUE (aggregate_id, sequence)
);

CREATE INDEX streaming_outbox_pending
    ON streaming_outbox (available_at, created_at)
    WHERE published_at IS NULL;

CREATE INDEX streaming_outbox_expired_claims
    ON streaming_outbox (claim_expires_at, available_at, created_at)
    WHERE published_at IS NULL;

CREATE TABLE streaming_dead_letters (
    dead_letter_id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    aggregate_id TEXT NOT NULL,
    event_type TEXT NOT NULL,
    payload JSONB NOT NULL,
    reason_code TEXT NOT NULL,
    attempts INTEGER NOT NULL CHECK (attempts >= 0),
    first_failed_at TIMESTAMPTZ NOT NULL,
    last_failed_at TIMESTAMPTZ NOT NULL,
    closed_at TIMESTAMPTZ,
    closed_by TEXT,
    resolution_note TEXT,
    CONSTRAINT streaming_dead_letters_event_unique UNIQUE (event_id)
);

CREATE INDEX streaming_dead_letters_open
    ON streaming_dead_letters (last_failed_at)
    WHERE closed_at IS NULL;

CREATE TABLE streaming_dead_letter_operations (
    operation_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    dead_letter_id UUID NOT NULL REFERENCES streaming_dead_letters (dead_letter_id),
    event_id UUID NOT NULL,
    action TEXT NOT NULL CHECK (action IN ('REDRIVE', 'CLOSE')),
    operator_id TEXT NOT NULL CHECK (char_length(operator_id) BETWEEN 1 AND 128),
    resolution_note TEXT NOT NULL CHECK (char_length(resolution_note) BETWEEN 1 AND 1000),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX streaming_dead_letter_operations_history
    ON streaming_dead_letter_operations (dead_letter_id, occurred_at);

-- Transactional position: row ownership is retained until COMMIT. Writers acquire
-- this row before domain locks so two commits cannot publish inverted watermarks.
CREATE TABLE discovery_commit_position (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    position BIGINT NOT NULL DEFAULT 0 CHECK (position >= 0)
);
INSERT INTO discovery_commit_position(singleton) VALUES(TRUE);

CREATE TABLE streaming_discovery_state (
    stream_id TEXT PRIMARY KEY REFERENCES stream_configs(stream_id),
    projection_version BIGINT NOT NULL CHECK(projection_version > 0),
    discovery_position BIGINT NOT NULL UNIQUE CHECK(discovery_position > 0),
    payload JSONB NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX streaming_discovery_observation ON streaming_discovery_state(observed_at, stream_id);

CREATE TABLE streaming_delivery_operations (
    operation_id UUID PRIMARY KEY,
    event_id UUID NOT NULL REFERENCES streaming_outbox(event_id),
    action TEXT NOT NULL CHECK(action IN ('REDRIVE','CLOSE')),
    operator_id TEXT NOT NULL CHECK(char_length(operator_id) BETWEEN 1 AND 128),
    resolution_note TEXT NOT NULL CHECK(char_length(resolution_note) BETWEEN 1 AND 1000),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE streaming_discovery_cuts (
    snapshot_id UUID PRIMARY KEY,
    watermark BIGINT NOT NULL CHECK(watermark >= 0),
    captured_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    page_limit INTEGER NOT NULL CHECK(page_limit BETWEEN 1 AND 50)
);
CREATE TABLE streaming_discovery_cut_items (
    snapshot_id UUID NOT NULL REFERENCES streaming_discovery_cuts(snapshot_id) ON DELETE CASCADE,
    ordinal BIGINT NOT NULL CHECK(ordinal >= 0),
    payload JSONB NOT NULL,
    PRIMARY KEY(snapshot_id, ordinal)
);
CREATE TABLE streaming_discovery_cursors (
    token UUID PRIMARY KEY,
    snapshot_id UUID NOT NULL REFERENCES streaming_discovery_cuts(snapshot_id) ON DELETE CASCADE,
    ordinal BIGINT NOT NULL,
    page_limit INTEGER NOT NULL,
    UNIQUE(snapshot_id, ordinal, page_limit)
);

CREATE FUNCTION publish_stream_discovery(target_stream TEXT) RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE
    c stream_configs%ROWTYPE;
    s stream_sessions%ROWTYPE;
    p BIGINT;
    v BIGINT;
    observed TIMESTAMPTZ := clock_timestamp();
    category JSONB;
    tags JSONB;
    snapshot JSONB;
BEGIN
    -- All application writers acquire this lock before their domain locks.
    UPDATE discovery_commit_position SET position=position+1 WHERE singleton RETURNING position INTO p;
    SELECT * INTO STRICT c FROM stream_configs WHERE stream_id=target_stream;
    SELECT * INTO s FROM stream_sessions WHERE stream_id=target_stream AND stream_generation=c.stream_generation;
    SELECT jsonb_build_object('id', value->>'id', 'name', value->>'name') INTO category
      FROM jsonb_array_elements(c.catalog_labels) WHERE value->>'id'=c.category_id;
    SELECT COALESCE(jsonb_agg(jsonb_build_object('id', label->>'id', 'name', label->>'name') ORDER BY ids.ordinality), '[]'::jsonb) INTO tags
      FROM jsonb_array_elements_text(c.tag_ids) WITH ORDINALITY AS ids(id, ordinality)
      JOIN LATERAL jsonb_array_elements(c.catalog_labels) AS labels(label) ON label->>'id'=ids.id;
    IF category IS NULL OR jsonb_array_length(tags)<>jsonb_array_length(c.tag_ids) THEN
        RAISE EXCEPTION 'invalid stored catalog labels';
    END IF;
    SELECT COALESCE(MAX(projection_version),0)+1 INTO v FROM streaming_discovery_state WHERE stream_id=target_stream;
    snapshot := jsonb_build_object(
        'streamId',c.stream_id,'channelId',c.channel_id,'projectionVersion',v,'discoveryPosition',p,
        'metadataVersion',c.metadata_version,'title',c.title,'category',category,'tags',tags,
        'sessionId',s.session_id,'streamGeneration',c.stream_generation,'sessionVersion',s.session_version,
        'status',CASE WHEN s.session_id IS NULL THEN 'OFFLINE' WHEN s.status='RECONNECT_GRACE' THEN 'LIVE' ELSE s.status END,
        'availability',COALESCE(s.availability,'OFFLINE'),'startedAtUtc',s.started_at,
        'stateObservedAtUtc',observed,
        'viewerCount',CASE WHEN s.status IN ('LIVE','RECONNECT_GRACE') THEN s.viewer_count ELSE 0 END,
        'countVersion',COALESCE(s.count_version,0),
        'viewerCountObservedAtUtc',CASE WHEN s.status IN ('LIVE','RECONNECT_GRACE') THEN s.viewer_count_observed_at ELSE observed END
    );
    INSERT INTO streaming_discovery_state VALUES(target_stream,v,p,snapshot,observed)
      ON CONFLICT(stream_id) DO UPDATE SET projection_version=v,discovery_position=p,payload=snapshot,observed_at=observed;
    INSERT INTO streaming_outbox(event_id,aggregate_id,sequence,event_type,payload,consumer,created_at,available_at)
      VALUES(uuidv7(),'stream:'||target_stream,v,'StreamDiscoverySnapshot',snapshot,'discovery',observed,observed);
END $$;

CREATE FUNCTION streaming_config_snapshot_trigger() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    PERFORM publish_stream_discovery(NEW.stream_id);
    RETURN NEW;
END $$;
CREATE TRIGGER streaming_config_created_snapshot AFTER INSERT ON stream_configs
  FOR EACH ROW EXECUTE FUNCTION streaming_config_snapshot_trigger();
CREATE TRIGGER streaming_config_metadata_snapshot AFTER UPDATE OF metadata_version, catalog_labels ON stream_configs
  FOR EACH ROW WHEN(OLD.metadata_version IS DISTINCT FROM NEW.metadata_version OR OLD.catalog_labels IS DISTINCT FROM NEW.catalog_labels)
  EXECUTE FUNCTION streaming_config_snapshot_trigger();
CREATE TRIGGER streaming_session_created_snapshot AFTER INSERT ON stream_sessions
  FOR EACH ROW EXECUTE FUNCTION streaming_config_snapshot_trigger();
CREATE TRIGGER streaming_session_changed_snapshot AFTER UPDATE OF status,availability,count_version,viewer_count_observed_at ON stream_sessions
  FOR EACH ROW WHEN(OLD.status IS DISTINCT FROM NEW.status OR OLD.availability IS DISTINCT FROM NEW.availability
    OR OLD.count_version IS DISTINCT FROM NEW.count_version OR OLD.viewer_count_observed_at IS DISTINCT FROM NEW.viewer_count_observed_at)
  EXECUTE FUNCTION streaming_config_snapshot_trigger();
