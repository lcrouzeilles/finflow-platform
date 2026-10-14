ALTER TABLE outbox_events
    ADD COLUMN claimed_by VARCHAR(200),
    ADD COLUMN claim_until TIMESTAMPTZ;

CREATE INDEX idx_outbox_events_claimable
    ON outbox_events (created_at)
    WHERE published_at IS NULL;