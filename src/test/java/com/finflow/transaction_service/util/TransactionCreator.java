package com.finflow.transaction_service.util;

import com.finflow.transaction_service.domain.transaction.Transaction;
import com.finflow.transaction_service.domain.transaction.TransferRequest;
import com.finflow.transaction_service.repository.TransactionRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class TransactionCreator {

    private final TransactionRepository transactionRepository;

    public TransactionCreator(
            TransactionRepository transactionRepository
    ) {
        this.transactionRepository = transactionRepository;
    }

    public Transaction createCompletedTransaction(
            TransferRequest request,
            String currency
    ) {
        Transaction transaction = new Transaction(
                request.sourceAccountId(),
                request.destinationAccountId(),
                request.amount(),
                currency,
                "test-" + UUID.randomUUID()
        );

        transaction.markAsCompleted();

        return transactionRepository.save(transaction);
    }
}