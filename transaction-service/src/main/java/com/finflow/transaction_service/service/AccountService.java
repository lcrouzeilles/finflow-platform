package com.finflow.transaction_service.service;

import com.finflow.transaction_service.domain.account.Account;
import com.finflow.transaction_service.domain.account.AccountNumberGenerator;
import com.finflow.transaction_service.exception.AccountNotFoundException;
import com.finflow.transaction_service.exception.OwnerNotFoundException;
import com.finflow.transaction_service.repository.AccountRepository;
import com.finflow.transaction_service.repository.OwnerRepository;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final AccountNumberGenerator accountNumberGenerator;
    private final OwnerRepository ownerRepository;
    private final CacheManager cacheManager;

    public AccountService(
            AccountRepository accountRepository,
            AccountNumberGenerator accountNumberGenerator,
            OwnerRepository ownerRepository, CacheManager cacheManager
    ) {
        this.accountRepository = accountRepository;
        this.accountNumberGenerator = accountNumberGenerator;
        this.ownerRepository = ownerRepository;
        this.cacheManager = cacheManager;
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

    public Account getAccount(UUID accountId) {
        Cache cache = cacheManager.getCache("accounts");

        if (cache != null) {
            try {
                Account cachedAccount = cache.get(accountId, Account.class);

                if (cachedAccount != null) {
                    return cachedAccount;
                }
            } catch (RuntimeException ignored) {
                // Redis is unavailable. Fall back to the database.
            }
        }

        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));

        if (cache != null) {
            try {
                cache.put(accountId, account);
            } catch (RuntimeException ignored) {
                // Redis is unavailable. The database remains the source of truth.
            }
        }

        return account;
    }

}
