package com.finflow.transaction_service.service;

import com.finflow.transaction_service.AbstractIntegrationTest;
import com.finflow.transaction_service.domain.account.Account;
import com.finflow.transaction_service.domain.account.AccountNumber;
import com.finflow.transaction_service.domain.account.Owner;
import com.finflow.transaction_service.domain.transaction.Transaction;
import com.finflow.transaction_service.domain.transaction.TransferRequest;
import com.finflow.transaction_service.repository.AccountRepository;
import com.finflow.transaction_service.repository.OutboxEventRepository;
import com.finflow.transaction_service.repository.OwnerRepository;
import com.finflow.transaction_service.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two requests carrying the same Idempotency-Key at the same time must
 * behave like a single request followed by its replay: one debit, one
 * transaction, one outbox event, and the same transaction returned to both
 * callers. Before the fix the loser of the race died on the unique
 * constraint with a 500.
 */
@SpringBootTest
@ActiveProfiles("test")
class TransferServiceIdempotencyConcurrencyIntegrationTest
        extends AbstractIntegrationTest {

    private static final int PARALLEL_REQUESTS = 4;

    @Autowired
    private TransferService transferService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private OwnerRepository ownerRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Test
    void sameIdempotencyKeyInParallelProducesExactlyOneTransfer()
            throws Exception {

        UUID ownerId = ownerRepository.save(new Owner()).getId();

        Account source = new Account(uniqueAccountNumber(), "ARS", ownerId);
        Account destination = new Account(uniqueAccountNumber(), "ARS", ownerId);

        source.deposit(new BigDecimal("500.00"));

        UUID sourceId = accountRepository.saveAndFlush(source).getId();
        UUID destinationId = accountRepository.saveAndFlush(destination).getId();

        long transactionsBefore = transactionRepository.count();
        long outboxBefore = outboxEventRepository.count();

        TransferRequest request = new TransferRequest(
                sourceId,
                destinationId,
                new BigDecimal("100.00"),
                "idempotency-race-" + UUID.randomUUID()
        );

        ExecutorService executor =
                Executors.newFixedThreadPool(PARALLEL_REQUESTS);
        CountDownLatch ready = new CountDownLatch(PARALLEL_REQUESTS);
        CountDownLatch start = new CountDownLatch(1);

        List<Future<Transaction>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < PARALLEL_REQUESTS; i++) {
                Callable<Transaction> task = () -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException(
                                "Timed out waiting to start"
                        );
                    }
                    return transferService.transfer(request, ownerId);
                };
                futures.add(executor.submit(task));
            }

            assertThat(ready.await(10, TimeUnit.SECONDS))
                    .as("all tasks ready")
                    .isTrue();

            start.countDown();

            List<UUID> returnedIds = new ArrayList<>();
            for (Future<Transaction> future : futures) {
                // Every caller must get a transaction back: no exception.
                returnedIds.add(future.get(30, TimeUnit.SECONDS).getId());
            }

            assertThat(returnedIds)
                    .hasSize(PARALLEL_REQUESTS)
                    .containsOnly(returnedIds.get(0));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS))
                    .isTrue();
        }

        Account finalSource =
                accountRepository.findById(sourceId).orElseThrow();
        Account finalDestination =
                accountRepository.findById(destinationId).orElseThrow();

        assertThat(finalSource.getBalance())
                .isEqualByComparingTo("400.00");
        assertThat(finalDestination.getBalance())
                .isEqualByComparingTo("100.00");

        assertThat(transactionRepository.count())
                .isEqualTo(transactionsBefore + 1);
        assertThat(outboxEventRepository.count())
                .isEqualTo(outboxBefore + 1);

        Transaction persisted = transactionRepository
                .findByIdempotencyKey(request.idempotencyKey())
                .orElseThrow();

        assertThat(persisted.getAmount()).isEqualByComparingTo("100.00");
    }

    private AccountNumber uniqueAccountNumber() {
        String suffix = UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 8)
                .toUpperCase();

        return new AccountNumber("FF-" + suffix);
    }
}
