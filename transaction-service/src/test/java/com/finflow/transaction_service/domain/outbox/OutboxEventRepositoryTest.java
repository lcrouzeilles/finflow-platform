package com.finflow.transaction_service.domain.outbox;

import com.finflow.transaction_service.repository.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(
        replace = AutoConfigureTestDatabase.Replace.NONE
)
class OutboxEventRepositoryTest {

    @Autowired
    private OutboxEventRepository repository;

    @Test
    void shouldPersistAndRetrieveOutboxEvent() {
        OutboxEvent event = new OutboxEvent(
                "TRANSACTION",
                UUID.randomUUID(),
                "TRANSFER_COMPLETED",
                """
                {"sourceAccountId":"source","destinationAccountId":"destination"}
                """
        );

        OutboxEvent saved = repository.saveAndFlush(event);

        OutboxEvent retrieved = repository.findById(saved.getId())
                .orElseThrow();

        assertThat(retrieved.getId()).isEqualTo(saved.getId());
        assertThat(retrieved.getAggregateType()).isEqualTo("TRANSACTION");
        assertThat(retrieved.getAggregateId())
                .isEqualTo(event.getAggregateId());
        assertThat(retrieved.getEventType())
                .isEqualTo("TRANSFER_COMPLETED");
        assertThat(retrieved.getPayload()).contains("sourceAccountId");
        assertThat(retrieved.getCreatedAt()).isNotNull();
        assertThat(retrieved.getPublishedAt()).isNull();
        assertThat(retrieved.getAttempts()).isZero();
    }

    @Test
    void shouldPersistClaimAndPublicationStatus() {
        OutboxEvent event = new OutboxEvent(
                "TRANSACTION",
                UUID.randomUUID(),
                "TRANSFER_COMPLETED",
                "{}"
        );

        event.claim("repository-test-worker", Instant.now().plusSeconds(60));

        OutboxEvent saved = repository.saveAndFlush(event);

        OutboxEvent retrieved = repository.findById(saved.getId())
                .orElseThrow();

        assertThat(retrieved.getClaimedBy())
                .isEqualTo("repository-test-worker");
        assertThat(retrieved.getClaimUntil()).isNotNull();

        retrieved.markPublished();
        repository.saveAndFlush(retrieved);

        OutboxEvent published = repository.findById(saved.getId())
                .orElseThrow();

        assertThat(published.getPublishedAt()).isNotNull();
        assertThat(published.getClaimedBy()).isNull();
        assertThat(published.getClaimUntil()).isNull();
    }
}