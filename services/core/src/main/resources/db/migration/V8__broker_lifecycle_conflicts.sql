-- Keep P1 projection reasons and allow the independent P2 lifecycle barrier to retain conflicts.
ALTER TABLE discovery.projection_conflicts DROP CONSTRAINT projection_conflicts_reason_check;
ALTER TABLE discovery.projection_conflicts ADD CONSTRAINT projection_conflicts_reason_check
    CHECK (reason IN ('EVENT_ID_REUSED','SAME_VERSION_DIFFERENT_CONTENT','CHANNEL_MISMATCH','LIFECYCLE_CONFLICT'));
