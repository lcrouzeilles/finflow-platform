package com.finflow.transaction_service.payment.webhook;

import com.finflow.transaction_service.AbstractIntegrationTest;
import com.finflow.transaction_service.config.TestSecurityConfig;
import com.finflow.transaction_service.domain.account.Account;
import com.finflow.transaction_service.domain.account.AccountNumber;
import com.finflow.transaction_service.domain.account.Owner;
import com.finflow.transaction_service.domain.outbox.OutboxEvent;
import com.finflow.transaction_service.domain.transaction.Transaction;
import com.finflow.transaction_service.domain.transaction.TransactionStatus;
import com.finflow.transaction_service.domain.transaction.TransactionType;
import com.finflow.transaction_service.payment.domain.Deposit;
import com.finflow.transaction_service.payment.domain.DepositRepository;
import com.finflow.transaction_service.payment.domain.DepositStatus;
import com.finflow.transaction_service.payment.event.DepositEventProducer;
import com.finflow.transaction_service.payment.reconciliation.DiscrepancyType;
import com.finflow.transaction_service.payment.reconciliation.ReconciliationDiscrepancy;
import com.finflow.transaction_service.payment.reconciliation.ReconciliationDiscrepancyRepository;
import com.finflow.transaction_service.payment.service.CreateDepositCommand;
import com.finflow.transaction_service.payment.service.DepositService;
import com.finflow.transaction_service.payment.stripe.CheckoutSession;
import com.finflow.transaction_service.payment.stripe.CreateCheckoutSession;
import com.finflow.transaction_service.payment.stripe.StripeCheckoutGateway;
import com.finflow.transaction_service.repository.AccountRepository;
import com.finflow.transaction_service.repository.OutboxEventRepository;
import com.finflow.transaction_service.repository.OwnerRepository;
import com.finflow.transaction_service.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static com.finflow.transaction_service.payment.webhook.StripeWebhookTestSupport.checkoutSessionObject;
import static com.finflow.transaction_service.payment.webhook.StripeWebhookTestSupport.envelope;
import static com.finflow.transaction_service.payment.webhook.StripeWebhookTestSupport.eventId;
import static com.finflow.transaction_service.payment.webhook.StripeWebhookTestSupport.paidSessionObject;
import static com.finflow.transaction_service.payment.webhook.StripeWebhookTestSupport.sign;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real database and cache; Stripe replaced by a mock only to create the
 * Checkout Session. Webhook events are built by the test exactly as Stripe
 * sends them and applied through {@link StripeWebhookService}, plus one
 * end-to-end delivery through the HTTP endpoint with a valid signature.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
class StripeWebhookIntegrationTest extends AbstractIntegrationTest {

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

    @Autowired
    private ReconciliationDiscrepancyRepository discrepancyRepository;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StripeCheckoutGateway checkoutGateway;

    private UUID ownerId;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        ownerId = ownerRepository.save(new Owner()).getId();
        accountId = accountRepository
                .saveAndFlush(new Account(uniqueAccountNumber(), "USD", ownerId))
                .getId();

