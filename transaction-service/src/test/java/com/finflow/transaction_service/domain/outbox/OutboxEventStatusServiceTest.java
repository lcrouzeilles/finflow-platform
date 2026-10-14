package com.finflow.transaction_service.domain.outbox;

import com.finflow.transaction_service.repository.OutboxEventRepository;
import com.finflow.transaction_service.service.OutboxEventStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxEventStatusServiceTest {

    @Mock
    private OutboxEventRepository repository;

    private OutboxEventStatusService statusService;

    @BeforeEach
    void setUp() {
        statusService = new OutboxEventStatusService(repository);
    }

    @Test
    void shouldMarkClaimedEventAsPublished() {
        String workerId = "worker-1";
        OutboxEvent event = createClaimedEvent(workerId);

        when(repository.findById(event.getId()))
                .thenReturn(Optional.of(event));

        statusService.markPublished(event.getId(), workerId);

        assertThat(event.getPublishedAt()).isNotNull();
        assertThat(event.getLastError()).isNull();
        assertThat(event.getClaimedBy()).isNull();
    }

    @Test
    void shouldRecordFailureForClaimedEvent() {
        String workerId = "worker-1";
        OutboxEvent event = createClaimedEvent(workerId);

        when(repository.findById(event.getId()))
                .thenReturn(Optional.of(event));

        statusService.markFailed(
                event.getId(),
                workerId,
                "Kafka unavailable"
        );

        assertThat(event.getAttempts()).isEqualTo(1);
        assertThat(event.getLastError()).isEqualTo("Kafka unavailable");
        assertThat(event.getClaimedBy()).isNull();
    }

    @Test
    void shouldRejectUpdateFromAnotherWorker() {
        OutboxEvent event = createClaimedEvent("worker-1");

        when(repository.findById(event.getId()))
                .thenReturn(Optional.of(event));

        assertThatThrownBy(() ->
                statusService.markPublished(event.getId(), "worker-2")
        ).isInstanceOf(IllegalStateException.class);

        assertThat(event.getPublishedAt()).isNull();
        assertThat(event.getClaimedBy()).isEqualTo("worker-1");
    }

    @Test
    void shouldRejectUpdateWhenEventDoesNotExist() {
        UUID eventId = UUID.randomUUID();

        when(repository.findById(eventId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                statusService.markPublished(eventId, "worker-1")
        ).isInstanceOf(RuntimeException.class);
    }

    private OutboxEvent createClaimedEvent(String workerId) {
        OutboxEvent event = new OutboxEvent(
                "TRANSACTION",
                UUID.randomUUID(),
                "TRANSFER_COMPLETED",
                "{}"
        );

        event.claim(workerId, Instant.now().plusSeconds(180));
        return event;
    }
}