package com.finflow.transaction_service.api.admin;

import com.finflow.transaction_service.domain.outbox.OutboxEvent;

import java.time.Instant;
import java.util.UUID;

/** An outbox event as an operator sees it; the payload is left out. */
public record OutboxEventResponse(
        UUID id,
        String aggregateType,
        UUID aggregateId,
        String eventType,
        int attempts,
        String lastError,
        Instant createdAt,
        Instant failedAt,
        String correlationId
) {
    public static OutboxEventResponse from(OutboxEvent event) {
        return new OutboxEventResponse(
                event.getId(),
                event.getAggregateType(),
                event.getAggregateId(),
                event.getEventType(),
                event.getAttempts(),
                event.getLastError(),
                event.getCreatedAt(),
                event.getFailedAt(),
                event.getCorrelationId()
        );
    }
}
