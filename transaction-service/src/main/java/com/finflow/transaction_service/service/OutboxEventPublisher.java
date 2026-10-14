
package com.finflow.transaction_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finflow.transaction_service.domain.outbox.OutboxEvent;

import com.finflow.transaction_service.event.TransferCompletedEvent;
import com.finflow.transaction_service.event.TransferEventProducer;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

@Service
@Profile("!test")
public class OutboxEventPublisher {

    private static final int BATCH_SIZE = 10;
    private static final Duration LEASE_DURATION =
            Duration.ofMinutes(3);
    private static final int MAX_ERROR_LENGTH = 4000;

    private final OutboxEventClaimer claimer;
    private final OutboxEventStatusService statusService;
    private final TransferEventProducer producer;
    private final ObjectMapper objectMapper;

    public OutboxEventPublisher(
            OutboxEventClaimer claimer,
            OutboxEventStatusService statusService,
            TransferEventProducer producer,
            ObjectMapper objectMapper
    ) {
        this.claimer = claimer;
        this.statusService = statusService;
        this.producer = producer;
        this.objectMapper = objectMapper;
    }

    @Scheduled(
            fixedDelayString = "${finflow.outbox.poll-interval-ms:1000}"
    )
    public void publishBatch() {
        List<OutboxEvent> events =
                claimer.claimBatch(BATCH_SIZE, LEASE_DURATION);

        for (OutboxEvent event : events) {
            try {
                TransferCompletedEvent transferEvent =
                        objectMapper.readValue(
                                event.getPayload(),
                                TransferCompletedEvent.class
                        );

                producer.publishAndAwait(transferEvent);

                statusService.markPublished(
                        event.getId(),
                        event.getClaimedBy()
                );

            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();

                statusService.markFailed(
                        event.getId(),
                        event.getClaimedBy(),
                        "Publisher interrupted"
                );

                return;

            } catch (Exception exception) {
                statusService.markFailed(
                        event.getId(),
                        event.getClaimedBy(),
                        errorMessage(exception)
                );
            }
        }
    }

    private String errorMessage(Exception exception) {
        String message = exception.getMessage();

        if (message == null || message.isBlank()) {
            message = exception.getClass().getSimpleName();
        }

        return message.length() <= MAX_ERROR_LENGTH
                ? message
                : message.substring(0, MAX_ERROR_LENGTH);
    }
}
