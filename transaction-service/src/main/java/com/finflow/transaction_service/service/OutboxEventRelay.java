package com.finflow.transaction_service.service;

/**
 * Delivers one kind of outbox event to its destination. The publisher picks
 * the relay by {@link #eventType()}; an event type with no relay is marked
 * failed and eventually exhausted, which the {@code exhausted} counter
 * makes visible.
 */
public interface OutboxEventRelay {

    /** The {@code outbox_events.event_type} this relay handles. */
    String eventType();

    /**
     * Deserializes the stored payload and publishes it, waiting for the
     * broker acknowledgement. Any exception marks the event as failed.
     *
     * @param correlationId id of the originating request, sent as the
     *                      X-Correlation-Id header; {@code null} when the
     *                      event has none
     */
    void publishAndAwait(String payload, String correlationId) throws Exception;
}
