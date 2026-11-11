package com.finflow.transaction_service.service;

import com.finflow.transaction_service.cache.RedisAvailability;
import com.finflow.transaction_service.domain.account.Account;
import com.finflow.transaction_service.domain.account.AccountNumber;
import com.finflow.transaction_service.domain.account.AccountNumberGenerator;
import com.finflow.transaction_service.repository.AccountRepository;
import com.finflow.transaction_service.repository.OwnerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    private static final AccountNumber TAKEN = new AccountNumber("FF-TAKEN001");
    private static final AccountNumber FREE = new AccountNumber("FF-FREE0001");

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private AccountNumberGenerator accountNumberGenerator;

    @Mock
    private OwnerRepository ownerRepository;

    @Mock
    private CacheManager cacheManager;

    @Mock
    private RedisAvailability redisAvailability;

    private AccountService accountService;

    private final UUID ownerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        accountService = new AccountService(
                accountRepository,
                accountNumberGenerator,
                ownerRepository,
                cacheManager,
                redisAvailability
        );
        when(ownerRepository.existsById(ownerId)).thenReturn(true);
    }

    @Test
    void shouldRetryWhenGeneratedAccountNumberIsTaken() {
        when(accountNumberGenerator.generate()).thenReturn(TAKEN, FREE);
        when(accountRepository.existsByAccountNumber(TAKEN)).thenReturn(true);
        when(accountRepository.existsByAccountNumber(FREE)).thenReturn(false);
        when(accountRepository.save(any(Account.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Account account = accountService.createAccount(ownerId, "USD");

        assertThat(account.getAccountNumber()).isEqualTo(FREE);
    }

    @Test
    void shouldFailWhenEveryGeneratedAccountNumberIsTaken() {
        when(accountNumberGenerator.generate()).thenReturn(TAKEN);
        when(accountRepository.existsByAccountNumber(TAKEN)).thenReturn(true);

        assertThatThrownBy(() -> accountService.createAccount(ownerId, "USD"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("5 attempts");

        verify(accountRepository, never()).save(any(Account.class));
    }
}
