package com.finflow.transaction_service.service;

import com.finflow.transaction_service.domain.account.Owner;
import com.finflow.transaction_service.domain.account.Account;
import com.finflow.transaction_service.domain.account.AccountNumber;
import com.finflow.transaction_service.domain.transaction.Transaction;
import com.finflow.transaction_service.domain.transaction.TransferRequest;
import com.finflow.transaction_service.exception.InvalidTransferException;
import com.finflow.transaction_service.repository.AccountRepository;
import com.finflow.transaction_service.repository.OwnerRepository;
import com.finflow.transaction_service.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@SpringBootTest
@ActiveProfiles("test")
class TransferServiceRollbackTest {

    @Autowired
    private TransferService transferService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private OwnerRepository ownerRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private TransactionRepository transactionRepository;

    private UUID sourceAccountId;
    private UUID destinationAccountId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM transactions");
        jdbcTemplate.update("DELETE FROM accounts");
        jdbcTemplate.update("DELETE FROM owners");

        Owner sourceOwner = ownerRepository.save(new Owner());
        Owner destinationOwner = ownerRepository.save(new Owner());

        Account sourceAccount = new Account(
                new AccountNumber("FF-SOURCE01"),
                "ARS",
                sourceOwner.getId()
        );

        Account destinationAccount = new Account(
                new AccountNumber("FF-DESTI001"),
                "ARS",
                destinationOwner.getId()
        );

        sourceAccount.deposit(new BigDecimal("1000.00"));
        destinationAccount.deposit(new BigDecimal("200.00"));

        sourceAccountId = accountRepository.save(sourceAccount).getId();
        destinationAccountId = accountRepository.save(destinationAccount).getId();
    }

    @Test
    void shouldRollbackAccountBalancesWhenTransactionPersistenceFails() {
        doThrow(new RuntimeException("Transaction persistence failed"))
                .when(transactionRepository)
                .save(any(Transaction.class));

        TransferRequest request = new TransferRequest(
                sourceAccountId,
                destinationAccountId,
                new BigDecimal("300.00")
        );

        assertThatThrownBy(() -> transferService.transfer(request))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Transaction persistence failed");

        Account sourceAccountAfterRollback =
                accountRepository.findById(sourceAccountId)
                        .orElseThrow();

        Account destinationAccountAfterRollback =
                accountRepository.findById(destinationAccountId)
                        .orElseThrow();

        assertThat(sourceAccountAfterRollback.getBalance())
                .isEqualByComparingTo("1000.00");

        assertThat(destinationAccountAfterRollback.getBalance())
                .isEqualByComparingTo("200.00");

        verify(transactionRepository).save(any(Transaction.class));
    }
}