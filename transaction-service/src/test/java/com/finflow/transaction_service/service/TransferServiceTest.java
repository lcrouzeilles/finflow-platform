package com.finflow.transaction_service.service;

import tools.jackson.databind.ObjectMapper;
import com.finflow.transaction_service.domain.account.Account;
import com.finflow.transaction_service.domain.account.AccountNumber;
import com.finflow.transaction_service.domain.account.AccountNumberGenerator;
import com.finflow.transaction_service.domain.outbox.OutboxEvent;
import com.finflow.transaction_service.domain.transaction.Transaction;
import com.finflow.transaction_service.domain.transaction.TransferRequest;
import com.finflow.transaction_service.event.TransferCompletedEvent;
import com.finflow.transaction_service.exception.AccountNotFoundException;
import com.finflow.transaction_service.exception.InsufficientFundsException;
import com.finflow.transaction_service.exception.InvalidTransferException;
import com.finflow.transaction_service.repository.AccountRepository;
import com.finflow.transaction_service.repository.OutboxEventRepository;
import com.finflow.transaction_service.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    private TransferService transferService;

    private UUID sourceAccountId;
    private UUID destinationAccountId;
    private UUID ownerId;

    @BeforeEach
    void setUp() {
        transferService = new TransferService(
                accountRepository,
                transactionRepository,
                eventPublisher,
                outboxEventRepository,
                new ObjectMapper(),
                new NoOpTransactionManager()
        );

        sourceAccountId = UUID.randomUUID();
        destinationAccountId = UUID.randomUUID();
        ownerId = UUID.randomUUID();

    }

    @Test
    void shouldRejectNullTransferRequest() {
        assertThatThrownBy(() ->
                transferService.transfer(null, ownerId)
        )
                .isInstanceOf(InvalidTransferException.class)
                .hasMessage("Transfer request cannot be null");

        verifyNoInteractions(accountRepository);
        verifyNoInteractions(transactionRepository);
    }

    @Test
    void shouldRejectNullSourceAccountId() {
        TransferRequest request = new TransferRequest(
                null,
                destinationAccountId,
                new BigDecimal("100.00"),
                "transfer-test-001"
        );

        assertThatThrownBy(() ->
                transferService.transfer(request, ownerId)
        )
                .isInstanceOf(InvalidTransferException.class)
                .hasMessage("Source account ID cannot be null");

        verifyNoInteractions(accountRepository);
        verifyNoInteractions(transactionRepository);
    }

    @Test
    void shouldRejectNullDestinationAccountId() {
        TransferRequest request = new TransferRequest(
                sourceAccountId,
                null,
                new BigDecimal("100.00"),
                "transfer-test-001"
        );

        assertThatThrownBy(() ->
                transferService.transfer(request, ownerId)
        )
                .isInstanceOf(InvalidTransferException.class)
                .hasMessage("Destination account ID cannot be null");

        verifyNoInteractions(accountRepository);
        verifyNoInteractions(transactionRepository);
    }

    @Test
    void shouldRejectSameSourceAndDestinationAccountId() {
        TransferRequest request = new TransferRequest(
                sourceAccountId,
                sourceAccountId,
                new BigDecimal("100.00"),
                "transfer-test-001"
        );

        assertThatThrownBy(() ->
                transferService.transfer(request, ownerId)
        )
                .isInstanceOf(InvalidTransferException.class)
                .hasMessage(
                        "Source and destination accounts must be different"
                );

        verifyNoInteractions(accountRepository);
        verifyNoInteractions(transactionRepository);
    }

    @Test
    void shouldLoadSourceAccountUsingSourceAccountId() {
        Account sourceAccount = createAccount(
                "FF-SOURCE01",
                "ARS"
        );

        Account destinationAccount = createAccount(
                "FF-DESTIN01",
                "ARS"
        );

        sourceAccount.deposit(new BigDecimal("100.00"));

        when(accountRepository.findByIdForUpdate(sourceAccountId))
                .thenReturn(Optional.of(sourceAccount));

        when(accountRepository.findByIdForUpdate(destinationAccountId))
                .thenReturn(Optional.of(destinationAccount));

        TransferRequest request = new TransferRequest(
                sourceAccountId,
                destinationAccountId,
                new BigDecimal("100.00"),
                "transfer-test-001"
        );

        stubTransactionSave();
        transferService.transfer(request, ownerId);

        verify(accountRepository).findByIdForUpdate(sourceAccountId);
    }

    @Test
    void shouldLoadDestinationAccountUsingDestinationAccountId() {
        Account sourceAccount = createAccount(
                "FF-SOURCE02",
                "ARS"
        );

        Account destinationAccount = createAccount(
                "FF-DESTIN02",
                "ARS"
        );
        sourceAccount.deposit(new BigDecimal("100.00"));
        when(accountRepository.findByIdForUpdate(sourceAccountId))
                .thenReturn(Optional.of(sourceAccount));

        when(accountRepository.findByIdForUpdate(destinationAccountId))
                .thenReturn(Optional.of(destinationAccount));

        TransferRequest request = new TransferRequest(
                sourceAccountId,
                destinationAccountId,
                new BigDecimal("100.00"),
                "transfer-test-001"
        );

        stubTransactionSave();
        transferService.transfer(request, ownerId);

        verify(accountRepository).findByIdForUpdate(destinationAccountId);
    }

    @Test
    void shouldRejectWhenSourceAccountDoesNotExist() {
        sourceAccountId = UUID.fromString(
                "00000000-0000-0000-0000-000000000001"
        );
        destinationAccountId = UUID.fromString(
                "00000000-0000-0000-0000-000000000002"
        );

        when(accountRepository.findByIdForUpdate(sourceAccountId))
                .thenReturn(Optional.empty());

        TransferRequest request = new TransferRequest(
                sourceAccountId,
                destinationAccountId,
                new BigDecimal("100.00"),
                "transfer-test-001"
        );

        assertThatThrownBy(() -> transferService.transfer(request, ownerId))
                .isInstanceOf(AccountNotFoundException.class)
                .hasMessage("Account not found with id: " + sourceAccountId);

        verify(accountRepository).findByIdForUpdate(sourceAccountId);
    }

    @Test
    void shouldRejectWhenDestinationAccountDoesNotExist() {
        sourceAccountId = UUID.fromString(
                "00000000-0000-0000-0000-000000000001"
        );
        destinationAccountId = UUID.fromString(
                "00000000-0000-0000-0000-000000000002"
        );

        Account sourceAccount = createAccount("FF-SOURCE03", "ARS");

        when(accountRepository.findByIdForUpdate(sourceAccountId))
                .thenReturn(Optional.of(sourceAccount));

        when(accountRepository.findByIdForUpdate(destinationAccountId))
                .thenReturn(Optional.empty());

        TransferRequest request = new TransferRequest(
                sourceAccountId,
                destinationAccountId,
                new BigDecimal("100.00"),
                "transfer-test-001"
        );

        assertThatThrownBy(() -> transferService.transfer(request, ownerId))
                .isInstanceOf(AccountNotFoundException.class)
                .hasMessage(
                        "Account not found with id: " + destinationAccountId
                );

        verify(accountRepository).findByIdForUpdate(sourceAccountId);
        verify(accountRepository).findByIdForUpdate(destinationAccountId);
    }

    @Test
    void shouldRejectTransferWhenCurrenciesDoNotMatch() {
        Account sourceAccount = createAccount(
                "FF-SOURCE04",
                "ARS"
        );

        Account destinationAccount = createAccount(
                "FF-DESTIN04",
                "USD"
        );

        sourceAccount.deposit(new BigDecimal("100.00"));

        when(accountRepository.findByIdForUpdate(sourceAccountId))
                .thenReturn(Optional.of(sourceAccount));

        when(accountRepository.findByIdForUpdate(destinationAccountId))
                .thenReturn(Optional.of(destinationAccount));

        TransferRequest request = new TransferRequest(
                sourceAccountId,
                destinationAccountId,
                new BigDecimal("100.00"),
                "transfer-test-001"
        );

        assertThatThrownBy(() ->
                transferService.transfer(request, ownerId)
        )
                .isInstanceOf(InvalidTransferException.class)
                .hasMessage(
                        "Source and destination currencies must match"
                );

        verify(accountRepository).findByIdForUpdate(sourceAccountId);
        verify(accountRepository).findByIdForUpdate(destinationAccountId);
    }

    @Test
    void shouldTransferMoneyBetweenAccounts() {
        Account sourceAccount = createAccount(
                "FF-SOURCE05",
                "ARS"
        );

        Account destinationAccount = createAccount(
                "FF-DESTIN05",
                "ARS"
        );

        sourceAccount.deposit(new BigDecimal("500.00"));

        when(accountRepository.findByIdForUpdate(sourceAccountId))
                .thenReturn(Optional.of(sourceAccount));

        when(accountRepository.findByIdForUpdate(destinationAccountId))
                .thenReturn(Optional.of(destinationAccount));

        TransferRequest request = new TransferRequest(
                sourceAccountId,
                destinationAccountId,
                new BigDecimal("100.00"),
                "transfer-test-001"
        );

        stubTransactionSave();
        transferService.transfer(request, ownerId);

        assertThat(
                sourceAccount.getBalance()
        ).isEqualByComparingTo("400.00");

        assertThat(
                destinationAccount.getBalance()
        ).isEqualByComparingTo("100.00");

        verify(accountRepository).findByIdForUpdate(sourceAccountId);
        verify(accountRepository).findByIdForUpdate(destinationAccountId);

        verify(accountRepository).save(sourceAccount);
        verify(accountRepository).save(destinationAccount);
        verify(eventPublisher).publishEvent(
                any(TransferCompletedEvent.class)
        );

        verify(outboxEventRepository).save(
                argThat(event ->
                        "TRANSACTION".equals(event.getAggregateType())
                                && "TRANSFER_COMPLETED".equals(event.getEventType())
                                && event.getAggregateId() != null
                                && event.getPayload().contains(
                                sourceAccountId.toString()
                        )
                                && event.getPayload().contains(
                                destinationAccountId.toString()
                        )
                )
        );
    }

    @Test
    void shouldNotSaveAccountsWhenCurrenciesDoNotMatch() {
        Account sourceAccount = createAccount(
                "FF-SOURCE07",
                "ARS"
        );

        Account destinationAccount = createAccount(
                "FF-DESTIN07",
                "USD"
        );

        sourceAccount.deposit(new BigDecimal("100.00"));

        when(accountRepository.findByIdForUpdate(sourceAccountId))
                .thenReturn(Optional.of(sourceAccount));

        when(accountRepository.findByIdForUpdate(destinationAccountId))
                .thenReturn(Optional.of(destinationAccount));

        TransferRequest request = new TransferRequest(
                sourceAccountId,
                destinationAccountId,
                new BigDecimal("100.00"),
                "transfer-test-001"
        );

        assertThatThrownBy(() ->
                transferService.transfer(request, ownerId)
        )
                .isInstanceOf(InvalidTransferException.class)
                .hasMessage(
                        "Source and destination currencies must match"
                );

        verify(accountRepository, never()).save(sourceAccount);
        verify(accountRepository, never()).save(destinationAccount);
    }

    @Test
    void shouldRejectZeroAmount() {
        TransferRequest request = new TransferRequest(
                sourceAccountId,
                destinationAccountId,
                BigDecimal.ZERO,
                "transfer-test-001"
        );

        assertThatThrownBy(() ->
                transferService.transfer(request, ownerId)
        )
                .isInstanceOf(InvalidTransferException.class)
                .hasMessage("Transfer amount must be greater than zero");

        verifyNoInteractions(accountRepository);
        verifyNoInteractions(transactionRepository);
    }

    @Test
    void shouldRejectNegativeAmount() {
        TransferRequest request = new TransferRequest(
                sourceAccountId,
                destinationAccountId,
                new BigDecimal("-10.00"),
                "transfer-test-001"
        );

        assertThatThrownBy(() ->
                transferService.transfer(request, ownerId)
        )
                .isInstanceOf(InvalidTransferException.class)
                .hasMessage("Transfer amount must be greater than zero");

        verifyNoInteractions(accountRepository);
        verifyNoInteractions(transactionRepository);
    }

    @Test
    void shouldRejectTransferWhenSourceAccountHasInsufficientFunds() {
        AccountNumberGenerator accountNumberGenerator = new AccountNumberGenerator();
        AccountNumber sourceAccountNumber = accountNumberGenerator.generate();
        AccountNumber destinationAccountNumber = accountNumberGenerator.generate();

        Account sourceAccount = createAccount(sourceAccountNumber.getValue(),"ARS");
        Account destinationAccount = createAccount(destinationAccountNumber.getValue(),"ARS");

        sourceAccount.deposit(new BigDecimal("100.00"));
        destinationAccount.deposit(new BigDecimal("50.00"));

        UUID sourceAccountId = UUID.randomUUID();
        UUID destinationAccountId = UUID.randomUUID();

        TransferRequest request = new TransferRequest(
                sourceAccountId,
                destinationAccountId,
                new BigDecimal("150.00"),
                "transfer-test-001"
        );

        when(accountRepository.findByIdForUpdate(sourceAccountId))
                .thenReturn(Optional.of(sourceAccount));

        when(accountRepository.findByIdForUpdate(destinationAccountId))
                .thenReturn(Optional.of(destinationAccount));

        assertThatThrownBy(() ->
                transferService.transfer(request, ownerId)
        )
                .isInstanceOf(InsufficientFundsException.class)
                .hasMessageContaining("Insufficient funds");

        assertThat(sourceAccount.getBalance())
                .isEqualByComparingTo("100.00");

        assertThat(destinationAccount.getBalance())
                .isEqualByComparingTo("50.00");

        verify(accountRepository, never())
                .save(any(Account.class));

        verify(transactionRepository, never())
                .save(any(Transaction.class));
    }

    @Test
    void shouldNotCreateOutboxEventWhenTransferIsRejected() {
        Account sourceAccount = createAccount("FF-SOURCE08", "ARS");
        Account destinationAccount = createAccount("FF-DESTIN08", "ARS");

        sourceAccount.deposit(new BigDecimal("10.00"));

        when(accountRepository.findByIdForUpdate(sourceAccountId))
                .thenReturn(Optional.of(sourceAccount));

        when(accountRepository.findByIdForUpdate(destinationAccountId))
                .thenReturn(Optional.of(destinationAccount));

        TransferRequest request = new TransferRequest(
                sourceAccountId,
                destinationAccountId,
                new BigDecimal("100.00"),
                "transfer-rejected-test"
        );

        assertThatThrownBy(() -> transferService.transfer(request, ownerId))
                .isInstanceOf(InsufficientFundsException.class);

        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
        verify(eventPublisher, never()).publishEvent(
                any(TransferCompletedEvent.class)
        );
    }

    @Test
    void shouldRejectNullRequestingOwner() {
        TransferRequest request = new TransferRequest(
                sourceAccountId,
                destinationAccountId,
                new BigDecimal("100.00"),
                "transfer-test-001"
        );

        assertThatThrownBy(() ->
                transferService.transfer(request, null)
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Authenticated owner ID is required");

        verifyNoInteractions(accountRepository);
        verifyNoInteractions(transactionRepository);
    }

    @Test
    void shouldRejectTransferFromAccountNotOwnedByRequester() {
        Account sourceAccount = createAccount("FF-SOURCE09", "ARS");
        Account destinationAccount = createAccount("FF-DESTIN09", "ARS");

        sourceAccount.deposit(new BigDecimal("500.00"));

        when(accountRepository.findByIdForUpdate(sourceAccountId))
                .thenReturn(Optional.of(sourceAccount));

        when(accountRepository.findByIdForUpdate(destinationAccountId))
                .thenReturn(Optional.of(destinationAccount));

        TransferRequest request = new TransferRequest(
                sourceAccountId,
                destinationAccountId,
                new BigDecimal("100.00"),
                "cross-owner-test-001"
        );

        UUID anotherOwnerId = UUID.randomUUID();

        assertThatThrownBy(() ->
                transferService.transfer(request, anotherOwnerId)
        )
                .isInstanceOf(AccessDeniedException.class);

        assertThat(sourceAccount.getBalance())
                .isEqualByComparingTo("500.00");
        assertThat(destinationAccount.getBalance())
                .isEqualByComparingTo("0.00");

        verify(accountRepository, never()).save(any(Account.class));
        verify(transactionRepository, never()).save(any(Transaction.class));
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
        verify(eventPublisher, never()).publishEvent(
                any(TransferCompletedEvent.class)
        );
    }

    @Test
    void shouldAllowTransferToAccountOwnedByAnotherOwner() {
        Account sourceAccount = createAccount("FF-SOURCE10", "ARS");

        Account destinationAccount = new Account(
                new AccountNumber("FF-DESTIN10"),
                "ARS",
                UUID.randomUUID()
        );

        sourceAccount.deposit(new BigDecimal("500.00"));

        when(accountRepository.findByIdForUpdate(sourceAccountId))
                .thenReturn(Optional.of(sourceAccount));

        when(accountRepository.findByIdForUpdate(destinationAccountId))
                .thenReturn(Optional.of(destinationAccount));

        TransferRequest request = new TransferRequest(
                sourceAccountId,
                destinationAccountId,
                new BigDecimal("100.00"),
                "transfer-to-other-owner-001"
        );

        stubTransactionSave();
        transferService.transfer(request, ownerId);

        assertThat(sourceAccount.getBalance())
                .isEqualByComparingTo("400.00");
        assertThat(destinationAccount.getBalance())
                .isEqualByComparingTo("100.00");
    }

    @Test
    void shouldReturnExistingTransactionOnReplayByItsOwner() {
        Account sourceAccount = createAccount("FF-SOURCE11", "ARS");

        Transaction existing = new Transaction(
                sourceAccountId,
                destinationAccountId,
                new BigDecimal("100.00"),
                "ARS",
                "replay-test-001"
        );
        existing.markAsCompleted();

        when(transactionRepository.findByIdempotencyKey("replay-test-001"))
                .thenReturn(Optional.of(existing));

        when(accountRepository.findById(sourceAccountId))
                .thenReturn(Optional.of(sourceAccount));

        TransferRequest request = new TransferRequest(
                sourceAccountId,
                destinationAccountId,
                new BigDecimal("100.00"),
                "replay-test-001"
        );

        Transaction result = transferService.transfer(request, ownerId);

        assertThat(result).isSameAs(existing);

        verify(accountRepository, never()).findByIdForUpdate(any());
        verify(accountRepository, never()).save(any(Account.class));
        verify(transactionRepository, never()).save(any(Transaction.class));
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
    }

    @Test
    void shouldRejectReplayOfTransactionOwnedByAnotherOwner() {
        Account sourceAccount = createAccount("FF-SOURCE12", "ARS");

        Transaction existing = new Transaction(
                sourceAccountId,
                destinationAccountId,
                new BigDecimal("100.00"),
                "ARS",
                "foreign-replay-001"
        );
        existing.markAsCompleted();

        when(transactionRepository.findByIdempotencyKey("foreign-replay-001"))
                .thenReturn(Optional.of(existing));

        when(accountRepository.findById(sourceAccountId))
                .thenReturn(Optional.of(sourceAccount));

        TransferRequest request = new TransferRequest(
                sourceAccountId,
                destinationAccountId,
                new BigDecimal("100.00"),
                "foreign-replay-001"
        );

        UUID anotherOwnerId = UUID.randomUUID();

        assertThatThrownBy(() ->
                transferService.transfer(request, anotherOwnerId)
        )
                .isInstanceOf(AccessDeniedException.class);

        verify(accountRepository, never()).findByIdForUpdate(any());
        verify(accountRepository, never()).save(any(Account.class));
        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    @Test
    void shouldCheckReplayOwnershipBeforeDetectingIdempotencyConflict() {
        Account sourceAccount = createAccount("FF-SOURCE13", "ARS");

        Transaction existing = new Transaction(
                sourceAccountId,
                destinationAccountId,
                new BigDecimal("100.00"),
                "ARS",
                "foreign-conflict-001"
        );
        existing.markAsCompleted();

        when(transactionRepository.findByIdempotencyKey("foreign-conflict-001"))
                .thenReturn(Optional.of(existing));

        when(accountRepository.findById(sourceAccountId))
                .thenReturn(Optional.of(sourceAccount));

        // Different amount: the owner would get a 409, a stranger gets 403
        // so the key cannot be probed for its original payload.
        TransferRequest request = new TransferRequest(
                sourceAccountId,
                destinationAccountId,
                new BigDecimal("999.00"),
                "foreign-conflict-001"
        );

        assertThatThrownBy(() ->
                transferService.transfer(request, UUID.randomUUID())
        )
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void shouldReturnExistingTransactionFoundAfterAcquiringLocks() {
        Account sourceAccount = createAccount("FF-SOURCE14", "ARS");
        Account destinationAccount = createAccount("FF-DESTIN14", "ARS");

        sourceAccount.deposit(new BigDecimal("500.00"));

        Transaction committedByOtherRequest = new Transaction(
                sourceAccountId,
                destinationAccountId,
                new BigDecimal("100.00"),
                "ARS",
                "race-after-lock-001"
        );
        committedByOtherRequest.markAsCompleted();

        // First lookup: nothing yet. Second lookup (after the row locks):
        // the concurrent request has committed in the meantime.
        when(transactionRepository.findByIdempotencyKey("race-after-lock-001"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(committedByOtherRequest));

        when(accountRepository.findByIdForUpdate(sourceAccountId))
                .thenReturn(Optional.of(sourceAccount));
        when(accountRepository.findByIdForUpdate(destinationAccountId))
                .thenReturn(Optional.of(destinationAccount));
        when(accountRepository.findById(sourceAccountId))
                .thenReturn(Optional.of(sourceAccount));

        TransferRequest request = new TransferRequest(
                sourceAccountId,
                destinationAccountId,
                new BigDecimal("100.00"),
                "race-after-lock-001"
        );

        Transaction result = transferService.transfer(request, ownerId);

        assertThat(result).isSameAs(committedByOtherRequest);
        assertThat(sourceAccount.getBalance())
                .isEqualByComparingTo("500.00");

        verify(accountRepository, never()).save(any(Account.class));
        verify(transactionRepository, never()).save(any(Transaction.class));
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
    }

    @Test
    void shouldReturnWinningTransactionWhenIdempotencyRaceIsLost() {
        Account sourceAccount = createAccount("FF-SOURCE15", "ARS");
        Account destinationAccount = createAccount("FF-DESTIN15", "ARS");

        sourceAccount.deposit(new BigDecimal("500.00"));

        Transaction winner = new Transaction(
                sourceAccountId,
                destinationAccountId,
                new BigDecimal("100.00"),
                "ARS",
                "race-lost-001"
        );
        winner.markAsCompleted();

        // Both lookups inside the unit of work miss; the insert then fails
        // on the unique constraint; the recovery lookup finds the winner.
        when(transactionRepository.findByIdempotencyKey("race-lost-001"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));

        when(accountRepository.findByIdForUpdate(sourceAccountId))
                .thenReturn(Optional.of(sourceAccount));
        when(accountRepository.findByIdForUpdate(destinationAccountId))
                .thenReturn(Optional.of(destinationAccount));
        when(accountRepository.findById(sourceAccountId))
                .thenReturn(Optional.of(sourceAccount));

        when(transactionRepository.save(any(Transaction.class)))
                .thenThrow(new DataIntegrityViolationException(
                        "duplicate key value violates unique constraint "
                                + "uk_transactions_idempotency_key"
                ));

        TransferRequest request = new TransferRequest(
                sourceAccountId,
                destinationAccountId,
                new BigDecimal("100.00"),
                "race-lost-001"
        );

        Transaction result = transferService.transfer(request, ownerId);

        assertThat(result).isSameAs(winner);

        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
        verify(eventPublisher, never()).publishEvent(
                any(TransferCompletedEvent.class)
        );
    }

    @Test
    void shouldRejectLostRaceReplayWhenRequesterDoesNotOwnWinner() {
        Account sourceAccount = createAccount("FF-SOURCE16", "ARS");
        Account destinationAccount = createAccount("FF-DESTIN16", "ARS");

        sourceAccount.deposit(new BigDecimal("500.00"));

        Transaction winner = new Transaction(
                sourceAccountId,
                destinationAccountId,
                new BigDecimal("100.00"),
                "ARS",
                "race-lost-foreign-001"
        );
        winner.markAsCompleted();

        when(transactionRepository.findByIdempotencyKey("race-lost-foreign-001"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));

        when(accountRepository.findByIdForUpdate(sourceAccountId))
                .thenReturn(Optional.of(sourceAccount));
        when(accountRepository.findByIdForUpdate(destinationAccountId))
                .thenReturn(Optional.of(destinationAccount));

        when(transactionRepository.save(any(Transaction.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        TransferRequest request = new TransferRequest(
                sourceAccountId,
                destinationAccountId,
                new BigDecimal("100.00"),
                "race-lost-foreign-001"
        );

        // When the winner is re-read, its source account turns out to be
        // owned by someone else: the recovery path must enforce ownership
        // exactly like a regular replay does.
        Account foreignSource = new Account(
                new AccountNumber("FF-FOREIGN1"),
                "ARS",
                UUID.randomUUID()
        );
        when(accountRepository.findById(sourceAccountId))
                .thenReturn(Optional.of(foreignSource));

        assertThatThrownBy(() -> transferService.transfer(request, ownerId))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void shouldPropagateIntegrityViolationUnrelatedToIdempotency() {
        Account sourceAccount = createAccount("FF-SOURCE17", "ARS");
        Account destinationAccount = createAccount("FF-DESTIN17", "ARS");

        sourceAccount.deposit(new BigDecimal("500.00"));

        when(transactionRepository.findByIdempotencyKey("other-violation-001"))
                .thenReturn(Optional.empty());

        when(accountRepository.findByIdForUpdate(sourceAccountId))
                .thenReturn(Optional.of(sourceAccount));
        when(accountRepository.findByIdForUpdate(destinationAccountId))
                .thenReturn(Optional.of(destinationAccount));

        DataIntegrityViolationException violation =
                new DataIntegrityViolationException("some other constraint");

        when(transactionRepository.save(any(Transaction.class)))
                .thenThrow(violation);

        TransferRequest request = new TransferRequest(
                sourceAccountId,
                destinationAccountId,
                new BigDecimal("100.00"),
                "other-violation-001"
        );

        assertThatThrownBy(() -> transferService.transfer(request, ownerId))
                .isSameAs(violation);
    }

    /**
     * Transaction manager that performs no real transaction handling, so
     * the service can be exercised with plain mocks.
     */
    private static final class NoOpTransactionManager
            implements PlatformTransactionManager {

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

    @Test
    void shouldEmbedOutboxEventIdAndTransactionIdInPayload() {
        Account sourceAccount = createAccount("FF-SOURCE18", "ARS");
        Account destinationAccount = createAccount("FF-DESTIN18", "ARS");

        sourceAccount.deposit(new BigDecimal("500.00"));

        when(accountRepository.findByIdForUpdate(sourceAccountId))
                .thenReturn(Optional.of(sourceAccount));
        when(accountRepository.findByIdForUpdate(destinationAccountId))
                .thenReturn(Optional.of(destinationAccount));

        TransferRequest request = new TransferRequest(
                sourceAccountId,
                destinationAccountId,
                new BigDecimal("100.00"),
                "payload-ids-001"
        );

        stubTransactionSave();
        Transaction saved = transferService.transfer(request, ownerId);

        verify(outboxEventRepository).save(
                argThat(event ->
                        event.getPayload().contains(
                                "\"eventId\":\"" + event.getId() + "\"")
                                && event.getPayload().contains(
                                "\"transactionId\":\"" + saved.getId() + "\"")
                                && event.getAggregateId().equals(saved.getId())
                )
        );

        verify(eventPublisher).publishEvent(
                argThat((TransferCompletedEvent e) ->
                        e.transactionId().equals(saved.getId())
                                && e.eventId() != null)
        );
    }

    private Account createAccount(
            String accountNumber,
            String currency
    ) {
        return new Account(
                new AccountNumber(accountNumber),
                currency,
                ownerId
        );
    }

    private void stubTransactionSave() {
        when(transactionRepository.save(any(Transaction.class)))
                .thenAnswer(invocation -> {
                    Transaction saved = invocation.getArgument(0);

                    ReflectionTestUtils.setField(
                            saved,
                            "id",
                            UUID.randomUUID()
                    );

                    return saved;
                });
    }
}