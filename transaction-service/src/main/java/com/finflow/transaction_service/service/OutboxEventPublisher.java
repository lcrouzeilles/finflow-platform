package com.finflow.transaction_service.service;

import com.finflow.transaction_service.config.CorrelationIdFilter;
import com.finflow.transaction_service.domain.outbox.OutboxEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.transport.ReceiverContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Polls the outbox and relays events to Kafka (at-least-once).
 *
 * <p>Failure isolation: a problem with one event (deserialization, Kafka,
 * or even updating its status) never aborts the rest of the batch. If an
 * event was delivered to Kafka but its status could not be updated (for
 * example because the lease expired and another worker took it), it will
 * be re-delivered: consumers must deduplicate on {@code eventId} (see
 * {@link com.finflow.transaction_service.event.TransferCompletedHandler}).
 *
 * <p>Each {@code event_type} is handled by one {@link OutboxEventRelay}; an
 * event with no relay is marked failed (and eventually exhausted) rather
 * than blocking the batch.
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
    static final String OBSERVATION_PUBLISH = "finflow.outbox.publish";
    private static final String TRACE_PARENT = "traceparent";

    static final int BATCH_SIZE = 10;
    static final int MAX_BATCHES_PER_RUN = 50;
    private static final Duration LEASE_DURATION =
            Duration.ofMinutes(3);
    private static final int MAX_ERROR_LENGTH = 4000;

    private final OutboxEventClaimer claimer;
    private final OutboxEventStatusService statusService;
    private final Map<String, OutboxEventRelay> relays;
    private final ObservationRegistry observationRegistry;

    private final Counter publishedCounter;
    private final Counter failedCounter;
    private final Counter exhaustedCounter;
    private final Counter statusUpdateFailedCounter;

    public OutboxEventPublisher(
            OutboxEventClaimer claimer,
            OutboxEventStatusService statusService,
            List<OutboxEventRelay> relays,
            MeterRegistry meterRegistry,
            ObservationRegistry observationRegistry
    ) {
        this.claimer = claimer;
        this.statusService = statusService;
        this.relays = relays.stream().collect(Collectors.toUnmodifiableMap(
                OutboxEventRelay::eventType,
                Function.identity()
        ));
        this.observationRegistry = observationRegistry;

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
        // Keep draining while batches come back full: one batch per poll
        // caps throughput at BATCH_SIZE events per interval and instance,
        // far below the rate transfers can produce them. The bound keeps a
        // single run from holding the scheduler thread indefinitely.
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            if (claimAndPublish() < BATCH_SIZE) {
                return;
            }
        }
    }

    /**
     * Claims and publishes one batch.
     *
     * @return the number of events claimed, or {@code -1} if the publisher
     *         was interrupted
     */
    private int claimAndPublish() {
        List<OutboxEvent> events =
                claimer.claimBatch(BATCH_SIZE, LEASE_DURATION);

        for (OutboxEvent event : events) {
            // Log lines about this event carry the originating request's id.
            if (event.getCorrelationId() != null) {
                MDC.put(CorrelationIdFilter.MDC_KEY, event.getCorrelationId());
            }
            try {
                if (!publish(event)) {
                    return -1;
                }
            } finally {
                MDC.remove(CorrelationIdFilter.MDC_KEY);
            }
        }
        return events.size();
    }

    /** Returns {@code false} when the batch must stop (interrupted). */
    private boolean publish(OutboxEvent event) {
        OutboxEventRelay relay = relays.get(event.getEventType());

        if (relay == null) {
            safeMarkFailed(
                    event,
                    "No relay registered for event type "
                            + event.getEventType()
            );
            return true;
        }

        try {
            publishObservation(event).observeChecked(
                    () -> relay.publishAndAwait(event.getPayload(), event.getCorrelationId())
            );

        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            safeMarkFailed(event, "Publisher interrupted");
            return false;

        } catch (Exception exception) {
            safeMarkFailed(event, errorMessage(exception));
            return true;
        }

        safeMarkPublished(event);
        return true;
    }

    /**
     * The publication of one event, traced as a child of the request that
     * wrote it (the stored traceparent is read like an incoming message
     * header). The Kafka send happens inside it, so a single trace runs from
     * the HTTP request to the Kafka listener. Without a stored traceparent
     * the publication starts a new trace.
     */
    private Observation publishObservation(OutboxEvent event) {
        ReceiverContext<OutboxEvent> context = new ReceiverContext<>(
                (carrier, key) -> TRACE_PARENT.equals(key) ? carrier.getTraceParent() : null
        );
        context.setCarrier(event);

        return Observation.createNotStarted(OBSERVATION_PUBLISH, () -> context, observationRegistry)
                .contextualName("outbox publish")
                .lowCardinalityKeyValue("event.type", event.getEventType());
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
