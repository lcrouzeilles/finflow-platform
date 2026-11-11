package com.finflow.transaction_service.service;

import com.finflow.transaction_service.domain.outbox.OutboxEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static com.finflow.transaction_service.service.OutboxEventPublisher.BATCH_SIZE;
import static com.finflow.transaction_service.service.OutboxEventPublisher.MAX_BATCHES_PER_RUN;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** One scheduled run drains the outbox while batches come back full. */
@ExtendWith(MockitoExtension.class)
class OutboxEventPublisherDrainTest {

    @Mock
    private OutboxEventClaimer claimer;

    @Mock
    private OutboxEventStatusService statusService;

    @Mock
    private OutboxEventRelay relay;

    private OutboxEventPublisher publisher;

    @BeforeEach
    void setUp() {
        when(relay.eventType()).thenReturn("TRANSFER_COMPLETED");
        publisher = new OutboxEventPublisher(
                claimer,
                statusService,
                List.of(relay),
                new SimpleMeterRegistry(),
                ObservationRegistry.NOOP
        );
    }

    private static List<OutboxEvent> batchOf(int size) {
        return IntStream.range(0, size)
                .mapToObj(i -> {
                    OutboxEvent event = new OutboxEvent(
                            "TRANSACTION", UUID.randomUUID(), "TRANSFER_COMPLETED", "{}");
                    event.claim("worker-1", Instant.now().plusSeconds(180));
                    return event;
                })
                .toList();
    }

    @Test
    void shouldKeepClaimingWhileBatchesAreFull() throws Exception {
        when(claimer.claimBatch(anyInt(), any())).thenReturn(
                batchOf(BATCH_SIZE), batchOf(BATCH_SIZE), batchOf(3));

        publisher.publishBatch();

        verify(claimer, times(3)).claimBatch(anyInt(), any());
        verify(relay, times(2 * BATCH_SIZE + 3)).publishAndAwait(any(), any());
    }

    @Test
    void shouldStopAfterAPartialBatch() throws Exception {
        when(claimer.claimBatch(anyInt(), any())).thenReturn(batchOf(BATCH_SIZE - 1));

        publisher.publishBatch();

        verify(claimer, times(1)).claimBatch(anyInt(), any());
    }

    @Test
    void shouldBoundTheBatchesOfOneRun() throws Exception {
        when(claimer.claimBatch(anyInt(), any()))
                .thenAnswer(invocation -> batchOf(BATCH_SIZE));

        publisher.publishBatch();

        verify(claimer, times(MAX_BATCHES_PER_RUN)).claimBatch(anyInt(), any());
    }

    @Test
    void shouldStopDrainingWhenInterrupted() throws Exception {
        when(claimer.claimBatch(anyInt(), any())).thenReturn(batchOf(BATCH_SIZE));
        doAnswer(invocation -> {
            throw new InterruptedException("shutting down");
        }).when(relay).publishAndAwait(any(), any());

        try {
            publisher.publishBatch();
        } finally {
            // Clear the flag the publisher restored, for the next tests.
            Thread.interrupted();
        }

        verify(claimer, times(1)).claimBatch(anyInt(), any());
        verify(relay, times(1)).publishAndAwait(any(), any());
        verify(statusService, never()).markPublished(any(), any());
    }
}
