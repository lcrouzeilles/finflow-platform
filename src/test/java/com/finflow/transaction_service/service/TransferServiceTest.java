package com.finflow.transaction_service.service;

import com.finflow.transaction_service.domain.account.Account;
import com.finflow.transaction_service.domain.account.AccountNumber;
import com.finflow.transaction_service.domain.transaction.TransferRequest;
import com.finflow.transaction_service.exception.AccountNotFoundException;
import com.finflow.transaction_service.exception.InvalidTransferException;
import com.finflow.transaction_service.repository.AccountRepository;
import com.finflow.transaction_service.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionRepository transactionRepository;

    private TransferService transferService;

    private UUID sourceAccountId;
    private UUID destinationAccountId;
    private UUID ownerId;

    @BeforeEach
    void setUp() {
        transferService = new TransferService(
                accountRepository,
                transactionRepository
        );

        sourceAccountId = UUID.randomUUID();
        destinationAccountId = UUID.randomUUID();
        ownerId = UUID.randomUUID();
    }

    @Test
    void shouldRejectNullTransferRequest() {
        assertThatThrownBy(() ->
                transferService.transfer(null)
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
                new BigDecimal("100.00")
        );

        assertThatThrownBy(() ->
                transferService.transfer(request)
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
                new BigDecimal("100.00")
        );

        assertThatThrownBy(() ->
                transferService.transfer(request)
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
                new BigDecimal("100.00")
        );

        assertThatThrownBy(() ->
                transferService.transfer(request)
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

        when(accountRepository.findById(sourceAccountId))
                .thenReturn(Optional.of(sourceAccount));

        when(accountRepository.findById(destinationAccountId))
                .thenReturn(Optional.of(destinationAccount));

        TransferRequest request = new TransferRequest(
                sourceAccountId,
                destinationAccountId,
                new BigDecimal("100.00")
        );

        transferService.transfer(request);

        verify(accountRepository).findById(sourceAccountId);
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
        when(accountRepository.findById(sourceAccountId))
                .thenReturn(Optional.of(sourceAccount));

        when(accountRepository.findById(destinationAccountId))
                .thenReturn(Optional.of(destinationAccount));

        TransferRequest request = new TransferRequest(
                sourceAccountId,
                destinationAccountId,
                new BigDecimal("100.00")
        );

        transferService.transfer(request);

        verify(accountRepository).findById(destinationAccountId);
    }

    @Test
    void shouldRejectWhenSourceAccountDoesNotExist() {
        when(accountRepository.findById(sourceAccountId))
                .thenReturn(Optional.empty());

        TransferRequest request = new TransferRequest(
                sourceAccountId,
                destinationAccountId,
                new BigDecimal("100.00")
        );

        assertThatThrownBy(() ->
                transferService.transfer(request)
        )
                .isInstanceOf(AccountNotFoundException.class)
                .hasMessage(
                        "Account not found with id: " + sourceAccountId
                );

        verify(accountRepository).findById(sourceAccountId);
    }

    @Test
    void shouldRejectWhenDestinationAccountDoesNotExist() {
        Account sourceAccount = createAccount(
                "FF-SOURCE03",
                "ARS"
        );

        when(accountRepository.findById(sourceAccountId))
                .thenReturn(Optional.of(sourceAccount));

        when(accountRepository.findById(destinationAccountId))
                .thenReturn(Optional.empty());

        TransferRequest request = new TransferRequest(
                sourceAccountId,
                destinationAccountId,
                new BigDecimal("100.00")
        );

        assertThatThrownBy(() ->
                transferService.transfer(request)
        )
                .isInstanceOf(AccountNotFoundException.class)
                .hasMessage(
                        "Account not found with id: "
                                + destinationAccountId
                );

        verify(accountRepository).findById(sourceAccountId);
        verify(accountRepository).findById(destinationAccountId);
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
}