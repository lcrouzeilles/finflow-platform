-- Terminal state for outbox events that exhausted their retry budget.
-- A pending event is: published_at IS NULL AND failed_at IS NULL.
ALTER TABLE outbox_events
    ADD COLUMN failed_at TIMESTAMPTZ;

CREATE INDEX idx_outbox_events_failed
    ON outbox_events (failed_at)
    WHERE failed_at IS NOT NULL;

-- Identical to idx_outbox_events_pending (V6); V7 created it by mistake.
DROP INDEX IF EXISTS idx_outbox_events_claimable;
