package com.finflow.transaction_service.domain.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finflow.transaction_service.event.TransferCompletedEvent;
import com.finflow.transaction_service.event.TransferEventProducer;
import com.finflow.transaction_service.service.OutboxEventClaimer;
import com.finflow.transaction_service.service.OutboxEventPublisher;
import com.finflow.transaction_service.service.OutboxEventStatusService;
import com.finflow.transaction_service.service.TransferOutboxRelay;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxEventPublisherTest {

    @Mock
    private OutboxEventClaimer claimer;

    @Mock
    private TransferEventProducer producer;

    @Mock
    private OutboxEventStatusService statusService;

    private SimpleMeterRegistry meterRegistry;

    private OutboxEventPublisher publisher;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();

        publisher = new OutboxEventPublisher(
                claimer,
                statusService,
                List.of(new TransferOutboxRelay(producer, new ObjectMapper())),
                meterRegistry
        );
    }

    private double count(String result) {
        return meterRegistry.get("finflow.outbox.events")
                .tag("result", result)
                .counter()
                .count();
    }

    private OutboxEvent claimedEvent(String workerId) {
        OutboxEvent event = new OutboxEvent(
                "TRANSACTION",
                UUID.randomUUID(),
                "TRANSFER_COMPLETED",
                """
                {"eventId":"%s","transactionId":"%s","sourceAccountId":"%s","destinationAccountId":"%s"}
                """.formatted(
                        UUID.randomUUID(), UUID.randomUUID(),
                        UUID.randomUUID(), UUID.randomUUID()
                )
        );
        event.claim(workerId, Instant.now().plusSeconds(180));
        return event;
    }

    @Test
    void shouldPublishValidEventAndMarkItPublished() throws Exception {
        String workerId = "worker-1";
        UUID sourceAccountId = UUID.randomUUID();
        UUID destinationAccountId = UUID.randomUUID();

        OutboxEvent event = new OutboxEvent(
                "TRANSACTION",
                UUID.randomUUID(),
                "TRANSFER_COMPLETED",
                new ObjectMapper().writeValueAsString(
                        new TransferCompletedEvent(
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                sourceAccountId,
                                destinationAccountId
                        )
                )
        );

        event.claim(workerId, Instant.now().plusSeconds(180));

        when(claimer.claimBatch(any(Integer.class), any()))
                .thenReturn(List.of(event));

        publisher.publishBatch();

        verify(producer).publishAndAwait(
                any(TransferCompletedEvent.class)
        );
        verify(statusService).markPublished(event.getId(), workerId);
        verify(statusService, never()).markFailed(
                eq(event.getId()),
                eq(workerId),
                any(String.class)
        );
    }

    @Test
    void shouldMarkEventFailedWhenKafkaPublishingFails() throws Exception {
        String workerId = "worker-1";

        OutboxEvent event = new OutboxEvent(
                "TRANSACTION",
                UUID.randomUUID(),
                "TRANSFER_COMPLETED",
                """
                {"sourceAccountId":"%s","destinationAccountId":"%s"}
                """.formatted(UUID.randomUUID(), UUID.randomUUID())
        );

        event.claim(workerId, Instant.now().plusSeconds(180));

        when(claimer.claimBatch(any(Integer.class), any()))
                .thenReturn(List.of(event));

        doThrow(new RuntimeException("Kafka unavailable"))
                .when(producer)
                .publishAndAwait(any(TransferCompletedEvent.class));

        publisher.publishBatch();

        verify(statusService).markFailed(
                eq(event.getId()),
                eq(workerId),
                any(String.class)
        );
        verify(statusService, never())
                .markPublished(event.getId(), workerId);
    }

    @Test
    void shouldDoNothingWhenNoEventsAreClaimable() throws Exception {
        when(claimer.claimBatch(any(Integer.class), any()))
                .thenReturn(List.of());

        publisher.publishBatch();

        verify(producer, never())
                .publishAndAwait(any(TransferCompletedEvent.class));
    }

    @Test
    void shouldCountPublishedAndFailedEvents() throws Exception {
        String workerId = "worker-1";
        OutboxEvent ok = claimedEvent(workerId);
        OutboxEvent ko = claimedEvent(workerId);

        when(claimer.claimBatch(any(Integer.class), any()))
                .thenReturn(List.of(ok, ko));

        failPublishingOf(ko);

        when(statusService.markFailed(eq(ko.getId()), eq(workerId), any()))
                .thenReturn(false);

        publisher.publishBatch();

        assertThat(count("published")).isEqualTo(1.0);
        assertThat(count("failed")).isEqualTo(1.0);
        assertThat(count("exhausted")).isEqualTo(0.0);
    }

    @Test
    void shouldCountExhaustedEventSeparately() throws Exception {
        String workerId = "worker-1";
        OutboxEvent event = claimedEvent(workerId);

        when(claimer.claimBatch(any(Integer.class), any()))
                .thenReturn(List.of(event));

        doThrow(new RuntimeException("Kafka unavailable"))
                .when(producer)
                .publishAndAwait(any(TransferCompletedEvent.class));

        when(statusService.markFailed(eq(event.getId()), eq(workerId), any()))
                .thenReturn(true);

        publisher.publishBatch();

        assertThat(count("exhausted")).isEqualTo(1.0);
        assertThat(count("failed")).isEqualTo(0.0);
    }

    @Test
    void shouldContinueBatchWhenRecordingFailureThrows() throws Exception {
        String workerId = "worker-1";
        OutboxEvent poison = claimedEvent(workerId);
        OutboxEvent healthy = claimedEvent(workerId);

        when(claimer.claimBatch(any(Integer.class), any()))
                .thenReturn(List.of(poison, healthy));

        // The poison event cannot be published...
        failPublishingOf(poison);

        // ...and its lease was lost, so recording the failure throws too.
        when(statusService.markFailed(eq(poison.getId()), eq(workerId), any()))
                .thenThrow(new IllegalStateException(
                        "Outbox event is not claimed by worker: " + workerId));

        publisher.publishBatch();

        // The healthy event is still processed and marked published.
        verify(producer, times(2)).publishAndAwait(any(TransferCompletedEvent.class));
        verify(statusService).markPublished(healthy.getId(), workerId);
        assertThat(count("published")).isEqualTo(1.0);
        assertThat(count("status_update_failed")).isEqualTo(1.0);
    }

    @Test
    void shouldContinueBatchWhenMarkingPublishedThrows() throws Exception {
        String workerId = "worker-1";
        OutboxEvent first = claimedEvent(workerId);
        OutboxEvent second = claimedEvent(workerId);

        when(claimer.claimBatch(any(Integer.class), any()))
                .thenReturn(List.of(first, second));

        doThrow(new IllegalStateException(
                "Outbox event is not claimed by worker: " + workerId))
                .when(statusService)
                .markPublished(first.getId(), workerId);

        publisher.publishBatch();

        verify(producer, times(2)).publishAndAwait(any(TransferCompletedEvent.class));
        verify(statusService).markPublished(second.getId(), workerId);
        // A lost status update is never turned into markFailed (which would
        // throw for the same reason) and never aborts the batch.
        verify(statusService, never()).markFailed(
                eq(first.getId()), eq(workerId), any(String.class));
        assertThat(count("published")).isEqualTo(1.0);
        assertThat(count("status_update_failed")).isEqualTo(1.0);
    }

    @Test
    void shouldMarkUndeserializablePayloadAsFailedAndContinue() throws Exception {
        String workerId = "worker-1";

        OutboxEvent broken = new OutboxEvent(
                "TRANSACTION", UUID.randomUUID(), "TRANSFER_COMPLETED", "{not json");
        broken.claim(workerId, Instant.now().plusSeconds(180));

        OutboxEvent healthy = claimedEvent(workerId);

        when(claimer.claimBatch(any(Integer.class), any()))
                .thenReturn(List.of(broken, healthy));

        publisher.publishBatch();

        verify(statusService).markFailed(eq(broken.getId()), eq(workerId), any(String.class));
        verify(producer, times(1)).publishAndAwait(any(TransferCompletedEvent.class));
        verify(statusService).markPublished(healthy.getId(), workerId);
    }

    @Test
    void shouldMarkEventWithoutRelayAsFailedAndContinue() throws Exception {
        String workerId = "worker-1";

        OutboxEvent orphan = new OutboxEvent(
                "SOMETHING", UUID.randomUUID(), "UNKNOWN_TYPE", "{}");
        orphan.claim(workerId, Instant.now().plusSeconds(180));

        OutboxEvent healthy = claimedEvent(workerId);

        when(claimer.claimBatch(any(Integer.class), any()))
                .thenReturn(List.of(orphan, healthy));

        publisher.publishBatch();

        verify(statusService).markFailed(
                eq(orphan.getId()), eq(workerId), eq("No relay registered for event type UNKNOWN_TYPE"));
        verify(producer, times(1)).publishAndAwait(any(TransferCompletedEvent.class));
        verify(statusService).markPublished(healthy.getId(), workerId);
    }

    /** Makes the producer reject exactly the given outbox event. */
    private void failPublishingOf(OutboxEvent target) throws Exception {
        String targetEventId = idIn(target.getPayload());

        doAnswer(invocation -> {
            TransferCompletedEvent published = invocation.getArgument(0);
            if (published.eventId().toString().equals(targetEventId)) {
                throw new RuntimeException("Kafka unavailable");
            }
            return null;
        }).when(producer).publishAndAwait(any(TransferCompletedEvent.class));
    }

    private static String idIn(String payload) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"eventId\":\"([^\"]+)\"")
                .matcher(payload);
        if (!m.find()) {
            throw new IllegalArgumentException("payload has no eventId");
        }
        return m.group(1);
    }
}
