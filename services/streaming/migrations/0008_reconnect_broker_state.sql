-- Preserve the P1 wire and existing immutable envelopes.
CREATE OR REPLACE FUNCTION streaming_broker_envelope(o streaming_outbox) RETURNS JSONB
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
        -- P1 exposes LIVE while reconnecting; P2 names the internal grace state.
        -- Normalize only the newly captured P2 envelope, never historical messages.
        body := o.payload || CASE
            WHEN o.payload->>'availability' = 'RECONNECTING'
            THEN jsonb_build_object('status', 'RECONNECT_GRACE')
            ELSE '{}'::jsonb END || jsonb_build_object(
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

