package com.finflow.transaction_service.domain.outbox;

import com.finflow.transaction_service.config.CorrelationIdFilter;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

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
    void shouldCaptureTheCorrelationIdOfTheCurrentRequest() {
        MDC.put(CorrelationIdFilter.MDC_KEY, "demo-123");
        try {
            assertThat(createEvent().getCorrelationId()).isEqualTo("demo-123");
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }

    @Test
    void shouldHaveNoCorrelationIdOutsideARequest() {
        assertThat(createEvent().getCorrelationId()).isNull();
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

    @Test
    void shouldBecomeTerminalWhenMaxAttemptsIsReached() {
        OutboxEvent event = createEvent();

        assertThat(event.markFailed("first", 2)).isFalse();
        assertThat(event.isExhausted()).isFalse();
        assertThat(event.getFailedAt()).isNull();
        assertThat(event.getNextAttemptAt()).isNotNull();

        assertThat(event.markFailed("second", 2)).isTrue();
        assertThat(event.isExhausted()).isTrue();
        assertThat(event.getFailedAt()).isNotNull();
        assertThat(event.getNextAttemptAt()).isNull();
        assertThat(event.getAttempts()).isEqualTo(2);
        assertThat(event.getLastError()).isEqualTo("second");
        assertThat(event.getClaimedBy()).isNull();
    }

    @Test
    void shouldNeverBecomeTerminalWithoutMaxAttempts() {
        OutboxEvent event = createEvent();

        for (int i = 0; i < 50; i++) {
            event.markFailed("Kafka unavailable");
        }

        assertThat(event.isExhausted()).isFalse();
        assertThat(event.getAttempts()).isEqualTo(50);
        assertThat(event.getNextAttemptAt()).isNotNull();
    }

    @Test
    void shouldRejectNonPositiveMaxAttempts() {
        OutboxEvent event = createEvent();

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> event.markFailed("x", 0)
        ).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldUseCallerSuppliedId() {
        UUID id = UUID.randomUUID();

        OutboxEvent event = new OutboxEvent(
                id, "TRANSACTION", UUID.randomUUID(), "TRANSFER_COMPLETED", "{}"
        );

        assertThat(event.getId()).isEqualTo(id);
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