package com.finflow.transaction_service.repository;

import com.finflow.transaction_service.domain.account.Account;
import com.finflow.transaction_service.domain.account.AccountNumber;
import com.finflow.transaction_service.domain.account.AccountStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AccountRepositoryTest {

    @Autowired
    private AccountRepository accountRepository;

    @Test
    void shouldSaveAndRetrieveAccount() {
        UUID ownerId = UUID.fromString(
                "7f3c2a91-6d84-4b17-9e52-1a6f8c3d0b45"
        );

        Account account = new Account(
                new AccountNumber("FF-ABC12345"),
                "ARS",
                ownerId
        );

        Account savedAccount = accountRepository.save(account);

        Account retrievedAccount = accountRepository
                .findById(savedAccount.getId())
                .orElseThrow();

        assertThat(retrievedAccount.getId())
                .isEqualTo(savedAccount.getId());

        assertThat(retrievedAccount.getOwnerId())
                .isEqualTo(ownerId);

        assertThat(retrievedAccount.getAccountNumber())
                .isEqualTo(new AccountNumber("FF-ABC12345"));

        assertThat(retrievedAccount.getCurrency())
                .isEqualTo("ARS");

        assertThat(retrievedAccount.getBalance())
                .isEqualByComparingTo("0");

        assertThat(retrievedAccount.getStatus())
                .isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void shouldFindAccountByAccountNumber() {
        UUID ownerId = UUID.fromString(
                "7f3c2a91-6d84-4b17-9e52-1a6f8c3d0b45"
        );

        Account account = new Account(
                new AccountNumber("FF-ABC12345"),
                "ARS",
                ownerId
        );

        accountRepository.save(account);

        Account retrievedAccount = accountRepository
                .findByAccountNumber(new AccountNumber("FF-ABC12345"))
                .orElseThrow();

        assertThat(retrievedAccount.getAccountNumber())
                .isEqualTo(new AccountNumber("FF-ABC12345"));
    }
}