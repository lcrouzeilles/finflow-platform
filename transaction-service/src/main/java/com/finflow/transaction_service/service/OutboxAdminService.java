package com.finflow.transaction_service.service;

import com.finflow.transaction_service.domain.outbox.OutboxEvent;
import com.finflow.transaction_service.exception.OutboxEventNotFoundException;
import com.finflow.transaction_service.exception.OutboxEventNotRetryableException;
import com.finflow.transaction_service.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Operator actions on outbox events that exhausted their retries (the
 * {@code OutboxEventsExhausted} alert): list them and, once the cause is
 * fixed, put them back in the queue for the publisher.
 */
@Service
public class OutboxAdminService {

    private static final Logger log =
            LoggerFactory.getLogger(OutboxAdminService.class);

    private final OutboxEventRepository repository;

    public OutboxAdminService(OutboxEventRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<OutboxEvent> findExhausted(int limit) {
        return repository.findByFailedAtIsNotNullOrderByFailedAtAsc(Limit.of(limit));
    }

    @Transactional
    public OutboxEvent retry(UUID eventId, String operator) {
        OutboxEvent event = repository.findById(eventId)
                .orElseThrow(() -> new OutboxEventNotFoundException(eventId));

        if (!event.isExhausted()) {
            throw new OutboxEventNotRetryableException(eventId);
        }

        event.requeue();

        log.warn("Outbox event {} ({}) requeued by {}; last error: {}",
                event.getId(), event.getEventType(), operator, event.getLastError());
        return event;
    }
}
