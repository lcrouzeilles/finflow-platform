package com.finflow.transaction_service.service;

import com.finflow.transaction_service.domain.outbox.OutboxEvent;
import com.finflow.transaction_service.repository.OutboxEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class OutboxEventClaimer {

    private final OutboxEventRepository outboxEventRepository;
    private final String workerId = UUID.randomUUID().toString();

    public OutboxEventClaimer(
            OutboxEventRepository outboxEventRepository
    ) {
        this.outboxEventRepository = outboxEventRepository;
    }

    @Transactional
    public List<OutboxEvent> claimBatch(
            int batchSize,
            Duration leaseDuration
    ) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException(
                    "Batch size must be positive"
            );
        }

        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException(
                    "Lease duration must be positive"
            );
        }

        Instant now = Instant.now();

        List<OutboxEvent> events =
                outboxEventRepository.findClaimableForUpdate(
                        now,
                        batchSize
                );

        Instant leaseUntil = now.plus(leaseDuration);

        events.forEach(event ->
                event.claim(workerId, leaseUntil)
        );

        return events;
    }
}