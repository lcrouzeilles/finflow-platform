package com.finflow.transaction_service.payment.reconciliation;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReconciliationWindowTest {

    @Test
    void shouldLeaveASettleGapAndOverlapPreviousRuns() {
        Instant now = Instant.parse("2026-11-02T03:00:00Z");
        ReconciliationProperties properties = new ReconciliationProperties(
                Duration.ofHours(48), Duration.ofHours(1), Duration.ofMinutes(5));

        ReconciliationWindow window = ReconciliationWindow.endingBefore(now, properties);

        assertThat(window.to()).isEqualTo(Instant.parse("2026-11-02T02:55:00Z"));
        assertThat(window.from()).isEqualTo(Instant.parse("2026-10-31T01:55:00Z"));
    }

    @Test
    void shouldRejectEmptyWindow() {
        Instant now = Instant.now();

        assertThatThrownBy(() -> new ReconciliationWindow(now, now))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
