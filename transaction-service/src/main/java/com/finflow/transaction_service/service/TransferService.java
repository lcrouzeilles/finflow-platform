package com.finflow.transaction_service.service;

import com.finflow.transaction_service.domain.account.Account;
import com.finflow.transaction_service.domain.transaction.Transaction;
import com.finflow.transaction_service.domain.transaction.TransferRequest;
import com.finflow.transaction_service.event.TransferCompletedEvent;
import com.finflow.transaction_service.exception.AccountNotFoundException;
import com.finflow.transaction_service.exception.IdempotencyKeyConflictException;
import com.finflow.transaction_service.exception.InvalidTransferException;
import com.finflow.transaction_service.repository.AccountRepository;
import com.finflow.transaction_service.repository.TransactionRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
public class TransferService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final ApplicationEventPublisher eventPublisher;

    public TransferService(
            AccountRepository accountRepository,
            TransactionRepository transactionRepository, ApplicationEventPublisher eventPublisher
    ) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public Transaction transfer(TransferRequest request) {
        validateRequest(request);

        Transaction existingTransaction = findExistingTransaction(request);

        if (existingTransaction != null) {
            validateIdempotencyReuse(existingTransaction, request);
            return existingTransaction;
        }

        Account sourceAccount = getAccount(request.sourceAccountId());
        Account destinationAccount = getAccount(request.destinationAccountId());

        validateAccounts(sourceAccount, destinationAccount);

        executeTransfer(sourceAccount, destinationAccount, request.amount());

        Transaction transaction = createTransaction(
                sourceAccount,
                request
        );

        Transaction savedTransaction = transactionRepository.save(transaction);

        eventPublisher.publishEvent(
                new TransferCompletedEvent(
                        request.sourceAccountId(),
                        request.destinationAccountId()
                )
        );

        return savedTransaction;
    }

    private Transaction findExistingTransaction(TransferRequest request) {
        validateIdempotencyKey(request.idempotencyKey());

        return transactionRepository
                .findByIdempotencyKey(request.idempotencyKey())
                .orElse(null);
    }

    private Account getAccount(UUID accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() ->
                        new AccountNotFoundException(accountId)
                );
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
}