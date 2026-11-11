package com.finflow.transaction_service.service;

import com.finflow.transaction_service.cache.RedisAvailability;
import com.finflow.transaction_service.domain.account.Account;
import com.finflow.transaction_service.domain.account.AccountNumber;
import com.finflow.transaction_service.domain.account.AccountNumberGenerator;
import com.finflow.transaction_service.exception.AccountNotFoundException;
import com.finflow.transaction_service.exception.OwnerNotFoundException;
import com.finflow.transaction_service.repository.AccountRepository;
import com.finflow.transaction_service.repository.OwnerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class AccountService {

    private static final Logger log =
            LoggerFactory.getLogger(AccountService.class);

    private static final int MAX_ACCOUNT_NUMBER_ATTEMPTS = 5;

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
                generateUnusedAccountNumber(),
                currency,
                ownerId
        );
        Account saved = accountRepository.save(newAccount);

        log.info(
                "Account created: accountId={}, ownerId={}, currency={}",
                saved.getId(),
                ownerId,
                currency
        );

        return saved;
    }

    /**
     * Collisions are unlikely (36^8 combinations) but would otherwise fail
     * on the unique constraint. Checking before insert keeps the retry inside
     * one transaction; a concurrent insert of the same number still hits the
     * constraint and is reported as a conflict by the error handler.
     */
    private AccountNumber generateUnusedAccountNumber() {
        for (int attempt = 0; attempt < MAX_ACCOUNT_NUMBER_ATTEMPTS; attempt++) {
            AccountNumber candidate = accountNumberGenerator.generate();

            if (!accountRepository.existsByAccountNumber(candidate)) {
                return candidate;
            }
        }

        throw new IllegalStateException(
                "Could not generate an unused account number after "
                        + MAX_ACCOUNT_NUMBER_ATTEMPTS + " attempts"
        );
    }

    /**
     * Returns the account only if it belongs to {@code ownerId}. A
     * non-owner gets the same {@link AccountNotFoundException} as for a
     * nonexistent account, so the endpoint does not reveal whether the
     * account ID exists.
     */
    public Account getOwnedAccount(UUID accountId, UUID ownerId) {
        if (ownerId == null) {
            throw new IllegalArgumentException("Owner ID cannot be null");
        }

        Account account = getAccount(accountId);

        if (!ownerId.equals(account.getOwnerId())) {
            throw new AccountNotFoundException(accountId);
        }

        return account;
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
