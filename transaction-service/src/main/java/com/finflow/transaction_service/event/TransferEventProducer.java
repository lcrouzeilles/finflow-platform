package com.finflow.transaction_service.event;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class TransferEventProducer {

    private static final String TOPIC = "finflow.transfer.completed";

    private final KafkaTemplate<String, TransferCompletedEvent> kafkaTemplate;

    public TransferEventProducer(
            KafkaTemplate<String, TransferCompletedEvent> kafkaTemplate
    ) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publish(TransferCompletedEvent event) {
        kafkaTemplate.send(
                TOPIC,
                event.sourceAccountId().toString(),
                event
        );
    }
}