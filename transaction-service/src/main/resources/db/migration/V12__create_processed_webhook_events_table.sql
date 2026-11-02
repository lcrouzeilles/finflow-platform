-- Deduplication of Stripe webhook deliveries (at-least-once, out of order).
-- The webhook handler inserts the event id FIRST, in the same transaction
-- as the business effect, with ON CONFLICT DO NOTHING: zero rows inserted
-- means the event was already processed. Postgres makes a concurrent
-- duplicate wait on the in-flight row, so exactly one delivery wins.
CREATE TABLE processed_webhook_events (
    event_id    VARCHAR(255) PRIMARY KEY,
    type        VARCHAR(100) NOT NULL,
    received_at TIMESTAMPTZ  NOT NULL
);
