package com.finflow.transaction_service.payment.webhook;

import com.finflow.transaction_service.AbstractIntegrationTest;
import com.finflow.transaction_service.domain.account.Account;
import com.finflow.transaction_service.domain.account.AccountNumber;
import com.finflow.transaction_service.domain.account.Owner;
import com.finflow.transaction_service.payment.domain.Deposit;
import com.finflow.transaction_service.payment.domain.DepositRepository;
import com.finflow.transaction_service.payment.domain.DepositStatus;
import com.finflow.transaction_service.payment.service.CreateDepositCommand;
import com.finflow.transaction_service.payment.service.DepositService;
import com.finflow.transaction_service.payment.stripe.CheckoutSession;
import com.finflow.transaction_service.payment.stripe.CreateCheckoutSession;
import com.finflow.transaction_service.payment.stripe.StripeCheckoutGateway;
import com.finflow.transaction_service.repository.AccountRepository;
import com.finflow.transaction_service.repository.OutboxEventRepository;
import com.finflow.transaction_service.repository.OwnerRepository;
import com.finflow.transaction_service.repository.TransactionRepository;
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

import static com.finflow.transaction_service.payment.webhook.StripeWebhookTestSupport.eventId;
import static com.finflow.transaction_service.payment.webhook.StripeWebhookTestSupport.paidSessionObject;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Stripe may deliver the same event several times at once (retries racing
 * with a slow first delivery). The handler claims the event id with
 * {@code INSERT ... ON CONFLICT DO NOTHING} as the first statement of its
 * transaction: Postgres makes every concurrent duplicate wait on the
 * in-flight row until the first transaction commits, after which they see
 * the conflict and insert nothing. Exactly one delivery therefore applies
 * the credit, and none of them fails.
 */
@SpringBootTest
@ActiveProfiles("test")
class StripeWebhookDedupConcurrencyIntegrationTest extends AbstractIntegrationTest {

    private static final int PARALLEL_DELIVERIES = 4;

    @Autowired
    private StripeWebhookService webhookService;

    @Autowired
    private DepositService depositService;

    @Autowired
    private DepositRepository depositRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private OwnerRepository ownerRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private ProcessedWebhookEventRepository processedWebhookEventRepository;

    @MockitoBean
    private StripeCheckoutGateway checkoutGateway;

    @Test
    void sameEventDeliveredInParallelCreditsExactlyOnce() throws Exception {
        UUID ownerId = ownerRepository.save(new Owner()).getId();
        UUID accountId = accountRepository
                .saveAndFlush(new Account(uniqueAccountNumber(), "USD", ownerId))
                .getId();

        when(checkoutGateway.createCheckoutSession(any(CreateCheckoutSession.class)))
                .thenAnswer(invocation -> {
                    CreateCheckoutSession command = invocation.getArgument(0);
                    String sessionId = "cs_test_" + command.depositId().toString().replace("-", "");
                    return new CheckoutSession(sessionId, "https://checkout.test/" + sessionId, null);
                });

        Deposit deposit = depositService.createDeposit(
                new CreateDepositCommand(accountId, new BigDecimal("50.00"), "USD",
                        "webhook-race-" + UUID.randomUUID()),
                ownerId
        );

        String id = eventId();
        StripeWebhookEvent event = new StripeWebhookEvent(
                id, "checkout.session.completed",
                paidSessionObject(deposit.getStripeSessionId(), 5000L, "usd", deposit.getId().toString()));

        long transactionsBefore = transactionRepository.count();
        long outboxBefore = outboxEventRepository.count();
        long processedBefore = processedWebhookEventRepository.count();

        ExecutorService executor = Executors.newFixedThreadPool(PARALLEL_DELIVERIES);
        CountDownLatch ready = new CountDownLatch(PARALLEL_DELIVERIES);
        CountDownLatch start = new CountDownLatch(1);

        List<Future<WebhookOutcome>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < PARALLEL_DELIVERIES; i++) {
                Callable<WebhookOutcome> task = () -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to start");
                    }
                    return webhookService.process(event);
                };
                futures.add(executor.submit(task));
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).as("all tasks ready").isTrue();

            start.countDown();

            List<WebhookOutcome> outcomes = new ArrayList<>();
            for (Future<WebhookOutcome> future : futures) {
                // Every delivery must be acknowledged: no exception.
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }

            assertThat(outcomes).containsOnlyOnce(WebhookOutcome.PROCESSED);
            assertThat(outcomes.stream().filter(WebhookOutcome.DUPLICATE::equals).count())
                    .isEqualTo(PARALLEL_DELIVERIES - 1);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(accountRepository.findById(accountId).orElseThrow().getBalance())
                .isEqualByComparingTo("50.00");
        assertThat(depositRepository.findById(deposit.getId()).orElseThrow().getStatus())
                .isEqualTo(DepositStatus.COMPLETED);
        assertThat(transactionRepository.count()).isEqualTo(transactionsBefore + 1);
        assertThat(outboxEventRepository.count()).isEqualTo(outboxBefore + 1);
        assertThat(processedWebhookEventRepository.count()).isEqualTo(processedBefore + 1);
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
