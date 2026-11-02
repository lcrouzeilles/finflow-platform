package com.finflow.transaction_service.payment.reconciliation;

import com.finflow.transaction_service.AbstractIntegrationTest;
import com.finflow.transaction_service.domain.account.Account;
import com.finflow.transaction_service.domain.account.AccountNumber;
import com.finflow.transaction_service.domain.account.Owner;
import com.finflow.transaction_service.payment.domain.Deposit;
import com.finflow.transaction_service.payment.domain.DepositRepository;
import com.finflow.transaction_service.payment.domain.DepositStatus;
import com.finflow.transaction_service.payment.service.CreateDepositCommand;
import com.finflow.transaction_service.payment.service.DepositCompletionService;
import com.finflow.transaction_service.payment.service.DepositService;
import com.finflow.transaction_service.payment.stripe.CheckoutSession;
import com.finflow.transaction_service.payment.stripe.CreateCheckoutSession;
import com.finflow.transaction_service.payment.stripe.ProviderCheckoutSession;
import com.finflow.transaction_service.payment.stripe.StripeCheckoutGateway;
import com.finflow.transaction_service.repository.AccountRepository;
import com.finflow.transaction_service.repository.OwnerRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Real database; the provider is a mock whose listing is scripted per
 * test. Because the database is shared with the other integration tests
 * (which leave their own deposits behind), assertions are made per session
 * id, never on global totals.
 */
