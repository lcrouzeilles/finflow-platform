package com.finflow.transaction_service.domain.outbox;

import com.finflow.transaction_service.repository.OutboxEventRepository;
import com.finflow.transaction_service.service.OutboxEventClaimer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxEventClaimerTest {

    @Mock
    private OutboxEventRepository repository;

    private OutboxEventClaimer claimer;

    @BeforeEach
    void setUp() {
        claimer = new OutboxEventClaimer(repository);
    }

    @Test
    void shouldClaimReturnedEvents() {
        OutboxEvent first = createEvent();
        OutboxEvent second = createEvent();

        when(repository.findClaimableForUpdate(any(Instant.class), eq(10)))
                .thenReturn(List.of(first, second));

        Instant beforeClaim = Instant.now();

        List<OutboxEvent> claimed =
                claimer.claimBatch(10, Duration.ofMinutes(3));

        Instant afterClaim = Instant.now();

        assertThat(claimed).containsExactly(first, second);

        for (OutboxEvent event : claimed) {
            assertThat(event.getClaimedBy()).isNotBlank();
            assertThat(event.getClaimUntil())
                    .isBetween(
                            beforeClaim.plus(Duration.ofMinutes(3)),
                            afterClaim.plus(Duration.ofMinutes(3))
                    );
        }

        verify(repository).findClaimableForUpdate(
                any(Instant.class),
                eq(10)
        );
    }

    @Test
    void shouldReturnEmptyListWhenNoEventsAreClaimable() {
        when(repository.findClaimableForUpdate(any(Instant.class), eq(10)))
                .thenReturn(List.of());

        List<OutboxEvent> claimed =
                claimer.claimBatch(10, Duration.ofMinutes(3));

        assertThat(claimed).isEmpty();
    }

    @Test
    void shouldRejectNonPositiveBatchSize() {
        assertThatThrownBy(() ->
                claimer.claimBatch(0, Duration.ofMinutes(3))
        ).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNonPositiveLeaseDuration() {
        assertThatThrownBy(() ->
                claimer.claimBatch(10, Duration.ZERO)
        ).isInstanceOf(IllegalArgumentException.class);
    }

    private OutboxEvent createEvent() {
        return new OutboxEvent(
                "TRANSACTION",
                UUID.randomUUID(),
                "TRANSFER_COMPLETED",
                "{}"
        );
    }
}