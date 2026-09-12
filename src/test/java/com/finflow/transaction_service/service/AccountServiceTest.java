package com.finflow.transaction_service.service;

import com.finflow.transaction_service.domain.account.Account;
import com.finflow.transaction_service.domain.account.AccountNumber;
import com.finflow.transaction_service.domain.account.AccountNumberGenerator;
import com.finflow.transaction_service.exception.OwnerNotFoundException;
import com.finflow.transaction_service.repository.AccountRepository;
import com.finflow.transaction_service.repository.OwnerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private AccountNumberGenerator accountNumberGenerator;

    @Mock
    private OwnerRepository ownerRepository;

    private AccountService accountService;

    private UUID ownerId;
    private AccountNumber generatedAccountNumber;

    @BeforeEach
    void setUp() {
        accountService = new AccountService(
                accountRepository,
                accountNumberGenerator,
                ownerRepository
        );

        ownerId = UUID.fromString(
                "7f3c2a91-6d84-4b17-9e52-1a6f8c3d0b45"
        );

        generatedAccountNumber =
                new AccountNumber("FF-ABC12345");
    }

    @Test
    void shouldCreateAccountWhenOwnerExists() {
        Account savedAccount = new Account(
                generatedAccountNumber,
                "ARS",
                ownerId
        );

        when(ownerRepository.existsById(ownerId))
                .thenReturn(true);

        when(accountNumberGenerator.generate())
                .thenReturn(generatedAccountNumber);

        when(accountRepository.save(any(Account.class)))
                .thenReturn(savedAccount);

        Account result = accountService.createAccount(ownerId, "ARS");

        assertNotNull(result);
        assertEquals(ownerId, result.getOwnerId());
        assertEquals("ARS", result.getCurrency());
        assertEquals(
                0,
                result.getBalance().compareTo(java.math.BigDecimal.ZERO)
        );
        assertEquals(
                generatedAccountNumber,
                result.getAccountNumber()
        );

        verify(ownerRepository).existsById(ownerId);
        verify(accountNumberGenerator).generate();
        verify(accountRepository).save(any(Account.class));
    }

    @Test
    void shouldThrowOwnerNotFoundExceptionWhenOwnerDoesNotExist() {
        when(ownerRepository.existsById(ownerId))
                .thenReturn(false);

        OwnerNotFoundException exception = assertThrows(
                OwnerNotFoundException.class,
                () -> accountService.createAccount(ownerId, "ARS")
        );

        assertTrue(
                exception.getMessage().contains(ownerId.toString())
        );

        verify(ownerRepository).existsById(ownerId);
        verifyNoInteractions(accountNumberGenerator);
        verifyNoInteractions(accountRepository);
    }

    @Test
    void shouldRejectNullOwnerId() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> accountService.createAccount(null, "ARS")
        );

        assertEquals(
                "Owner ID cannot be null",
                exception.getMessage()
        );

        verifyNoInteractions(ownerRepository);
        verifyNoInteractions(accountNumberGenerator);
        verifyNoInteractions(accountRepository);
    }

    @Test
    void shouldUseProvidedCurrency() {
        when(ownerRepository.existsById(ownerId))
                .thenReturn(true);

        when(accountNumberGenerator.generate())
                .thenReturn(generatedAccountNumber);

        when(accountRepository.save(any(Account.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Account result = accountService.createAccount(ownerId, "USD");

        assertEquals("USD", result.getCurrency());

        verify(accountRepository).save(argThat(account ->
                "USD".equals(account.getCurrency())
        ));
    }

    @Test
    void shouldStartAccountWithZeroBalance() {
        when(ownerRepository.existsById(ownerId))
                .thenReturn(true);

        when(accountNumberGenerator.generate())
                .thenReturn(generatedAccountNumber);

        when(accountRepository.save(any(Account.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Account result = accountService.createAccount(ownerId, "ARS");

        assertEquals(
                0,
                result.getBalance().compareTo(java.math.BigDecimal.ZERO)
        );
    }
}
