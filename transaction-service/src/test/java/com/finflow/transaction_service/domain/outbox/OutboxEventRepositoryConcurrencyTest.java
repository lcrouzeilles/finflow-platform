
package com.finflow.transaction_service.domain.outbox;

import com.finflow.transaction_service.repository.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(
        replace = AutoConfigureTestDatabase.Replace.NONE
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OutboxEventRepositoryConcurrencyTest {

    @Autowired
    private OutboxEventRepository repository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void concurrentWorkersShouldClaimDifferentEvents() throws Exception {
        TransactionTemplate transaction =
                new TransactionTemplate(transactionManager);

        // Use only a dedicated test database: this clears existing outbox rows.
        List<UUID> eventIds = transaction.execute(status -> {
            repository.deleteAllInBatch();
            repository.flush();

            OutboxEvent first = new OutboxEvent(
                    "TRANSACTION",
                    UUID.randomUUID(),
                    "TRANSFER_COMPLETED",
                    "{\"event\":\"first\"}"
            );

            OutboxEvent second = new OutboxEvent(
                    "TRANSACTION",
                    UUID.randomUUID(),
                    "TRANSFER_COMPLETED",
                    "{\"event\":\"second\"}"
            );

            repository.saveAllAndFlush(List.of(first, second));

            return List.of(first.getId(), second.getId());
        });

        assertThat(eventIds).hasSize(2);

        CountDownLatch firstTransactionLocked = new CountDownLatch(1);
        CountDownLatch releaseFirstTransaction = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<UUID> firstWorker = executor.submit(() ->
                    transaction.execute(status -> {
                        List<OutboxEvent> claimed =
                                repository.findClaimableForUpdate(
                                        Instant.now(), 1
                                );

                        assertThat(claimed).hasSize(1);
                        firstTransactionLocked.countDown();

                        try {
                            if (!releaseFirstTransaction.await(
                                    5, TimeUnit.SECONDS
                            )) {
                                throw new IllegalStateException(
                                        "Timed out waiting to release first transaction"
                                );
                            }
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(exception);
                        }

                        return claimed.getFirst().getId();
                    })
            );

            assertThat(firstTransactionLocked.await(
                    5, TimeUnit.SECONDS
            )).isTrue();

            Future<UUID> secondWorker = executor.submit(() ->
                    transaction.execute(status -> {
                        List<OutboxEvent> claimed =
                                repository.findClaimableForUpdate(
                                        Instant.now(), 1
                                );

                        assertThat(claimed).hasSize(1);
                        return claimed.getFirst().getId();
                    })
            );

            UUID secondClaimedId =
                    secondWorker.get(5, TimeUnit.SECONDS);

            releaseFirstTransaction.countDown();

            UUID firstClaimedId =
                    firstWorker.get(5, TimeUnit.SECONDS);

            assertThat(firstClaimedId)
                    .isNotEqualTo(secondClaimedId);

            assertThat(eventIds)
                    .contains(firstClaimedId, secondClaimedId);

        } finally {
            releaseFirstTransaction.countDown();
            executor.shutdownNow();

            // Remove fixtures after both worker transactions finish.
            executor.awaitTermination(5, TimeUnit.SECONDS);

            transaction.executeWithoutResult(status ->
                    repository.deleteAllInBatch()
            );
        }
    }
}
