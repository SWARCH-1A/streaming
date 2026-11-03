-- Recovery must not scan every unalerted snapshot for each old delivery.
CREATE INDEX streaming_outbox_pending_alert
 ON streaming_outbox (consumer, aggregate_id)
 WHERE published_at IS NULL AND dead_letter_at IS NULL AND closed_at IS NULL
   AND alerted_at IS NOT NULL;
