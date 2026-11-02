package com.finflow.transaction_service.payment.service;

import com.finflow.transaction_service.AbstractIntegrationTest;
import com.finflow.transaction_service.domain.account.Account;
import com.finflow.transaction_service.domain.account.AccountNumber;
import com.finflow.transaction_service.domain.account.Owner;
import com.finflow.transaction_service.exception.AccountNotFoundException;
import com.finflow.transaction_service.exception.IdempotencyKeyConflictException;
import com.finflow.transaction_service.payment.domain.Deposit;
import com.finflow.transaction_service.payment.domain.DepositRepository;
import com.finflow.transaction_service.payment.domain.DepositStatus;
import com.finflow.transaction_service.payment.stripe.CheckoutSession;
import com.finflow.transaction_service.payment.stripe.CreateCheckoutSession;
import com.finflow.transaction_service.payment.stripe.PaymentProviderException;
import com.finflow.transaction_service.payment.stripe.StripeCheckoutGateway;
import com.finflow.transaction_service.repository.AccountRepository;
import com.finflow.transaction_service.repository.OwnerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Real database, Stripe replaced by a mock that answers deterministically
 * per idempotency key (like Stripe does for a repeated key).
 */
@SpringBootTest
@ActiveProfiles("test")
class DepositServiceIntegrationTest extends AbstractIntegrationTest {

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

    private UUID ownerId;
    private UUID otherOwnerId;
    private UUID usdAccountId;
    private UUID arsAccountId;

    @BeforeEach
    void setUp() {
        ownerId = ownerRepository.save(new Owner()).getId();
        otherOwnerId = ownerRepository.save(new Owner()).getId();

        usdAccountId = accountRepository
                .saveAndFlush(new Account(uniqueAccountNumber(), "USD", ownerId))
                .getId();
        arsAccountId = accountRepository
                .saveAndFlush(new Account(uniqueAccountNumber(), "ARS", ownerId))
                .getId();

        when(checkoutGateway.createCheckoutSession(any(CreateCheckoutSession.class)))
                .thenAnswer(invocation -> {
                    CreateCheckoutSession command = invocation.getArgument(0);
                    String sessionId = "cs_test_" + Integer.toHexString(
                            command.idempotencyKey().hashCode());
                    return new CheckoutSession(
                            sessionId,
                            "https://checkout.stripe.com/c/pay/" + sessionId,
                            null
                    );
                });
    }

    @Test
    void shouldPersistPendingDepositWithItsCheckoutSession() {
        String key = uniqueKey();

        Deposit deposit = depositService.createDeposit(command(usdAccountId, "20.00", key), ownerId);

        Deposit persisted = depositRepository.findById(deposit.getId()).orElseThrow();

        assertThat(persisted.getStatus()).isEqualTo(DepositStatus.PENDING);
        assertThat(persisted.getOwnerId()).isEqualTo(ownerId);
        assertThat(persisted.getAccountId()).isEqualTo(usdAccountId);
        assertThat(persisted.getAmount()).isEqualByComparingTo("20.00");
        assertThat(persisted.getCurrency()).isEqualTo("USD");
        assertThat(persisted.getStripeSessionId()).startsWith("cs_test_");
        assertThat(persisted.getCheckoutUrl()).contains(persisted.getStripeSessionId());
        assertThat(persisted.getIdempotencyKey()).isEqualTo(key);
        assertThat(persisted.getTransactionId()).isNull();

        // The balance is untouched: only the webhook credits.
        assertThat(accountRepository.findById(usdAccountId).orElseThrow().getBalance())
                .isEqualByComparingTo("0.00");
    }

    @Test
    void shouldHideAccountsOfOtherOwners() {
        long before = depositRepository.count();

        assertThatThrownBy(() -> depositService.createDeposit(
                command(usdAccountId, "20.00", uniqueKey()), otherOwnerId))
                .isInstanceOf(AccountNotFoundException.class);

        assertThat(depositRepository.count()).isEqualTo(before);
        verifyNoInteractions(checkoutGateway);
    }

    @Test
    void shouldReplaySameKeyWithoutCreatingASecondSession() {
        String key = uniqueKey();

        Deposit first = depositService.createDeposit(command(usdAccountId, "20.00", key), ownerId);
        Deposit replay = depositService.createDeposit(command(usdAccountId, "20.00", key), ownerId);

        assertThat(replay.getId()).isEqualTo(first.getId());
        assertThat(replay.getCheckoutUrl()).isEqualTo(first.getCheckoutUrl());
        assertThat(depositRepository.findByIdempotencyKey(key)).isPresent();
        verify(checkoutGateway, times(1)).createCheckoutSession(any());
    }

    @Test
    void shouldRejectKeyReuseWithDifferentData() {
        String key = uniqueKey();

        depositService.createDeposit(command(usdAccountId, "20.00", key), ownerId);

        assertThatThrownBy(() -> depositService.createDeposit(
                command(usdAccountId, "21.00", key), ownerId))
                .isInstanceOf(IdempotencyKeyConflictException.class);
    }

    @Test
    void shouldRejectReplayOfAnotherOwnersKey() {
        String key = uniqueKey();

        depositService.createDeposit(command(usdAccountId, "20.00", key), ownerId);

        assertThatThrownBy(() -> depositService.createDeposit(
                command(usdAccountId, "20.00", key), otherOwnerId))
                .isInstanceOf(AccessDeniedException.class);

        verify(checkoutGateway, times(1)).createCheckoutSession(any());
    }

    @Test
    void shouldRejectCurrencyMismatchWithAccount() {
        long before = depositRepository.count();

        assertThatThrownBy(() -> depositService.createDeposit(
                command(arsAccountId, "20.00", uniqueKey()), ownerId))
                .isInstanceOf(InvalidDepositException.class);

        assertThat(depositRepository.count()).isEqualTo(before);
        verifyNoInteractions(checkoutGateway);
    }

    @Test
    void shouldLeaveNoRowWhenProviderFails() {
        when(checkoutGateway.createCheckoutSession(any()))
                .thenThrow(new PaymentProviderException("Stripe down", true, 503, null));

        String key = uniqueKey();
        long before = depositRepository.count();

        assertThatThrownBy(() -> depositService.createDeposit(
                command(usdAccountId, "20.00", key), ownerId))
                .isInstanceOf(PaymentProviderException.class);

        assertThat(depositRepository.count()).isEqualTo(before);
        assertThat(depositRepository.findByIdempotencyKey(key)).isEmpty();
    }

    @Test
    void shouldReadOwnDepositAndHideOthers() {
        Deposit deposit = depositService.createDeposit(
                command(usdAccountId, "20.00", uniqueKey()), ownerId);

        assertThat(depositService.getOwnedDeposit(deposit.getId(), ownerId).getId())
                .isEqualTo(deposit.getId());

        assertThatThrownBy(() -> depositService.getOwnedDeposit(deposit.getId(), otherOwnerId))
                .isInstanceOf(DepositNotFoundException.class);
    }

    private CreateDepositCommand command(UUID accountId, String amount, String key) {
        return new CreateDepositCommand(accountId, new BigDecimal(amount), "USD", key);
    }

    private static String uniqueKey() {
        return "deposit-it-" + UUID.randomUUID();
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
