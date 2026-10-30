package com.finflow.transaction_service.service;

import com.finflow.transaction_service.domain.account.Account;
import com.finflow.transaction_service.domain.account.AccountNumber;
import com.finflow.transaction_service.domain.transaction.TransferRequest;
import com.finflow.transaction_service.exception.InsufficientFundsException;
import com.finflow.transaction_service.repository.AccountRepository;
import com.finflow.transaction_service.repository.OutboxEventRepository;
import com.finflow.transaction_service.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class TransferServiceConcurrencyIntegrationTest {

    @Autowired
    private TransferService transferService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Test
    void concurrentTransfersMustNotOverspendSourceAccount()
            throws Exception {

        UUID ownerId = UUID.randomUUID();

        Account source = new Account(
                uniqueAccountNumber(),
                "ARS",
                ownerId
        );

        Account destinationOne = new Account(
                uniqueAccountNumber(),
                "ARS",
                ownerId
        );

        Account destinationTwo = new Account(
                uniqueAccountNumber(),
                "ARS",
                ownerId
        );

        source.deposit(new BigDecimal("100.00"));

        source = accountRepository.saveAndFlush(source);
        destinationOne = accountRepository.saveAndFlush(destinationOne);
        destinationTwo = accountRepository.saveAndFlush(destinationTwo);

        UUID sourceId = source.getId();
        UUID destinationOneId = destinationOne.getId();
        UUID destinationTwoId = destinationTwo.getId();

        long transactionsBefore = transactionRepository.count();
        long outboxBefore = outboxEventRepository.count();

        TransferRequest firstRequest = new TransferRequest(
                sourceId,
                destinationOneId,
                new BigDecimal("70.00"),
                "concurrency-test-" + UUID.randomUUID()
        );

        TransferRequest secondRequest = new TransferRequest(
                sourceId,
                destinationTwoId,
                new BigDecimal("70.00"),
                "concurrency-test-" + UUID.randomUUID()
        );

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            Future<TransferOutcome> first = executor.submit(() ->
                    executeTransfer(firstRequest, ready, start));

            Future<TransferOutcome> second = executor.submit(() ->
                    executeTransfer(secondRequest, ready, start));

            assertTrue(
                    ready.await(10, TimeUnit.SECONDS),
                    "Both transfer tasks should become ready"
            );

            start.countDown();

            TransferOutcome firstOutcome =
                    first.get(20, TimeUnit.SECONDS);
            TransferOutcome secondOutcome =
                    second.get(20, TimeUnit.SECONDS);

            List<TransferOutcome> outcomes =
                    List.of(firstOutcome, secondOutcome);

            assertEquals(
                    1,
                    outcomes.stream()
                            .filter(TransferOutcome::succeeded)
                            .count(),
                    "Exactly one transfer should succeed"
            );

            assertEquals(
                    1,
                    outcomes.stream()
                            .filter(TransferOutcome::insufficientFunds)
                            .count(),
                    "The other transfer should fail for insufficient funds"
            );
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(
                    10, TimeUnit.SECONDS
            ));
        }

        Account finalSource =
                accountRepository.findById(sourceId).orElseThrow();

        Account finalDestinationOne =
                accountRepository.findById(destinationOneId).orElseThrow();

        Account finalDestinationTwo =
                accountRepository.findById(destinationTwoId).orElseThrow();

        assertEquals(
                0,
                finalSource.getBalance()
                        .compareTo(new BigDecimal("30.00")),
                "Source balance must be 30.00"
        );

        BigDecimal destinationTotal =
                finalDestinationOne.getBalance()
                        .add(finalDestinationTwo.getBalance());

        assertEquals(
                0,
                destinationTotal.compareTo(new BigDecimal("70.00")),
                "Exactly 70.00 should be credited across both destinations"
        );

        assertTrue(
                finalDestinationOne.getBalance()
                        .compareTo(BigDecimal.ZERO) == 0
                        || finalDestinationOne.getBalance()
                        .compareTo(new BigDecimal("70.00")) == 0
        );

        assertTrue(
                finalDestinationTwo.getBalance()
                        .compareTo(BigDecimal.ZERO) == 0
                        || finalDestinationTwo.getBalance()
                        .compareTo(new BigDecimal("70.00")) == 0
        );

        assertEquals(
                transactionsBefore + 1,
                transactionRepository.count(),
                "Only one transaction should be persisted"
        );

        assertEquals(
                outboxBefore + 1,
                outboxEventRepository.count(),
                "Only one outbox event should be persisted"
        );
    }

    private TransferOutcome executeTransfer(
            TransferRequest request,
            CountDownLatch ready,
            CountDownLatch start
    ) {
        ready.countDown();

        try {
            if (!start.await(10, TimeUnit.SECONDS)) {
                return new TransferOutcome(
                        false,
                        false,
                        new IllegalStateException(
                                "Timed out waiting to start"
                        )
                );
            }

            transferService.transfer(request);
            return new TransferOutcome(true, false, null);

        } catch (InsufficientFundsException exception) {
            return new TransferOutcome(false, true, exception);

        } catch (Exception exception) {
            return new TransferOutcome(false, false, exception);
        }
    }

    private record TransferOutcome(
            boolean succeeded,
            boolean insufficientFunds,
            Exception exception
    ) {
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
