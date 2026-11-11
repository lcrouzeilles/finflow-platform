-- Correlation id of the request that produced the event, relayed to Kafka
-- as the X-Correlation-Id header. Nullable: events created outside a
-- request (and rows from before this migration) have none.
ALTER TABLE outbox_events
    ADD COLUMN correlation_id VARCHAR(64);
