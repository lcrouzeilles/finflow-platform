package com.finflow.transaction_service.service;

import com.finflow.transaction_service.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Outbox backlog gauges, read from the database on every scrape so they
 * reflect all publisher instances and survive restarts (unlike the
 * per-instance {@code finflow.outbox.events} counters). A growing age of
 * the oldest pending event means the outbox is not being drained.
 */
@Component
public class OutboxMetrics {

    static final String METRIC_PENDING = "finflow.outbox.pending";
    static final String METRIC_OLDEST_PENDING_AGE = "finflow.outbox.oldest.pending.age";
    static final String METRIC_FAILED = "finflow.outbox.failed";

    private final OutboxEventRepository repository;
    private final Clock clock;

    @Autowired
    public OutboxMetrics(OutboxEventRepository repository, MeterRegistry meterRegistry) {
        this(repository, meterRegistry, Clock.systemUTC());
    }

    OutboxMetrics(OutboxEventRepository repository, MeterRegistry meterRegistry, Clock clock) {
        this.repository = repository;
        this.clock = clock;

        Gauge.builder(METRIC_PENDING, repository,
                        OutboxEventRepository::countByPublishedAtIsNullAndFailedAtIsNull)
                .description("Outbox events waiting to be published")
                .register(meterRegistry);
        Gauge.builder(METRIC_OLDEST_PENDING_AGE, this, OutboxMetrics::oldestPendingAgeSeconds)
                .description("Age of the oldest outbox event waiting to be published (0 when none)")
                .baseUnit("seconds")
                .register(meterRegistry);
        Gauge.builder(METRIC_FAILED, repository, OutboxEventRepository::countByFailedAtIsNotNull)
                .description("Outbox events that exhausted their retries and need an operator")
                .register(meterRegistry);
    }

    double oldestPendingAgeSeconds() {
        Instant oldest = repository.findOldestPendingCreatedAt();
        if (oldest == null) {
            return 0;
        }
        return Math.max(0, Duration.between(oldest, clock.instant()).toMillis() / 1000.0);
    }
}
