-- Deduplication of Kafka events (the outbox relays them at least once).
-- A consumer inserts (consumer, event_id) FIRST, in the same transaction as
-- its effect, with ON CONFLICT DO NOTHING: zero rows inserted means the
-- event was already applied. Postgres makes a concurrent duplicate wait on
-- the in-flight row, so exactly one delivery wins. The consumer is part of
-- the key so that different consumers can each apply the same event.
CREATE TABLE processed_kafka_events (
    consumer     VARCHAR(100) NOT NULL,
    event_id     UUID         NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (consumer, event_id)
);

-- Rows older than the redelivery window are purged periodically.
CREATE INDEX idx_processed_kafka_events_processed_at
    ON processed_kafka_events (processed_at);
