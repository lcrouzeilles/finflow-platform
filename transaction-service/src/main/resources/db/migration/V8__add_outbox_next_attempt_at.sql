ALTER TABLE outbox_events
    ADD COLUMN next_attempt_at TIMESTAMPTZ;

CREATE INDEX idx_outbox_events_next_attempt
    ON outbox_events (next_attempt_at, created_at)
    WHERE published_at IS NULL;