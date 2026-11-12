package com.finflow.transaction_service.event;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ProcessedKafkaEventCleanupTest {

    @Test
    void shouldPurgeClaimsOlderThanTheRetention() {
        ProcessedKafkaEventRepository repository = mock(ProcessedKafkaEventRepository.class);
        Instant now = Instant.parse("2026-11-12T03:30:00Z");

        new ProcessedKafkaEventCleanup(
                repository,
                Duration.ofDays(14),
                Clock.fixed(now, ZoneOffset.UTC)
        ).purge();

        verify(repository).deleteProcessedBefore(Instant.parse("2026-10-29T03:30:00Z"));
    }
}
