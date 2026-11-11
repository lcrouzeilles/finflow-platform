package com.finflow.transaction_service.service;

import tools.jackson.databind.ObjectMapper;
import com.finflow.transaction_service.event.TransferCompletedEvent;
import com.finflow.transaction_service.event.TransferEventProducer;
import org.springframework.stereotype.Component;

/** Relays {@code TRANSFER_COMPLETED} outbox rows to Kafka. */
@Component
public class TransferOutboxRelay implements OutboxEventRelay {

    public static final String EVENT_TYPE = "TRANSFER_COMPLETED";

    private final TransferEventProducer producer;
    private final ObjectMapper objectMapper;

    public TransferOutboxRelay(
            TransferEventProducer producer,
            ObjectMapper objectMapper
    ) {
        this.producer = producer;
        this.objectMapper = objectMapper;
    }

    @Override
    public String eventType() {
        return EVENT_TYPE;
    }

    @Override
    public void publishAndAwait(String payload, String correlationId) throws Exception {
        TransferCompletedEvent event =
                objectMapper.readValue(payload, TransferCompletedEvent.class);

        producer.publishAndAwait(event, correlationId);
    }
}
