package com.finflow.transaction_service.exception;

import java.util.UUID;

/** The event is still pending or already published, so there is nothing to retry. */
public class OutboxEventNotRetryableException extends RuntimeException {

    public OutboxEventNotRetryableException(UUID eventId) {
        super("Outbox event " + eventId + " has not exhausted its retries");
    }
}
