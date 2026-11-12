package com.finflow.transaction_service.exception;

import java.util.UUID;

public class OutboxEventNotFoundException extends RuntimeException {

    public OutboxEventNotFoundException(UUID eventId) {
        super("Outbox event not found with id: " + eventId);
    }
}
