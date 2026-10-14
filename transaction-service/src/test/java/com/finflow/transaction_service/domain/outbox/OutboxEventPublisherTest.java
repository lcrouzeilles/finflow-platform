package com.finflow.transaction_service.domain.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finflow.transaction_service.event.TransferCompletedEvent;
import com.finflow.transaction_service.event.TransferEventProducer;
import com.finflow.transaction_service.service.OutboxEventClaimer;
import com.finflow.transaction_service.service.OutboxEventPublisher;
import com.finflow.transaction_service.service.OutboxEventStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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

    private OutboxEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new OutboxEventPublisher(
                        claimer,
                statusService,
                        producer,
                new ObjectMapper()
                );
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
}