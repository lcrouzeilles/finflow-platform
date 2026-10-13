package com.finflow.transaction_service.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class TransferEventConsumer {

    private static final Logger log =
            LoggerFactory.getLogger(TransferEventConsumer.class);

    @KafkaListener(topics = "finflow.transfer.completed", groupId = "finflow-transfer-consumer")
    public void consume(TransferCompletedEvent event) {
        log.info(
                "Received transfer completed event: sourceAccountId={}, destinationAccountId={}",
                event.sourceAccountId(),
                event.destinationAccountId()
        );
    }
}