        when(checkoutGateway.createCheckoutSession(any(CreateCheckoutSession.class)))
                .thenAnswer(invocation -> {
                    CreateCheckoutSession command = invocation.getArgument(0);
                    String sessionId = "cs_test_" + command.depositId().toString().replace("-", "");
                    return new CheckoutSession(sessionId, "https://checkout.test/" + sessionId, null);
                });
    }

    @Test
    void paidSessionCreditsAccountOnceAndWritesLedgerOutboxAndDedupRows() {
        Deposit deposit = pendingDeposit("20.00");
        long transactionsBefore = transactionRepository.count();
        long outboxBefore = outboxEventRepository.count();

        String id = eventId();
        WebhookOutcome outcome = webhookService.process(new StripeWebhookEvent(
                id, "checkout.session.completed",
                paidSessionObject(deposit.getStripeSessionId(), 2000L, "usd", deposit.getId().toString())));

        assertThat(outcome).isEqualTo(WebhookOutcome.PROCESSED);

        Account account = accountRepository.findById(accountId).orElseThrow();
        assertThat(account.getBalance()).isEqualByComparingTo("20.00");

        Deposit completed = depositRepository.findById(deposit.getId()).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(DepositStatus.COMPLETED);
        assertThat(completed.getTransactionId()).isNotNull();
        assertThat(completed.getStripePaymentIntentId()).isEqualTo("pi_" + deposit.getStripeSessionId());

        Transaction ledgerEntry = transactionRepository.findById(completed.getTransactionId()).orElseThrow();
        assertThat(ledgerEntry.getType()).isEqualTo(TransactionType.DEPOSIT);
        assertThat(ledgerEntry.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(ledgerEntry.getSourceAccountId()).isNull();
        assertThat(ledgerEntry.getDestinationAccountId()).isEqualTo(accountId);
        assertThat(ledgerEntry.getAmount()).isEqualByComparingTo("20.00");
        assertThat(ledgerEntry.getIdempotencyKey()).isEqualTo("deposit:" + deposit.getId());
        assertThat(transactionRepository.count()).isEqualTo(transactionsBefore + 1);

        assertThat(outboxEventRepository.count()).isEqualTo(outboxBefore + 1);
        OutboxEvent outboxEvent = outboxEventRepository.findAll().stream()
                .filter(event -> deposit.getId().equals(event.getAggregateId()))
                .findFirst()
                .orElseThrow();
        assertThat(outboxEvent.getEventType()).isEqualTo(DepositEventProducer.EVENT_TYPE);
        assertThat(outboxEvent.getPayload())
                .contains("\"eventId\":\"" + outboxEvent.getId() + "\"")
                .contains("\"accountId\":\"" + accountId + "\"");

        assertThat(processedWebhookEventRepository.existsById(id)).isTrue();
    }

    @Test
    void redeliveryOfTheSameEventIsIgnored() {
        Deposit deposit = pendingDeposit("15.00");
        String id = eventId();
        String payload = paidSessionObject(deposit.getStripeSessionId(), 1500L, "usd", deposit.getId().toString());

        assertThat(webhookService.process(new StripeWebhookEvent(id, "checkout.session.completed", payload)))
                .isEqualTo(WebhookOutcome.PROCESSED);
        assertThat(webhookService.process(new StripeWebhookEvent(id, "checkout.session.completed", payload)))
                .isEqualTo(WebhookOutcome.DUPLICATE);

        assertThat(accountRepository.findById(accountId).orElseThrow().getBalance())
                .isEqualByComparingTo("15.00");
    }

    @Test
    void secondCompletedEventForACompletedDepositDoesNotCreditTwice() {
        Deposit deposit = pendingDeposit("15.00");
        String payload = paidSessionObject(deposit.getStripeSessionId(), 1500L, "usd", deposit.getId().toString());
        long transactionsBefore = transactionRepository.count();

        webhookService.process(new StripeWebhookEvent(eventId(), "checkout.session.completed", payload));
        webhookService.process(new StripeWebhookEvent(eventId(), "checkout.session.completed", payload));

        assertThat(accountRepository.findById(accountId).orElseThrow().getBalance())
                .isEqualByComparingTo("15.00");
        assertThat(transactionRepository.count()).isEqualTo(transactionsBefore + 1);
    }

    @Test
    void expiredSessionMarksDepositExpiredWithoutCredit() {
        Deposit deposit = pendingDeposit("15.00");

        WebhookOutcome outcome = webhookService.process(new StripeWebhookEvent(
                eventId(), "checkout.session.expired",
                checkoutSessionObject(deposit.getStripeSessionId(), "expired", "unpaid",
                        1500L, "usd", deposit.getId().toString(), null)));

        assertThat(outcome).isEqualTo(WebhookOutcome.PROCESSED);
        assertThat(depositRepository.findById(deposit.getId()).orElseThrow().getStatus())
                .isEqualTo(DepositStatus.EXPIRED);
        assertThat(accountRepository.findById(accountId).orElseThrow().getBalance())
                .isEqualByComparingTo("0.00");
    }

    @Test
    void completedAfterExpiredDoesNotCredit() {
        Deposit deposit = pendingDeposit("15.00");

        webhookService.process(new StripeWebhookEvent(eventId(), "checkout.session.expired",
                checkoutSessionObject(deposit.getStripeSessionId(), "expired", "unpaid",
                        1500L, "usd", deposit.getId().toString(), null)));
        webhookService.process(new StripeWebhookEvent(eventId(), "checkout.session.completed",
                paidSessionObject(deposit.getStripeSessionId(), 1500L, "usd", deposit.getId().toString())));

        assertThat(depositRepository.findById(deposit.getId()).orElseThrow().getStatus())
                .isEqualTo(DepositStatus.EXPIRED);
        assertThat(accountRepository.findById(accountId).orElseThrow().getBalance())
                .isEqualByComparingTo("0.00");
    }

    @Test
    void unknownEventTypeIsAcknowledgedAndRemembered() {
        String id = eventId();

        WebhookOutcome outcome = webhookService.process(new StripeWebhookEvent(
                id, "payment_intent.created", "{\"id\": \"pi_whatever\"}"));

        assertThat(outcome).isEqualTo(WebhookOutcome.IGNORED);
        assertThat(processedWebhookEventRepository.existsById(id)).isTrue();
    }

    @Test
    void unpaidCompletedSessionDoesNotCredit() {
        Deposit deposit = pendingDeposit("15.00");

        WebhookOutcome outcome = webhookService.process(new StripeWebhookEvent(
                eventId(), "checkout.session.completed",
                checkoutSessionObject(deposit.getStripeSessionId(), "complete", "unpaid",
                        1500L, "usd", deposit.getId().toString(), null)));

        assertThat(outcome).isEqualTo(WebhookOutcome.IGNORED);
        assertThat(depositRepository.findById(deposit.getId()).orElseThrow().getStatus())
                .isEqualTo(DepositStatus.PENDING);
        assertThat(accountRepository.findById(accountId).orElseThrow().getBalance())
                .isEqualByComparingTo("0.00");
    }

    @Test
    void unknownSessionIsRecordedAsDiscrepancy() {
        String sessionId = "cs_test_unknown_" + UUID.randomUUID().toString().replace("-", "");

        WebhookOutcome outcome = webhookService.process(new StripeWebhookEvent(
                eventId(), "checkout.session.completed",
                paidSessionObject(sessionId, 1500L, "usd", null)));

        assertThat(outcome).isEqualTo(WebhookOutcome.PROCESSED);

        List<ReconciliationDiscrepancy> discrepancies =
                discrepancyRepository.findAllByStripeSessionId(sessionId);
        assertThat(discrepancies).hasSize(1);
        assertThat(discrepancies.get(0).getType()).isEqualTo(DiscrepancyType.UNKNOWN_SESSION);
    }

    @Test
    void amountMismatchIsRecordedAndNotCredited() {
        Deposit deposit = pendingDeposit("15.00");
        long transactionsBefore = transactionRepository.count();

        webhookService.process(new StripeWebhookEvent(eventId(), "checkout.session.completed",
                paidSessionObject(deposit.getStripeSessionId(), 9900L, "usd", deposit.getId().toString())));

        assertThat(depositRepository.findById(deposit.getId()).orElseThrow().getStatus())
                .isEqualTo(DepositStatus.PENDING);
        assertThat(accountRepository.findById(accountId).orElseThrow().getBalance())
                .isEqualByComparingTo("0.00");
        assertThat(transactionRepository.count()).isEqualTo(transactionsBefore);

        List<ReconciliationDiscrepancy> discrepancies =
                discrepancyRepository.findAllByStripeSessionId(deposit.getStripeSessionId());
        assertThat(discrepancies).hasSize(1);
        assertThat(discrepancies.get(0).getType()).isEqualTo(DiscrepancyType.AMOUNT_MISMATCH);
        assertThat(discrepancies.get(0).getDepositId()).isEqualTo(deposit.getId());
    }

    @Test
    void creditEvictsTheCachedAccount() {
        Deposit deposit = pendingDeposit("15.00");

        Cache accounts = cacheManager.getCache("accounts");
        assertThat(accounts).isNotNull();
        accounts.put(accountId, "stale");

        webhookService.process(new StripeWebhookEvent(eventId(), "checkout.session.completed",
                paidSessionObject(deposit.getStripeSessionId(), 1500L, "usd", deposit.getId().toString())));

        assertThat(accounts.get(accountId)).isNull();
    }

    @Test
    void signedDeliveryThroughTheEndpointCreditsTheAccount() throws Exception {
        Deposit deposit = pendingDeposit("42.00");
        String payload = envelope(eventId(), "checkout.session.completed",
                paidSessionObject(deposit.getStripeSessionId(), 4200L, "usd", deposit.getId().toString()));

        mockMvc.perform(post("/webhooks/stripe")
                        .contentType(APPLICATION_JSON)
                        .header("Stripe-Signature", sign(payload))
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(true))
                .andExpect(jsonPath("$.outcome").value("PROCESSED"));

        assertThat(accountRepository.findById(accountId).orElseThrow().getBalance())
                .isEqualByComparingTo("42.00");
        assertThat(depositRepository.findById(deposit.getId()).orElseThrow().getStatus())
                .isEqualTo(DepositStatus.COMPLETED);
    }

    @Test
    void invalidSignatureThroughTheEndpointHasNoEffect() throws Exception {
        Deposit deposit = pendingDeposit("42.00");
        String payload = envelope(eventId(), "checkout.session.completed",
                paidSessionObject(deposit.getStripeSessionId(), 4200L, "usd", deposit.getId().toString()));

        mockMvc.perform(post("/webhooks/stripe")
                        .contentType(APPLICATION_JSON)
                        .header("Stripe-Signature", sign(payload, "whsec_wrong"))
                        .content(payload))
                .andExpect(status().isBadRequest());

        assertThat(accountRepository.findById(accountId).orElseThrow().getBalance())
                .isEqualByComparingTo("0.00");
        assertThat(depositRepository.findById(deposit.getId()).orElseThrow().getStatus())
                .isEqualTo(DepositStatus.PENDING);
    }

    private Deposit pendingDeposit(String amount) {
        return depositService.createDeposit(
                new CreateDepositCommand(accountId, new BigDecimal(amount), "USD",
                        "webhook-it-" + UUID.randomUUID()),
                ownerId
        );
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
