-- W3C traceparent of the request that produced the event, so that its
-- publication to Kafka continues the same trace. Nullable: events created
-- outside a traced request (and rows from before this migration) have none.
ALTER TABLE outbox_events
    ADD COLUMN trace_parent VARCHAR(55);
