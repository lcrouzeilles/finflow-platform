package com.finflow.transaction_service.domain.outbox;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxEventTest {

    @Test
    void shouldInitializeNewEventAsPending() {
        OutboxEvent event = createEvent();

        assertThat(event.getId()).isNotNull();
        assertThat(event.getAggregateType()).isEqualTo("TRANSACTION");
        assertThat(event.getAggregateId()).isNotNull();
        assertThat(event.getEventType()).isEqualTo("TRANSFER_COMPLETED");
        assertThat(event.getPayload()).contains("sourceAccountId");
        assertThat(event.getCreatedAt()).isNotNull();
        assertThat(event.getPublishedAt()).isNull();
        assertThat(event.getAttempts()).isZero();
        assertThat(event.getLastError()).isNull();
        assertThat(event.getClaimedBy()).isNull();
        assertThat(event.getClaimUntil()).isNull();
    }

    @Test
    void shouldMarkEventAsPublishedAndClearClaim() {
        OutboxEvent event = createEvent();
        event.claim("worker-1", Instant.now().plusSeconds(60));

        event.markPublished();

        assertThat(event.getPublishedAt()).isNotNull();
        assertThat(event.getLastError()).isNull();
        assertThat(event.getClaimedBy()).isNull();
        assertThat(event.getClaimUntil()).isNull();
    }

    @Test
    void shouldIncrementAttemptsAndRecordFailure() {
        OutboxEvent event = createEvent();
        event.claim("worker-1", Instant.now().plusSeconds(60));

        event.markFailed("Kafka unavailable");

        assertThat(event.getAttempts()).isEqualTo(1);
        assertThat(event.getLastError()).isEqualTo("Kafka unavailable");
        assertThat(event.getClaimedBy()).isNull();
        assertThat(event.getClaimUntil()).isNull();
    }

    @Test
    void shouldApplyExponentialRetryBackoff() {
        OutboxEvent event = createEvent();

        Instant beforeFailure = Instant.now();

        event.markFailed("Kafka unavailable");

        Instant nextAttempt = event.getNextAttemptAt();

        assertThat(nextAttempt).isNotNull();
        assertThat(nextAttempt).isAfterOrEqualTo(beforeFailure);
        assertThat(nextAttempt)
                .isBeforeOrEqualTo(beforeFailure.plusSeconds(2));
        assertThat(event.getAttempts()).isEqualTo(1);

        event.markFailed("Kafka still unavailable");

        assertThat(event.getAttempts()).isEqualTo(2);
        assertThat(event.getNextAttemptAt())
                .isAfterOrEqualTo(nextAttempt);
    }

    private OutboxEvent createEvent() {
        return new OutboxEvent(
                "TRANSACTION",
                UUID.randomUUID(),
                "TRANSFER_COMPLETED",
                """
                {"sourceAccountId":"source","destinationAccountId":"destination"}
                """
        );
    }
}