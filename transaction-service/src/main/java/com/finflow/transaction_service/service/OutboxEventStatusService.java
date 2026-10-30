package com.finflow.transaction_service.service;

import com.finflow.transaction_service.domain.outbox.OutboxEvent;
import com.finflow.transaction_service.repository.OutboxEventRepository;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class OutboxEventStatusService {

    private static final Logger log =
            LoggerFactory.getLogger(OutboxEventStatusService.class);

    private final OutboxEventRepository repository;
    private final int maxAttempts;

    public OutboxEventStatusService(
            OutboxEventRepository repository,
            @Value("${finflow.outbox.max-attempts:10}") int maxAttempts
    ) {
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException(
                    "finflow.outbox.max-attempts must be positive"
            );
        }
        this.repository = repository;
        this.maxAttempts = maxAttempts;
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

    /**
     * Records a failed publication attempt.
     *
     * @return {@code true} if the event reached the configured maximum
     *         number of attempts and is now terminally failed
     */
    @Transactional
    public boolean markFailed(
            UUID eventId,
            String workerId,
            String error
    ) {
        OutboxEvent event = repository.findById(eventId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Outbox event not found: " + eventId
                ));

        validateClaim(event, workerId);

        boolean exhausted = event.markFailed(error, maxAttempts);

        if (exhausted) {
            log.error(
                    "Outbox event {} ({}/{}) exhausted {} attempts; "
                            + "marked as terminally failed. Last error: {}",
                    event.getId(),
                    event.getAggregateType(),
                    event.getAggregateId(),
                    event.getAttempts(),
                    error
            );
        }

        return exhausted;
    }

    public int getMaxAttempts() {
        return maxAttempts;
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

        if (event.isExhausted()) {
            throw new IllegalStateException(
                    "Outbox event has terminally failed: " + event.getId()
            );
        }
    }
}
