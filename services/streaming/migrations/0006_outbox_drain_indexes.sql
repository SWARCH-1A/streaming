-- Ordered claims must not sort/scan the entire durable backlog on each delivery.
-- Keep the same per-aggregate ordering while consumers recover independently.
CREATE INDEX streaming_outbox_claim_order
 ON streaming_outbox (consumer, created_at, aggregate_id, sequence)
 WHERE published_at IS NULL AND dead_letter_at IS NULL AND closed_at IS NULL;
CREATE INDEX streaming_outbox_pending_predecessor
 ON streaming_outbox (consumer, aggregate_id, sequence)
 WHERE published_at IS NULL AND dead_letter_at IS NULL AND closed_at IS NULL;
