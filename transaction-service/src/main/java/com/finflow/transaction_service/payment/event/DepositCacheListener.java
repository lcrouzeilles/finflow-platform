package com.finflow.transaction_service.payment.event;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Evicts the credited account from the cache once the deposit transaction
 * has committed. Uses {@code evictIfPresent} (synchronous DEL), see
 * {@link com.finflow.transaction_service.event.TransferCacheListener}.
 */
@Component
public class DepositCacheListener {

    static final String CACHE_NAME = "accounts";

    private final CacheManager cacheManager;

    public DepositCacheListener(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleDepositCompleted(DepositCompletedEvent event) {
        Cache cache = cacheManager.getCache(CACHE_NAME);

        if (cache != null) {
            cache.evictIfPresent(event.accountId());
        }
    }
}
