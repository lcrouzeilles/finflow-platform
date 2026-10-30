package com.finflow.transaction_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finflow.transaction_service.domain.outbox.OutboxEvent;
import com.finflow.transaction_service.event.TransferCompletedEvent;
import com.finflow.transaction_service.event.TransferEventProducer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * Polls the outbox and relays events to Kafka (at-least-once).
 *
 * <p>Failure isolation: a problem with one event (deserialization, Kafka,
 * or even updating its status) never aborts the rest of the batch. If an
 * event was delivered to Kafka but its status could not be updated (for
 * example because the lease expired and another worker took it), it will
 * be re-delivered: consumers must deduplicate on {@code eventId}.
 */
@Service
@Profile("!test")
public class OutboxEventPublisher {

    private static final Logger log =
            LoggerFactory.getLogger(OutboxEventPublisher.class);

    static final String METRIC_EVENTS = "finflow.outbox.events";
    static final String RESULT_PUBLISHED = "published";
    static final String RESULT_FAILED = "failed";
    static final String RESULT_EXHAUSTED = "exhausted";
    static final String RESULT_STATUS_UPDATE_FAILED = "status_update_failed";

    private static final int BATCH_SIZE = 10;
    private static final Duration LEASE_DURATION =
            Duration.ofMinutes(3);
    private static final int MAX_ERROR_LENGTH = 4000;

    private final OutboxEventClaimer claimer;
    private final OutboxEventStatusService statusService;
    private final TransferEventProducer producer;
    private final ObjectMapper objectMapper;

    private final Counter publishedCounter;
    private final Counter failedCounter;
    private final Counter exhaustedCounter;
    private final Counter statusUpdateFailedCounter;

    public OutboxEventPublisher(
            OutboxEventClaimer claimer,
            OutboxEventStatusService statusService,
            TransferEventProducer producer,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry
    ) {
        this.claimer = claimer;
        this.statusService = statusService;
        this.producer = producer;
        this.objectMapper = objectMapper;

        this.publishedCounter = counter(meterRegistry, RESULT_PUBLISHED);
        this.failedCounter = counter(meterRegistry, RESULT_FAILED);
        this.exhaustedCounter = counter(meterRegistry, RESULT_EXHAUSTED);
        this.statusUpdateFailedCounter =
                counter(meterRegistry, RESULT_STATUS_UPDATE_FAILED);
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

            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                safeMarkFailed(event, "Publisher interrupted");
                return;

            } catch (Exception exception) {
                safeMarkFailed(event, errorMessage(exception));
                continue;
            }

            safeMarkPublished(event);
        }
    }

    private void safeMarkPublished(OutboxEvent event) {
        try {
            statusService.markPublished(event.getId(), event.getClaimedBy());
            publishedCounter.increment();
        } catch (RuntimeException exception) {
            // Delivered to Kafka but not recorded: it will be retried and
            // delivered again (at-least-once). Do not abort the batch.
            statusUpdateFailedCounter.increment();
            log.warn(
                    "Outbox event {} was published but could not be marked "
                            + "as published; it will be re-delivered: {}",
                    event.getId(),
                    exception.getMessage()
            );
        }
    }

    private void safeMarkFailed(OutboxEvent event, String error) {
        try {
            boolean exhausted = statusService.markFailed(
                    event.getId(),
                    event.getClaimedBy(),
                    error
            );

            if (exhausted) {
                exhaustedCounter.increment();
            } else {
                failedCounter.increment();
            }

            log.warn(
                    "Outbox event {} failed to publish (attempt {}): {}",
                    event.getId(),
                    event.getAttempts(),
                    error
            );
        } catch (RuntimeException exception) {
            // Typically the lease expired and another worker owns the event
            // now; its own attempt will record the outcome.
            statusUpdateFailedCounter.increment();
            log.warn(
                    "Outbox event {} failed to publish ({}) and its failure "
                            + "could not be recorded: {}",
                    event.getId(),
                    error,
                    exception.getMessage()
            );
        }
    }

    private static Counter counter(MeterRegistry registry, String result) {
        return Counter.builder(METRIC_EVENTS)
                .description("Outbox events processed by the publisher, by result")
                .tag("result", result)
                .register(registry);
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
