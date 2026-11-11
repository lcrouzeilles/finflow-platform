package com.finflow.transaction_service.payment.event;

import tools.jackson.databind.ObjectMapper;
import com.finflow.transaction_service.config.CorrelationIdFilter;
import com.finflow.transaction_service.service.OutboxEventRelay;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Relays {@code DEPOSIT_COMPLETED} outbox rows to Kafka, keyed by account
 * so that all events of one account stay ordered within a partition.
 */
@Component
public class DepositEventProducer implements OutboxEventRelay {

    public static final String EVENT_TYPE = "DEPOSIT_COMPLETED";
    public static final String TOPIC = "finflow.deposit.completed";

    private static final long ACK_TIMEOUT_SECONDS = 10;

    private final KafkaTemplate<String, DepositCompletedEvent> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public DepositEventProducer(
            KafkaTemplate<String, DepositCompletedEvent> kafkaTemplate,
            ObjectMapper objectMapper
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public String eventType() {
        return EVENT_TYPE;
    }

    @Override
    public void publishAndAwait(String payload, String correlationId) throws Exception {
        DepositCompletedEvent event =
                objectMapper.readValue(payload, DepositCompletedEvent.class);

        ProducerRecord<String, DepositCompletedEvent> record = new ProducerRecord<>(
                TOPIC,
                event.accountId().toString(),
                event
        );
        if (correlationId != null) {
            record.headers().add(
                    CorrelationIdFilter.HEADER,
                    correlationId.getBytes(StandardCharsets.UTF_8)
            );
        }

        kafkaTemplate.send(record).get(ACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
}
