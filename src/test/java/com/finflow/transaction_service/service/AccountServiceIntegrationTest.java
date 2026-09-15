package com.finflow.transaction_service.service;

import com.finflow.transaction_service.domain.account.Owner;
import com.finflow.transaction_service.domain.account.Account;
import com.finflow.transaction_service.exception.OwnerNotFoundException;
import com.finflow.transaction_service.repository.AccountRepository;
import com.finflow.transaction_service.repository.OwnerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AccountServiceIntegrationTest {

    @Autowired
    private AccountService accountService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private OwnerRepository ownerRepository;

    @BeforeEach
    void cleanDatabase() {
        accountRepository.deleteAll();
        ownerRepository.deleteAll();
    }

    @Test
    void shouldCreateAccountForExistingOwner() {
        Owner owner = ownerRepository.saveAndFlush(new Owner());

        Account createdAccount = accountService.createAccount(
                owner.getId(),
                "ARS"
        );

        assertThat(createdAccount.getId()).isNotNull();
        assertThat(createdAccount.getOwnerId())
                .isEqualTo(owner.getId());
        assertThat(createdAccount.getCurrency())
                .isEqualTo("ARS");
        assertThat(createdAccount.getBalance())
                .isEqualByComparingTo("0");
        assertThat(createdAccount.getAccountNumber())
                .isNotNull();

        assertThat(accountRepository.findById(createdAccount.getId()))
                .isPresent();
    }

    @Test
    void shouldRejectAccountCreationForNonexistentOwner() {
        UUID nonexistentOwnerId = UUID.fromString(
                "11111111-1111-1111-1111-111111111111"
        );

        assertThatThrownBy(() ->
                accountService.createAccount(nonexistentOwnerId, "ARS")
        )
                .isInstanceOf(OwnerNotFoundException.class)
                .hasMessageContaining(nonexistentOwnerId.toString());

        assertThat(accountRepository.count()).isZero();
    }

}
