package com.finflow.transaction_service.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finflow.transaction_service.domain.account.Account;
import com.finflow.transaction_service.domain.outbox.OutboxEvent;
import com.finflow.transaction_service.domain.transaction.Transaction;
import com.finflow.transaction_service.domain.transaction.TransferRequest;
import com.finflow.transaction_service.event.TransferCompletedEvent;
import com.finflow.transaction_service.exception.AccountNotFoundException;
import com.finflow.transaction_service.exception.IdempotencyKeyConflictException;
import com.finflow.transaction_service.exception.InvalidTransferException;
import com.finflow.transaction_service.repository.AccountRepository;
import com.finflow.transaction_service.repository.OutboxEventRepository;
import com.finflow.transaction_service.repository.TransactionRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.UUID;

@Service
public class TransferService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public TransferService(
            AccountRepository accountRepository,
            TransactionRepository transactionRepository,
            ApplicationEventPublisher eventPublisher,
            OutboxEventRepository outboxEventRepository,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager
    ) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.eventPublisher = eventPublisher;
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Executes a transfer on behalf of {@code requestingOwnerId}, which must
     * be derived from the authenticated token (never from the request body).
     * The source account must belong to the requesting owner; otherwise an
     * {@link AccessDeniedException} is thrown. The same rule applies to the
     * idempotent replay of an existing transaction.
     *
     * <p>The unit of work runs in its own database transaction. If two
     * requests with the same idempotency key race and both reach the insert,
     * the loser fails on {@code uk_transactions_idempotency_key} at commit;
     * that failure is caught here and resolved by returning the winner, so
     * the caller observes the same idempotent response either way.
     */
    public Transaction transfer(TransferRequest request,
                                UUID requestingOwnerId) {

        if (requestingOwnerId == null) {
            throw new IllegalArgumentException(
                    "Authenticated owner ID is required"
            );
        }

        validateRequest(request);

        try {
            return transactionTemplate.execute(status ->
                    doTransfer(request, requestingOwnerId)
            );
        } catch (DataIntegrityViolationException exception) {
            return recoverFromIdempotencyRace(
                    request,
                    requestingOwnerId,
                    exception
            );
        }
    }

    private Transaction doTransfer(TransferRequest request,
                                   UUID requestingOwnerId) {

        Transaction existingTransaction = findExistingTransaction(request);

        if (existingTransaction != null) {
            return replay(existingTransaction, request, requestingOwnerId);
        }

        Account sourceAccount;
        Account destinationAccount;

        UUID sourceId = request.sourceAccountId();
        UUID destinationId = request.destinationAccountId();

        if (sourceId.compareTo(destinationId) < 0) {
            sourceAccount = getAccountForUpdate(sourceId);
            destinationAccount = getAccountForUpdate(destinationId);
        } else {
            destinationAccount = getAccountForUpdate(destinationId);
            sourceAccount = getAccountForUpdate(sourceId);
        }

        // A concurrent request with the same key may have committed while
        // this one was waiting for the row locks. Re-check before debiting
        // so the unique constraint is only the last line of defense.
        existingTransaction = findExistingTransaction(request);

        if (existingTransaction != null) {
            return replay(existingTransaction, request, requestingOwnerId);
        }

        assertOwnsAccount(sourceAccount, requestingOwnerId);

        validateAccounts(sourceAccount, destinationAccount);

        executeTransfer(sourceAccount, destinationAccount, request.amount());

        Transaction transaction = createTransaction(
                sourceAccount,
                request
        );

        Transaction savedTransaction = transactionRepository.save(transaction);

        TransferCompletedEvent event = new TransferCompletedEvent(
                request.sourceAccountId(),
                request.destinationAccountId()
        );

        OutboxEvent outboxEvent = new OutboxEvent(
                "TRANSACTION",
                savedTransaction.getId(),
                "TRANSFER_COMPLETED",
                serializeEvent(event)
        );

        outboxEventRepository.save(outboxEvent);

        // Temporary: preserve the existing Kafka flow until the
        // Outbox publisher is implemented.
        eventPublisher.publishEvent(event);

        return savedTransaction;
    }

    /**
     * Idempotent replay: the existing transaction is returned only to the
     * owner that created it and only for an identical payload.
     */
    private Transaction replay(
            Transaction existingTransaction,
            TransferRequest request,
            UUID requestingOwnerId
    ) {
        assertOwnsExistingTransaction(existingTransaction, requestingOwnerId);
        validateIdempotencyReuse(existingTransaction, request);
        return existingTransaction;
    }

    /**
     * Called when the unit of work was rolled back by a constraint
     * violation. If a transaction now exists for the key, another request
     * won the race and its result is the idempotent answer for this one.
     * Any other integrity violation is propagated untouched.
     */
    private Transaction recoverFromIdempotencyRace(
            TransferRequest request,
            UUID requestingOwnerId,
            DataIntegrityViolationException cause
    ) {
        Transaction winner = transactionRepository
                .findByIdempotencyKey(request.idempotencyKey())
                .orElseThrow(() -> cause);

        return replay(winner, request, requestingOwnerId);
    }

    private Transaction findExistingTransaction(TransferRequest request) {
        validateIdempotencyKey(request.idempotencyKey());

        return transactionRepository
                .findByIdempotencyKey(request.idempotencyKey())
                .orElse(null);
    }

    private void assertOwnsAccount(
            Account account,
            UUID requestingOwnerId
    ) {
        if (!requestingOwnerId.equals(account.getOwnerId())) {
            throw new AccessDeniedException(
                    "You are not authorized to transfer from this account"
            );
        }
    }

    /**
     * An idempotency key is only replayable by the owner of the source
     * account of the transaction it created. Otherwise a foreign key would
     * leak the transaction of another owner.
     */
    private void assertOwnsExistingTransaction(
            Transaction transaction,
            UUID requestingOwnerId
    ) {
        Account sourceAccount = accountRepository
                .findById(transaction.getSourceAccountId())
                .orElseThrow(() -> new AccessDeniedException(
                        "You are not authorized to access this transaction"
                ));

        if (!requestingOwnerId.equals(sourceAccount.getOwnerId())) {
            throw new AccessDeniedException(
                    "You are not authorized to access this transaction"
            );
        }
    }

    private void executeTransfer(
            Account sourceAccount,
            Account destinationAccount,
            BigDecimal amount
    ) {
        sourceAccount.withdraw(amount);
        destinationAccount.deposit(amount);

        accountRepository.save(sourceAccount);
        accountRepository.save(destinationAccount);
    }

    private Transaction createTransaction(
            Account sourceAccount,
            TransferRequest request
    ) {
        Transaction transaction = new Transaction(
                request.sourceAccountId(),
                request.destinationAccountId(),
                request.amount(),
                sourceAccount.getCurrency(),
                request.idempotencyKey()
        );

        transaction.markAsCompleted();

        return transaction;
    }

    private void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException(
                    "Idempotency-Key header is required"
            );
        }

        if (idempotencyKey.length() > 100) {
            throw new IllegalArgumentException(
                    "Idempotency-Key must not exceed 100 characters"
            );
        }
    }

    private void validateRequest(TransferRequest request) {
        if (request == null) {
            throw new InvalidTransferException(
                    "Transfer request cannot be null"
            );
        }

        if (request.sourceAccountId() == null) {
            throw new InvalidTransferException(
                    "Source account ID cannot be null"
            );
        }

        if (request.destinationAccountId() == null) {
            throw new InvalidTransferException(
                    "Destination account ID cannot be null"
            );
        }

        if (request.sourceAccountId()
                .equals(request.destinationAccountId())) {
            throw new InvalidTransferException(
                    "Source and destination accounts must be different"
            );
        }

        if (request.amount() == null ||
                request.amount().signum() <= 0) {
            throw new InvalidTransferException(
                    "Transfer amount must be greater than zero"
            );
        }

        if (request.idempotencyKey() == null
                || request.idempotencyKey().isBlank()) {
            throw new IllegalArgumentException(
                    "Idempotency key cannot be blank"
            );
        }
    }

    private void validateAccounts(
            Account sourceAccount,
            Account destinationAccount
    ) {
        if (!sourceAccount.getCurrency()
                .equals(destinationAccount.getCurrency())) {
            throw new InvalidTransferException(
                    "Source and destination currencies must match"
            );
        }
    }

    private void validateIdempotencyReuse(
            Transaction transaction,
            TransferRequest request
    ) {
        boolean sameSourceAccount =
                transaction.getSourceAccountId()
                        .equals(request.sourceAccountId());

        boolean sameDestinationAccount =
                transaction.getDestinationAccountId()
                        .equals(request.destinationAccountId());

        boolean sameAmount =
                transaction.getAmount()
                        .compareTo(request.amount()) == 0;

        if (!sameSourceAccount
                || !sameDestinationAccount
                || !sameAmount) {
            throw new IdempotencyKeyConflictException(
                    "Idempotency-Key was already used with different transfer data"
            );
        }
    }

    private String serializeEvent(TransferCompletedEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Failed to serialize transfer completed event",
                    exception
            );
        }
    }

    private Account getAccountForUpdate(UUID accountId) {
        return accountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() ->
                        new AccountNotFoundException(accountId)
                );
    }
}
