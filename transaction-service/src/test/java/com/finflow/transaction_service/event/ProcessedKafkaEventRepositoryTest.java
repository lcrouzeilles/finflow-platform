package com.finflow.transaction_service.event;

import com.finflow.transaction_service.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(
        replace = AutoConfigureTestDatabase.Replace.NONE
)
class ProcessedKafkaEventRepositoryTest extends AbstractIntegrationTest {

    private static final String CONSUMER = "test-consumer";

    @Autowired
    private ProcessedKafkaEventRepository repository;

    @Test
    void shouldClaimAnEventOncePerConsumer() {
        UUID eventId = UUID.randomUUID();
        Instant now = Instant.now();

        assertThat(repository.insertIfAbsent(CONSUMER, eventId, now)).isEqualTo(1);
        assertThat(repository.insertIfAbsent(CONSUMER, eventId, now)).isZero();
        assertThat(repository.insertIfAbsent("other-consumer", eventId, now)).isEqualTo(1);
    }

    @Test
    void shouldDeleteOnlyClaimsOlderThanTheCutoff() {
        Instant cutoff = Instant.now().truncatedTo(ChronoUnit.MICROS);
        UUID old = UUID.randomUUID();
        UUID recent = UUID.randomUUID();

        repository.insertIfAbsent(CONSUMER, old, cutoff.minus(1, ChronoUnit.DAYS));
        repository.insertIfAbsent(CONSUMER, recent, cutoff);

        repository.deleteProcessedBefore(cutoff);

        assertThat(repository.existsById(new ProcessedKafkaEvent.Key(CONSUMER, old))).isFalse();
        assertThat(repository.existsById(new ProcessedKafkaEvent.Key(CONSUMER, recent))).isTrue();
    }
}
