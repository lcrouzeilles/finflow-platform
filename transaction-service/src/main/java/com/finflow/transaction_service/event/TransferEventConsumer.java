package com.finflow.transaction_service.event;

import com.finflow.transaction_service.config.CorrelationIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
@Profile("!test")
public class TransferEventConsumer {

    private static final Logger log =
            LoggerFactory.getLogger(TransferEventConsumer.class);

    @KafkaListener(topics = "finflow.transfer.completed", groupId = "finflow-transfer-consumer")
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
            log.info(
                    "Received transfer completed event: eventId={}, transactionId={}, "
                            + "sourceAccountId={}, destinationAccountId={}",
                    event.eventId(),
                    event.transactionId(),
                    event.sourceAccountId(),
                    event.destinationAccountId()
            );
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }
}
