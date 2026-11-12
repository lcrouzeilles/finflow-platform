package com.finflow.transaction_service.event;

import com.finflow.transaction_service.config.CorrelationIdFilter;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Listens to completed transfers and hands them to
 * {@link TransferCompletedHandler}, which ignores redeliveries.
 */
@Component
@Profile("!test")
public class TransferEventConsumer {

    private final TransferCompletedHandler handler;

    public TransferEventConsumer(TransferCompletedHandler handler) {
        this.handler = handler;
    }

    @KafkaListener(topics = "finflow.transfer.completed", groupId = TransferCompletedHandler.CONSUMER)
    public void consume(
            TransferCompletedEvent event,
            @Header(name = CorrelationIdFilter.HEADER, required = false) byte[] correlationId
    ) {
        if (correlationId != null) {
            MDC.put(
                    CorrelationIdFilter.MDC_KEY,
                    new String(correlationId, StandardCharsets.UTF_8)
            );
        }
        try {
            handler.handle(event);
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }
}
