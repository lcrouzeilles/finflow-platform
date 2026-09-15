package com.finflow.transaction_service.service;

import com.finflow.transaction_service.domain.account.Account;
import com.finflow.transaction_service.domain.transaction.TransferRequest;
import com.finflow.transaction_service.exception.AccountNotFoundException;
import com.finflow.transaction_service.exception.InvalidTransferException;
import com.finflow.transaction_service.repository.AccountRepository;
import com.finflow.transaction_service.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransferService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    public TransferService(
            AccountRepository accountRepository,
            TransactionRepository transactionRepository
    ) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
    }

    @Transactional
    public void transfer(TransferRequest request) {
        validateRequest(request);

        Account sourceAccount = accountRepository.findById(
                request.sourceAccountId()
        ).orElseThrow(() ->
                new AccountNotFoundException(
                        request.sourceAccountId()
                )
        );

        Account destinationAccount = accountRepository.findById(
                request.destinationAccountId()
        ).orElseThrow(() ->
                new AccountNotFoundException(
                        request.destinationAccountId()
                )
        );

        validateAccounts(sourceAccount, destinationAccount);

        sourceAccount.withdraw(request.amount());
        destinationAccount.deposit(request.amount());

        accountRepository.save(sourceAccount);
        accountRepository.save(destinationAccount);
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
}