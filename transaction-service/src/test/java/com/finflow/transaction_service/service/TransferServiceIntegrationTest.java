package com.finflow.transaction_service.service;

import com.finflow.transaction_service.domain.account.Owner;
import com.finflow.transaction_service.domain.account.Account;
import com.finflow.transaction_service.domain.account.AccountNumber;
import com.finflow.transaction_service.domain.transaction.Transaction;
import com.finflow.transaction_service.domain.transaction.TransactionStatus;
import com.finflow.transaction_service.domain.transaction.TransferRequest;
import com.finflow.transaction_service.exception.IdempotencyKeyConflictException;
import com.finflow.transaction_service.exception.InsufficientFundsException;
import com.finflow.transaction_service.repository.AccountRepository;
import com.finflow.transaction_service.repository.OwnerRepository;
import com.finflow.transaction_service.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@SpringBootTest
@ActiveProfiles("test")
class TransferServiceIntegrationTest {

    @Autowired
    private TransferService transferService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private OwnerRepository ownerRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    private UUID sourceAccountId;
    private UUID destinationAccountId;

    @BeforeEach
    void setUp() {
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        ownerRepository.deleteAll();

        Owner sourceOwner = ownerRepository.save(new Owner());
        Owner destinationOwner = ownerRepository.save(new Owner());

        Account sourceAccount = accountRepository.save(
                new Account(
                        new AccountNumber("FF-TRANS001"),
                        "ARS",
                        sourceOwner.getId()
                )
        );

        Account destinationAccount = accountRepository.save(
                new Account(
                        new AccountNumber("FF-TRANS002"),
                        "ARS",
                        destinationOwner.getId()
                )
        );

        sourceAccount.deposit(new BigDecimal("500.00"));

        accountRepository.save(sourceAccount);

        sourceAccountId = sourceAccount.getId();
        destinationAccountId = destinationAccount.getId();
    }

    @Test
    void shouldCommitAccountChangesAndTransaction() {
        TransferRequest request = new TransferRequest(
                sourceAccountId,
                destinationAccountId,
                new BigDecimal("100.00"),
                "transfer-test-001"
        );

        transferService.transfer(request);

        Account sourceAccount = accountRepository
                .findById(sourceAccountId)
                .orElseThrow();

        Account destinationAccount = accountRepository
                .findById(destinationAccountId)
                .orElseThrow();

        assertThat(sourceAccount.getBalance())
                .isEqualByComparingTo("400.00");

        assertThat(destinationAccount.getBalance())
                .isEqualByComparingTo("100.00");

        assertThat(transactionRepository.count())
                .isEqualTo(1);

        Transaction transaction = transactionRepository.findAll()
                .stream()
                .findFirst()
                .orElseThrow();

        assertThat(transaction.getSourceAccountId())
                .isEqualTo(sourceAccountId);

        assertThat(transaction.getDestinationAccountId())
                .isEqualTo(destinationAccountId);

        assertThat(transaction.getAmount())
                .isEqualByComparingTo("100.00");

        assertThat(transaction.getCurrency())
                .isEqualTo("ARS");

        assertThat(transaction.getStatus())
                .isEqualTo(TransactionStatus.COMPLETED);
    }

    @Test
    void shouldRollbackWhenSourceAccountHasInsufficientFunds() {
        TransferRequest request = new TransferRequest(
                sourceAccountId,
                destinationAccountId,
                new BigDecimal("600.00"),
                "transfer-test-001"
        );

        assertThatThrownBy(() -> transferService.transfer(request))
                .isInstanceOf(InsufficientFundsException.class);

        Account sourceAccount = accountRepository
                .findById(sourceAccountId)
                .orElseThrow();

        Account destinationAccount = accountRepository
                .findById(destinationAccountId)
                .orElseThrow();

        assertThat(sourceAccount.getBalance())
                .isEqualByComparingTo("500.00");

        assertThat(destinationAccount.getBalance())
                .isEqualByComparingTo("0.00");

        assertThat(transactionRepository.count())
                .isZero();
    }

    @Test
    void shouldReturnExistingTransactionWhenSameIdempotencyKeyIsReused() {
        String idempotencyKey = "transfer-retry-001";

        TransferRequest request = new TransferRequest(
                sourceAccountId,
                destinationAccountId,
                new BigDecimal("100.00"),
                idempotencyKey
        );

        Transaction firstTransaction =
                transferService.transfer(request);

        Transaction secondTransaction =
                transferService.transfer(request);

        assertThat(secondTransaction.getId())
                .isEqualTo(firstTransaction.getId());

        Account sourceAccount = accountRepository
                .findById(sourceAccountId)
                .orElseThrow();

        Account destinationAccount = accountRepository
                .findById(destinationAccountId)
                .orElseThrow();

        assertThat(sourceAccount.getBalance())
                .isEqualByComparingTo("400.00");

        assertThat(destinationAccount.getBalance())
                .isEqualByComparingTo("100.00");

        assertThat(transactionRepository.count())
                .isEqualTo(1);
    }

    @Test
    void shouldRejectSameIdempotencyKeyWithDifferentTransferData() {
        String idempotencyKey = "same-key-different-data";

        TransferRequest firstRequest = new TransferRequest(
                sourceAccountId,
                destinationAccountId,
                new BigDecimal("100.00"),
                idempotencyKey
        );

        TransferRequest secondRequest = new TransferRequest(
                sourceAccountId,
                destinationAccountId,
                new BigDecimal("200.00"),
                idempotencyKey
        );

        transferService.transfer(firstRequest);

        assertThatThrownBy(() ->
                transferService.transfer(secondRequest)
        )
                .isInstanceOf(IdempotencyKeyConflictException.class);

        Account sourceAccount =
                accountRepository.findById(sourceAccountId).orElseThrow();

        Account destinationAccount =
                accountRepository.findById(destinationAccountId).orElseThrow();

        assertThat(sourceAccount.getBalance())
                .isEqualByComparingTo("400.00");

        assertThat(destinationAccount.getBalance())
                .isEqualByComparingTo("100.00");

        assertThat(transactionRepository.count())
                .isEqualTo(1);
    }

}