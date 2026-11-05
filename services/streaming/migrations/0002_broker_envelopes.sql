-- P2 keeps the original P1 payload and materializes the new wire representation once.
-- Both the domain change and this immutable envelope commit in the producer transaction.
ALTER TABLE streaming_outbox ADD COLUMN broker_envelope JSONB;

CREATE FUNCTION streaming_broker_envelope(o streaming_outbox) RETURNS JSONB
LANGUAGE plpgsql AS $$
DECLARE
    kind TEXT;
    body JSONB;
    channel TEXT;
    first_live TIMESTAMPTZ;
BEGIN
    IF o.event_type = 'StreamDiscoverySnapshot' THEN
        kind := 'stream.discovery.snapshot.v1';
        body := o.payload;
    ELSIF o.event_type IN ('StreamSessionPreparing','StreamSessionStarted','StreamSessionAvailabilityChanged','StreamSessionEnded') THEN
        kind := 'stream.session.changed.v1';
        SELECT s.channel_id, CASE WHEN o.event_type <> 'StreamSessionPreparing' THEN s.started_at END
          INTO channel, first_live FROM stream_sessions s WHERE s.session_id = o.payload->>'sessionId';
        body := o.payload || jsonb_build_object(
            'channelId', channel,
            'firstLiveAtUtc', to_char(first_live AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
            'initialLive', o.event_type = 'StreamSessionStarted');
    ELSE
        RETURN NULL;
    END IF;
    RETURN jsonb_build_object(
        'eventId', o.event_id, 'eventType', kind, 'schemaVersion', 1,
        'producer', 'streaming', 'aggregateId', o.aggregate_id, 'sequence', o.sequence,
        'occurredAtUtc', to_char(o.created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
        'correlationId', o.event_id::TEXT, 'payload', body);
END $$;

UPDATE streaming_outbox o SET broker_envelope = streaming_broker_envelope(o) WHERE published_at IS NULL;

CREATE FUNCTION capture_streaming_broker_envelope() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        NEW.broker_envelope := streaming_broker_envelope(NEW);
    ELSIF OLD.broker_envelope IS DISTINCT FROM NEW.broker_envelope THEN
        RAISE EXCEPTION 'broker envelope is immutable';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER streaming_outbox_broker_envelope
BEFORE INSERT OR UPDATE ON streaming_outbox
FOR EACH ROW EXECUTE FUNCTION capture_streaming_broker_envelope();
