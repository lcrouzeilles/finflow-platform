package com.finflow.transaction_service.payment.service;

import com.finflow.transaction_service.domain.account.Account;
import com.finflow.transaction_service.domain.account.AccountNumber;
import com.finflow.transaction_service.domain.account.AccountStatus;
import com.finflow.transaction_service.exception.IdempotencyKeyConflictException;
import com.finflow.transaction_service.payment.domain.Deposit;
import com.finflow.transaction_service.payment.domain.DepositRepository;
import com.finflow.transaction_service.payment.domain.DepositStatus;
import com.finflow.transaction_service.payment.stripe.CheckoutSession;
import com.finflow.transaction_service.payment.stripe.CreateCheckoutSession;
import com.finflow.transaction_service.payment.stripe.PaymentProviderException;
import com.finflow.transaction_service.payment.stripe.StripeCheckoutGateway;
import com.finflow.transaction_service.service.AccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DepositServiceTest {

    private static final String KEY = "deposit-key-001";

    @Mock
    private DepositRepository depositRepository;

    @Mock
    private AccountService accountService;

    @Mock
    private StripeCheckoutGateway checkoutGateway;

    private DepositService depositService;

    private UUID ownerId;
    private UUID accountId;
    private Account account;

    @BeforeEach
    void setUp() {
        ownerId = UUID.randomUUID();
        accountId = UUID.randomUUID();
        account = Account.forTest(
                accountId,
                new AccountNumber("FF-DEP00001"),
                "USD",
                ownerId
        );

        depositService = new DepositService(
                depositRepository,
                accountService,
                checkoutGateway,
                new PaymentsProperties(Set.of("USD"), new BigDecimal("0.50")),
                new NoOpTransactionManager()
        );
    }

    @Test
    void shouldRejectNullOwner() {
        assertThatThrownBy(() -> depositService.createDeposit(command("10.00"), null))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(depositRepository, accountService, checkoutGateway);
    }

    @Test
    void shouldRejectBlankIdempotencyKey() {
        CreateDepositCommand command =
                new CreateDepositCommand(accountId, new BigDecimal("10.00"), "USD", " ");

        assertThatThrownBy(() -> depositService.createDeposit(command, ownerId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Idempotency-Key");

        verifyNoInteractions(depositRepository, accountService, checkoutGateway);
    }

    @Test
    void shouldRejectUnsupportedCurrency() {
        CreateDepositCommand command =
                new CreateDepositCommand(accountId, new BigDecimal("10.00"), "ARS", KEY);

        assertThatThrownBy(() -> depositService.createDeposit(command, ownerId))
                .isInstanceOf(InvalidDepositException.class)
                .hasMessageContaining("ARS");

        verifyNoInteractions(depositRepository, accountService, checkoutGateway);
    }

    @Test
    void shouldRejectMoreThanTwoDecimals() {
        assertThatThrownBy(() -> depositService.createDeposit(command("10.123"), ownerId))
                .isInstanceOf(InvalidDepositException.class)
                .hasMessageContaining("2 decimal");

        verifyNoInteractions(depositRepository, accountService, checkoutGateway);
    }

    @Test
    void shouldRejectAmountBelowMinimum() {
        assertThatThrownBy(() -> depositService.createDeposit(command("0.49"), ownerId))
                .isInstanceOf(InvalidDepositException.class)
                .hasMessageContaining("at least 0.50");

        verifyNoInteractions(depositRepository, accountService, checkoutGateway);
    }

    @Test
    void shouldRejectCurrencyMismatchWithAccount() {
        Account arsAccount = Account.forTest(
                accountId, new AccountNumber("FF-DEP00002"), "ARS", ownerId
        );

        when(depositRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
        when(accountService.getOwnedAccount(accountId, ownerId)).thenReturn(arsAccount);

        assertThatThrownBy(() -> depositService.createDeposit(command("10.00"), ownerId))
                .isInstanceOf(InvalidDepositException.class)
                .hasMessageContaining("account currency");

        verifyNoInteractions(checkoutGateway);
        verify(depositRepository, never()).saveAndFlush(any());
    }

    @Test
    void shouldRejectInactiveAccount() {
        ReflectionTestUtils.setField(account, "status", AccountStatus.BLOCKED);

        when(depositRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
        when(accountService.getOwnedAccount(accountId, ownerId)).thenReturn(account);

        assertThatThrownBy(() -> depositService.createDeposit(command("10.00"), ownerId))
                .isInstanceOf(InvalidDepositException.class)
                .hasMessageContaining("active");

        verifyNoInteractions(checkoutGateway);
    }

    @Test
    void shouldCreateSessionAtStripeBeforePersistingPendingDeposit() {
        when(depositRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
        when(accountService.getOwnedAccount(accountId, ownerId)).thenReturn(account);
        when(checkoutGateway.createCheckoutSession(any(CreateCheckoutSession.class)))
                .thenReturn(new CheckoutSession("cs_test_1", "https://checkout.test/1", "pi_1"));
        when(depositRepository.saveAndFlush(any(Deposit.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Deposit deposit = depositService.createDeposit(command("10.50"), ownerId);

        assertThat(deposit.getId()).isEqualTo(DepositService.depositId(ownerId, KEY));
        assertThat(deposit.getStatus()).isEqualTo(DepositStatus.PENDING);
        assertThat(deposit.getAccountId()).isEqualTo(accountId);
        assertThat(deposit.getOwnerId()).isEqualTo(ownerId);
        assertThat(deposit.getAmount()).isEqualByComparingTo("10.50");
        assertThat(deposit.getCurrency()).isEqualTo("USD");
        assertThat(deposit.getStripeSessionId()).isEqualTo("cs_test_1");
        assertThat(deposit.getCheckoutUrl()).isEqualTo("https://checkout.test/1");
        assertThat(deposit.getStripePaymentIntentId()).isEqualTo("pi_1");
        assertThat(deposit.getIdempotencyKey()).isEqualTo(KEY);

        ArgumentCaptor<CreateCheckoutSession> captor =
                ArgumentCaptor.forClass(CreateCheckoutSession.class);
        verify(checkoutGateway).createCheckoutSession(captor.capture());

        CreateCheckoutSession sent = captor.getValue();
        assertThat(sent.amountMinor()).isEqualTo(1050L);
        assertThat(sent.currency()).isEqualTo("USD");
        assertThat(sent.depositId()).isEqualTo(deposit.getId());
        assertThat(sent.idempotencyKey()).isEqualTo("deposit:" + ownerId + ":" + KEY);

        InOrder inOrder = inOrder(checkoutGateway, depositRepository);
        inOrder.verify(checkoutGateway).createCheckoutSession(any());
        inOrder.verify(depositRepository).saveAndFlush(any());
    }

    @Test
    void shouldDeriveDeterministicIdsPerOwnerAndKey() {
        UUID otherOwner = UUID.randomUUID();

        assertThat(DepositService.depositId(ownerId, KEY))
                .isEqualTo(DepositService.depositId(ownerId, KEY))
                .isNotEqualTo(DepositService.depositId(otherOwner, KEY))
                .isNotEqualTo(DepositService.depositId(ownerId, KEY + "x"));

        assertThat(DepositService.providerIdempotencyKey(ownerId, KEY))
                .isNotEqualTo(DepositService.providerIdempotencyKey(otherOwner, KEY));
    }

    @Test
    void shouldReplayExistingDepositForItsOwnerWithoutCallingStripe() {
        Deposit existing = existingDeposit(ownerId, "10.00");

        when(depositRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.of(existing));

        Deposit replayed = depositService.createDeposit(command("10.00"), ownerId);

        assertThat(replayed).isSameAs(existing);
        verifyNoInteractions(checkoutGateway, accountService);
        verify(depositRepository, never()).saveAndFlush(any());
    }

    @Test
    void shouldRejectReplayByAnotherOwner() {
        Deposit existing = existingDeposit(UUID.randomUUID(), "10.00");

        when(depositRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> depositService.createDeposit(command("10.00"), ownerId))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(checkoutGateway, accountService);
    }

    @Test
    void shouldCheckOwnershipBeforeComparingPayload() {
        Deposit existing = existingDeposit(UUID.randomUUID(), "10.00");

        when(depositRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.of(existing));

        // Different amount AND different owner: ownership wins (403, not 409).
        assertThatThrownBy(() -> depositService.createDeposit(command("99.00"), ownerId))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void shouldRejectReplayWithDifferentData() {
        Deposit existing = existingDeposit(ownerId, "10.00");

        when(depositRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> depositService.createDeposit(command("99.00"), ownerId))
                .isInstanceOf(IdempotencyKeyConflictException.class);

        verifyNoInteractions(checkoutGateway);
    }

    @Test
    void shouldNotPersistAnythingWhenProviderFails() {
        when(depositRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
        when(accountService.getOwnedAccount(accountId, ownerId)).thenReturn(account);
        when(checkoutGateway.createCheckoutSession(any()))
                .thenThrow(new PaymentProviderException("down", true, null, null));

        assertThatThrownBy(() -> depositService.createDeposit(command("10.00"), ownerId))
                .isInstanceOf(PaymentProviderException.class);

        verify(depositRepository, never()).saveAndFlush(any());
    }

    @Test
    void shouldRecoverFromIdempotencyRaceByReplayingTheWinner() {
        Deposit winner = existingDeposit(ownerId, "10.00");

        when(depositRepository.findByIdempotencyKey(KEY))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(accountService.getOwnedAccount(accountId, ownerId)).thenReturn(account);
        when(checkoutGateway.createCheckoutSession(any()))
                .thenReturn(new CheckoutSession("cs_test_1", "https://checkout.test/1", null));
        when(depositRepository.saveAndFlush(any(Deposit.class)))
                .thenThrow(new DataIntegrityViolationException("uk_deposits_idempotency_key"));

        Deposit result = depositService.createDeposit(command("10.00"), ownerId);

        assertThat(result).isSameAs(winner);
    }

    @Test
    void shouldReturnOwnedDeposit() {
        Deposit existing = existingDeposit(ownerId, "10.00");

        when(depositRepository.findByIdAndOwnerId(existing.getId(), ownerId))
                .thenReturn(Optional.of(existing));

        assertThat(depositService.getOwnedDeposit(existing.getId(), ownerId))
                .isSameAs(existing);
    }

    @Test
    void shouldHideDepositsOfOtherOwners() {
        UUID depositId = UUID.randomUUID();

        when(depositRepository.findByIdAndOwnerId(depositId, ownerId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> depositService.getOwnedDeposit(depositId, ownerId))
                .isInstanceOf(DepositNotFoundException.class);
    }

    private CreateDepositCommand command(String amount) {
        return new CreateDepositCommand(accountId, new BigDecimal(amount), "USD", KEY);
    }

    private Deposit existingDeposit(UUID owner, String amount) {
        return new Deposit(
                DepositService.depositId(owner, KEY),
                accountId,
                owner,
                new BigDecimal(amount),
                "USD",
                KEY,
                "cs_test_existing",
                "https://checkout.test/existing",
                null
        );
    }

    private static class NoOpTransactionManager implements PlatformTransactionManager {

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            // no-op
        }

        @Override
        public void rollback(TransactionStatus status) {
            // no-op
        }
    }
}
