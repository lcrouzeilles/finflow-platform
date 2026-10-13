package com.finflow.transaction_service.event;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class TransferKafkaListener {

    private final TransferEventProducer producer;

    public TransferKafkaListener(
            TransferEventProducer producer
    ) {
        this.producer = producer;
    }

    @TransactionalEventListener(
            phase = TransactionPhase.AFTER_COMMIT
    )
    public void handleTransferCompleted(
            TransferCompletedEvent event
    ) {
        producer.publish(event);
    }
}
