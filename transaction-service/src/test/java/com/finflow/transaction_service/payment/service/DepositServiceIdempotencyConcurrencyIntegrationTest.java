package com.finflow.transaction_service.payment.service;

import com.finflow.transaction_service.AbstractIntegrationTest;
import com.finflow.transaction_service.domain.account.Account;
import com.finflow.transaction_service.domain.account.AccountNumber;
import com.finflow.transaction_service.domain.account.Owner;
import com.finflow.transaction_service.payment.domain.Deposit;
import com.finflow.transaction_service.payment.domain.DepositRepository;
import com.finflow.transaction_service.payment.stripe.CheckoutSession;
import com.finflow.transaction_service.payment.stripe.CreateCheckoutSession;
import com.finflow.transaction_service.payment.stripe.StripeCheckoutGateway;
import com.finflow.transaction_service.repository.AccountRepository;
import com.finflow.transaction_service.repository.OwnerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * The same Idempotency-Key sent in parallel must behave like one request
 * followed by replays: one deposit row, and every caller gets the same
 * deposit back. The provider mock answers like Stripe does for a repeated
 * idempotency key (same session), so the losers of the insert race replay
 * the winner instead of failing on the unique constraint.
 */
@SpringBootTest
@ActiveProfiles("test")
class DepositServiceIdempotencyConcurrencyIntegrationTest
        extends AbstractIntegrationTest {

    private static final int PARALLEL_REQUESTS = 4;

    @Autowired
    private DepositService depositService;

    @Autowired
    private DepositRepository depositRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private OwnerRepository ownerRepository;

    @MockitoBean
    private StripeCheckoutGateway checkoutGateway;

    @Test
    void sameIdempotencyKeyInParallelProducesExactlyOneDeposit() throws Exception {
        UUID ownerId = ownerRepository.save(new Owner()).getId();
        UUID accountId = accountRepository
                .saveAndFlush(new Account(uniqueAccountNumber(), "USD", ownerId))
                .getId();

        when(checkoutGateway.createCheckoutSession(any(CreateCheckoutSession.class)))
                .thenAnswer(invocation -> {
                    CreateCheckoutSession command = invocation.getArgument(0);
                    String sessionId = "cs_test_" + Integer.toHexString(
                            command.idempotencyKey().hashCode());
                    return new CheckoutSession(sessionId, "https://checkout.test/" + sessionId, null);
                });

        String key = "deposit-race-" + UUID.randomUUID();
        CreateDepositCommand command =
                new CreateDepositCommand(accountId, new BigDecimal("30.00"), "USD", key);

        long depositsBefore = depositRepository.count();

        ExecutorService executor = Executors.newFixedThreadPool(PARALLEL_REQUESTS);
        CountDownLatch ready = new CountDownLatch(PARALLEL_REQUESTS);
        CountDownLatch start = new CountDownLatch(1);

        List<Future<Deposit>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < PARALLEL_REQUESTS; i++) {
                Callable<Deposit> task = () -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to start");
                    }
                    return depositService.createDeposit(command, ownerId);
                };
                futures.add(executor.submit(task));
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).as("all tasks ready").isTrue();

            start.countDown();

            List<UUID> returnedIds = new ArrayList<>();
            for (Future<Deposit> future : futures) {
                // Every caller must get a deposit back: no exception.
                returnedIds.add(future.get(30, TimeUnit.SECONDS).getId());
            }

            assertThat(returnedIds)
                    .hasSize(PARALLEL_REQUESTS)
                    .containsOnly(returnedIds.get(0));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(depositRepository.count()).isEqualTo(depositsBefore + 1);

        Deposit persisted = depositRepository.findByIdempotencyKey(key).orElseThrow();

        assertThat(persisted.getAmount()).isEqualByComparingTo("30.00");
        assertThat(persisted.getStripeSessionId()).isEqualTo(
                "cs_test_" + Integer.toHexString(
                        DepositService.providerIdempotencyKey(ownerId, key).hashCode()));
    }

    private static AccountNumber uniqueAccountNumber() {
        String suffix = UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 8)
                .toUpperCase();

        return new AccountNumber("FF-" + suffix);
    }
}
