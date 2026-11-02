package com.finflow.transaction_service.event;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Evicts the cached accounts touched by a transfer once the transaction has
 * committed, so the next read sees the new balances.
 *
 * <p>Uses {@link Cache#evictIfPresent(Object)} and not {@link Cache#evict(Object)}
 * on purpose: with Lettuce, Spring Data Redis performs {@code evict} as an
 * asynchronous, fire-and-forget {@code DEL}, so the method returns before the
 * key is gone and a read issued right after the commit can still be served
 * the stale balance. {@code evictIfPresent} executes the {@code DEL}
 * synchronously.
 */
@Component
public class TransferCacheListener {

    private final CacheManager cacheManager;

    public TransferCacheListener(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleTransferCompleted(TransferCompletedEvent event) {
        Cache cache = cacheManager.getCache("accounts");

        if (cache != null) {
            cache.evictIfPresent(event.sourceAccountId());
            cache.evictIfPresent(event.destinationAccountId());
        }
    }
}