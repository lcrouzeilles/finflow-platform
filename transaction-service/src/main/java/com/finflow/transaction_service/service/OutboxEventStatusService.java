package com.finflow.transaction_service.service;

import com.finflow.transaction_service.domain.outbox.OutboxEvent;
import com.finflow.transaction_service.repository.OutboxEventRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

@Service
public class OutboxEventStatusService {

    private final OutboxEventRepository repository;

    public OutboxEventStatusService(OutboxEventRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void markPublished(UUID eventId, String workerId) {
        OutboxEvent event = repository.findById(eventId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Outbox event not found: " + eventId
                ));

        validateClaim(event, workerId);
        event.markPublished();
    }

    @Transactional
    public void markFailed(
            UUID eventId,
            String workerId,
            String error
    ) {
        OutboxEvent event = repository.findById(eventId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Outbox event not found: " + eventId
                ));

        validateClaim(event, workerId);
        event.markFailed(error);
    }

    private void validateClaim(OutboxEvent event, String workerId) {
        if (!workerId.equals(event.getClaimedBy())) {
            throw new IllegalStateException(
                    "Outbox event is not claimed by worker: " + workerId
            );
        }

        if (event.getPublishedAt() != null) {
            throw new IllegalStateException(
                    "Outbox event has already been published: " + event.getId()
            );
        }
    }
}
