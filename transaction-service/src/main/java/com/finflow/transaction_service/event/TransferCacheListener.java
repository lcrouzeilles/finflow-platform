package com.finflow.transaction_service.event;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

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
            cache.evict(event.sourceAccountId());
            cache.evict(event.destinationAccountId());
        }
    }
}