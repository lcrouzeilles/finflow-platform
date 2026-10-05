package com.finflow.transaction_service.service;

import com.finflow.transaction_service.cache.RedisAvailability;
import com.finflow.transaction_service.domain.account.Account;
import com.finflow.transaction_service.domain.account.AccountNumberGenerator;
import com.finflow.transaction_service.exception.AccountNotFoundException;
import com.finflow.transaction_service.exception.OwnerNotFoundException;
import com.finflow.transaction_service.repository.AccountRepository;
import com.finflow.transaction_service.repository.OwnerRepository;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final AccountNumberGenerator accountNumberGenerator;
    private final OwnerRepository ownerRepository;
    private final CacheManager cacheManager;
    private final RedisAvailability redisAvailability;

    public AccountService(
            AccountRepository accountRepository,
            AccountNumberGenerator accountNumberGenerator,
            OwnerRepository ownerRepository, CacheManager cacheManager, RedisAvailability redisAvailability
    ) {
        this.accountRepository = accountRepository;
        this.accountNumberGenerator = accountNumberGenerator;
        this.ownerRepository = ownerRepository;
        this.cacheManager = cacheManager;
        this.redisAvailability = redisAvailability;
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

        if (cache != null && redisAvailability.isAvailable()) {
            try {
                Account cachedAccount = cache.get(accountId, Account.class);

                if (cachedAccount != null) {
                    return cachedAccount;
                }
            } catch (RuntimeException exception) {
                redisAvailability.markFailure();
            }
        }

        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));

        if (cache != null && redisAvailability.isAvailable()) {
            try {
                cache.put(accountId, account);
            } catch (RuntimeException exception) {
                redisAvailability.markFailure();
            }
        }

        return account;
    }

}
