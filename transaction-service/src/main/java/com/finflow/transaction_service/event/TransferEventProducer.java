package com.finflow.transaction_service.event;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class TransferEventProducer {

    private static final String TOPIC = "finflow.transfer.completed";
    private static final long ACK_TIMEOUT_SECONDS = 10;

    private final KafkaTemplate<String, TransferCompletedEvent> kafkaTemplate;

    public TransferEventProducer(
            KafkaTemplate<String, TransferCompletedEvent> kafkaTemplate
    ) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publishAndAwait(TransferCompletedEvent event)
            throws ExecutionException, InterruptedException, TimeoutException {

        kafkaTemplate.send(
                TOPIC,
                event.sourceAccountId().toString(),
                event
        ).get(ACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
}