@SpringBootTest
@ActiveProfiles("test")
class ReconciliationServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ReconciliationService reconciliationService;

    @Autowired
    private DepositService depositService;

    @Autowired
    private DepositCompletionService depositCompletionService;

    @Autowired
    private DepositRepository depositRepository;

    @Autowired
    private ReconciliationDiscrepancyRepository discrepancyRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private OwnerRepository ownerRepository;

    @Autowired
    private MeterRegistry meterRegistry;

    @MockitoBean
    private StripeCheckoutGateway checkoutGateway;

    private UUID ownerId;
    private UUID accountId;
    private ReconciliationWindow window;

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

        Instant now = Instant.now();
        window = new ReconciliationWindow(now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(1)));
    }

    @Test
    void consistentDepositProducesNoDiscrepancy() {
        Deposit deposit = completedDeposit("20.00");

        providerLists(paid(deposit, 2000L, "usd"));

        reconciliationService.reconcile(window);

        assertThat(discrepancyRepository.findAllByStripeSessionId(deposit.getStripeSessionId())).isEmpty();
    }

    @Test
    void paidSessionWithPendingDepositIsReportedAsLostWebhook() {
        Deposit deposit = pendingDeposit("20.00");

        providerLists(paid(deposit, 2000L, "usd"));

        ReconciliationReport report = reconciliationService.reconcile(window);

        List<ReconciliationDiscrepancy> rows =
                discrepancyRepository.findAllByStripeSessionId(deposit.getStripeSessionId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getType()).isEqualTo(DiscrepancyType.PAID_WITHOUT_LOCAL_COMPLETION);
        assertThat(rows.get(0).getDepositId()).isEqualTo(deposit.getId());
        assertThat(rows.get(0).getDetail()).contains("PENDING");
        assertThat(report.found()).containsEntry(DiscrepancyType.PAID_WITHOUT_LOCAL_COMPLETION, 1);

        // Report only: the deposit is untouched.
        assertThat(depositRepository.findById(deposit.getId()).orElseThrow().getStatus())
                .isEqualTo(DepositStatus.PENDING);
        assertThat(accountRepository.findById(accountId).orElseThrow().getBalance())
                .isEqualByComparingTo("0.00");
    }

    @Test
    void paidSessionUnknownLocallyIsReported() {
        String sessionId = "cs_test_foreign_" + UUID.randomUUID().toString().replace("-", "");

        providerLists(new ProviderCheckoutSession(sessionId, "pi_f", "complete", "paid", 1200L, "usd", null));

        reconciliationService.reconcile(window);

        List<ReconciliationDiscrepancy> rows = discrepancyRepository.findAllByStripeSessionId(sessionId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getType()).isEqualTo(DiscrepancyType.PAID_WITHOUT_LOCAL_COMPLETION);
        assertThat(rows.get(0).getDepositId()).isNull();
    }

    @Test
    void completedDepositWithoutProviderPaymentIsReported() {
        Deposit deposit = completedDeposit("20.00");

        // Not in the listing, and the direct lookup says unpaid.
        providerLists();
        when(checkoutGateway.findCheckoutSession(eq(deposit.getStripeSessionId())))
                .thenReturn(Optional.of(new ProviderCheckoutSession(
                        deposit.getStripeSessionId(), null, "open", "unpaid", 2000L, "usd",
                        deposit.getId().toString())));

        reconciliationService.reconcile(window);

        List<ReconciliationDiscrepancy> rows =
                discrepancyRepository.findAllByStripeSessionId(deposit.getStripeSessionId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getType()).isEqualTo(DiscrepancyType.COMPLETED_WITHOUT_PROVIDER_PAYMENT);
        assertThat(rows.get(0).getDetail()).contains("unpaid");
    }

    @Test
    void completedDepositMissingAtProviderIsReported() {
        Deposit deposit = completedDeposit("20.00");

        providerLists();
        when(checkoutGateway.findCheckoutSession(eq(deposit.getStripeSessionId())))
                .thenReturn(Optional.empty());

        reconciliationService.reconcile(window);

        List<ReconciliationDiscrepancy> rows =
                discrepancyRepository.findAllByStripeSessionId(deposit.getStripeSessionId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getType()).isEqualTo(DiscrepancyType.COMPLETED_WITHOUT_PROVIDER_PAYMENT);
        assertThat(rows.get(0).getDetail()).contains("no such session");
    }

    @Test
    void amountMismatchIsReported() {
        Deposit deposit = completedDeposit("20.00");

        providerLists(paid(deposit, 2500L, "usd"));

        reconciliationService.reconcile(window);

        List<ReconciliationDiscrepancy> rows =
                discrepancyRepository.findAllByStripeSessionId(deposit.getStripeSessionId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getType()).isEqualTo(DiscrepancyType.AMOUNT_MISMATCH);
        assertThat(rows.get(0).getDetail()).contains("20.00").contains("2500");
    }

    @Test
    void secondRunDoesNotDuplicateDiscrepanciesButCountsThem() {
        Deposit deposit = pendingDeposit("20.00");

        providerLists(paid(deposit, 2000L, "usd"));

        double counterBefore = counter(DiscrepancyType.PAID_WITHOUT_LOCAL_COMPLETION);

        ReconciliationReport first = reconciliationService.reconcile(window);
        ReconciliationReport second = reconciliationService.reconcile(window);

        assertThat(discrepancyRepository.findAllByStripeSessionId(deposit.getStripeSessionId())).hasSize(1);
        assertThat(first.found()).containsEntry(DiscrepancyType.PAID_WITHOUT_LOCAL_COMPLETION, 1);
        assertThat(second.found()).containsEntry(DiscrepancyType.PAID_WITHOUT_LOCAL_COMPLETION, 1);
        assertThat(counter(DiscrepancyType.PAID_WITHOUT_LOCAL_COMPLETION))
                .as("metric counts new discrepancies only")
                .isEqualTo(counterBefore + 1);
    }

    private double counter(DiscrepancyType type) {
        return meterRegistry.counter(ReconciliationService.METRIC_DISCREPANCIES, "type", type.name()).count();
    }

    private void providerLists(ProviderCheckoutSession... sessions) {
        when(checkoutGateway.listCheckoutSessions(any(), any())).thenReturn(List.of(sessions));
    }

    private static ProviderCheckoutSession paid(Deposit deposit, long amountTotal, String currency) {
        return new ProviderCheckoutSession(
                deposit.getStripeSessionId(), "pi_" + deposit.getStripeSessionId(),
                "complete", "paid", amountTotal, currency, deposit.getId().toString());
    }

    private Deposit pendingDeposit(String amount) {
        return depositService.createDeposit(
                new CreateDepositCommand(accountId, new BigDecimal(amount), "USD",
                        "reconcile-it-" + UUID.randomUUID()),
                ownerId
        );
    }

    private Deposit completedDeposit(String amount) {
        Deposit deposit = pendingDeposit(amount);

        DepositCompletionService.Result result = depositCompletionService.complete(
                deposit.getStripeSessionId(),
                new BigDecimal(amount).movePointRight(2).longValueExact(),
                "usd",
                "pi_" + deposit.getStripeSessionId());

        assertThat(result).isEqualTo(DepositCompletionService.Result.CREDITED);

        return depositRepository.findById(deposit.getId()).orElseThrow();
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
