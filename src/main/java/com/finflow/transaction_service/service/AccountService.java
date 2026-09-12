package com.finflow.transaction_service.service;

import com.finflow.transaction_service.domain.account.Account;
import com.finflow.transaction_service.domain.account.AccountNumberGenerator;
import com.finflow.transaction_service.domain.account.Owner;
import com.finflow.transaction_service.exception.OwnerNotFoundException;
import com.finflow.transaction_service.repository.AccountRepository;
import com.finflow.transaction_service.repository.OwnerRepository;
import org.springframework.stereotype.Service;

import javax.security.auth.login.AccountNotFoundException;
import java.util.Optional;
import java.util.UUID;

@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final AccountNumberGenerator accountNumberGenerator;
    private final OwnerRepository ownerRepository;

    public AccountService(
            AccountRepository accountRepository,
            AccountNumberGenerator accountNumberGenerator,
            OwnerRepository ownerRepository
    ) {
        this.accountRepository = accountRepository;
        this.accountNumberGenerator = accountNumberGenerator;
        this.ownerRepository = ownerRepository;
    }

    public Account createAccount(UUID ownerId, String currency) {
        if (ownerId == null) {
            throw new IllegalArgumentException("Owner ID cannot be null");
        }
        if (!ownerRepository.existsById(ownerId)) {
            throw new OwnerNotFoundException(ownerId);
        }
        Account newAccount = new Account(
                accountNumberGenerator.generate(),
                currency,
                ownerId
        );
        return accountRepository.save(newAccount);
    }
}
