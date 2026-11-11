package com.finflow.transaction_service.service;

import com.finflow.transaction_service.repository.OutboxEventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxMetricsTest {

    private static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");

    @Mock
    private OutboxEventRepository repository;

    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        new OutboxMetrics(repository, meterRegistry, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private double gauge(String name) {
        return meterRegistry.get(name).gauge().value();
    }

    @Test
    void shouldReportPendingAndFailedEvents() {
        when(repository.countByPublishedAtIsNullAndFailedAtIsNull()).thenReturn(7L);
        when(repository.countByFailedAtIsNotNull()).thenReturn(2L);

        assertThat(gauge(OutboxMetrics.METRIC_PENDING)).isEqualTo(7);
        assertThat(gauge(OutboxMetrics.METRIC_FAILED)).isEqualTo(2);
    }

    @Test
    void shouldReportTheAgeOfTheOldestPendingEvent() {
        when(repository.findOldestPendingCreatedAt()).thenReturn(NOW.minusMillis(90_500));

        assertThat(gauge(OutboxMetrics.METRIC_OLDEST_PENDING_AGE)).isEqualTo(90.5);
    }

    @Test
    void shouldReportZeroAgeWhenNothingIsPending() {
        when(repository.findOldestPendingCreatedAt()).thenReturn(null);

        assertThat(gauge(OutboxMetrics.METRIC_OLDEST_PENDING_AGE)).isZero();
    }
}
