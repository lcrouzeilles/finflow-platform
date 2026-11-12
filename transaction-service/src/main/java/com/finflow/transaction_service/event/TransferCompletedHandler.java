package com.finflow.transaction_service.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Applies a {@link TransferCompletedEvent} at most once, although Kafka may
 * deliver it several times (the outbox relays at least once).
 *
 * <p>The event id is claimed with {@code INSERT ... ON CONFLICT DO NOTHING}
 * as the first statement of the transaction, as the Stripe webhook handler
 * does. If the effect fails, the claim rolls back with it and the listener's
 * retry processes the event again.
 */
@Service
public class TransferCompletedHandler {

    static final String CONSUMER = "finflow-transfer-consumer";

    private static final Logger log =
            LoggerFactory.getLogger(TransferCompletedHandler.class);

    private final ProcessedKafkaEventRepository processedEvents;

    public TransferCompletedHandler(ProcessedKafkaEventRepository processedEvents) {
        this.processedEvents = processedEvents;
    }

    /** Returns {@code false} when the event had already been applied. */
    @Transactional
    public boolean handle(TransferCompletedEvent event) {
        int claimed = processedEvents.insertIfAbsent(CONSUMER, event.eventId(), Instant.now());

        if (claimed == 0) {
            log.info("Transfer completed event {} already processed; ignoring redelivery",
                    event.eventId());
            return false;
        }

        log.info(
                "Received transfer completed event: eventId={}, transactionId={}, "
                        + "sourceAccountId={}, destinationAccountId={}",
                event.eventId(),
                event.transactionId(),
                event.sourceAccountId(),
                event.destinationAccountId()
        );
        return true;
    }
}
