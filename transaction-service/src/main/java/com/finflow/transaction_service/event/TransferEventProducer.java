package com.finflow.transaction_service.event;

import com.finflow.transaction_service.config.CorrelationIdFilter;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
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

    public void publishAndAwait(TransferCompletedEvent event, String correlationId)
            throws ExecutionException, InterruptedException, TimeoutException {

        ProducerRecord<String, TransferCompletedEvent> record = new ProducerRecord<>(
                TOPIC,
                event.sourceAccountId().toString(),
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
