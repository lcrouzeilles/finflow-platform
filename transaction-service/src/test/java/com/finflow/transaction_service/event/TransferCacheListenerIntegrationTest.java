package com.finflow.transaction_service.event;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@SpringBootTest
@ActiveProfiles("test")
class TransferCacheListenerIntegrationTest {

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private CacheManager cacheManager;

    @Test
    void shouldEvictCacheAfterTransactionCommits() {
        UUID sourceAccountId = UUID.randomUUID();
        UUID destinationAccountId = UUID.randomUUID();

        Cache accountsCache = cacheManager.getCache("accounts");

        assertNotNull(accountsCache);
        accountsCache.put(sourceAccountId, "source");
        accountsCache.put(destinationAccountId, "destination");

        TransferCompletedEvent event =
                new TransferCompletedEvent(
                        sourceAccountId,
                        destinationAccountId
                );

        TransactionTemplate transactionTemplate =
                new TransactionTemplate(transactionManager);

        transactionTemplate.executeWithoutResult(status ->
                eventPublisher.publishEvent(event)
        );

        assertNull(accountsCache.get(sourceAccountId));
        assertNull(accountsCache.get(destinationAccountId));
    }

    @Test
    void shouldNotEvictCacheWhenTransactionRollsBack() {
        UUID sourceAccountId = UUID.randomUUID();
        UUID destinationAccountId = UUID.randomUUID();

        Cache accountsCache = cacheManager.getCache("accounts");

        assertNotNull(accountsCache);
        accountsCache.put(sourceAccountId, "source");
        accountsCache.put(destinationAccountId, "destination");

        TransferCompletedEvent event =
                new TransferCompletedEvent(
                        sourceAccountId,
                        destinationAccountId
                );

        TransactionTemplate transactionTemplate =
                new TransactionTemplate(transactionManager);

        try {
            transactionTemplate.executeWithoutResult(status -> {
                eventPublisher.publishEvent(event);

                status.setRollbackOnly();
            });
        } catch (Exception ignored) {
            // No exception is expected from setRollbackOnly().
        }

        assertNotNull(accountsCache.get(sourceAccountId));
        assertNotNull(accountsCache.get(destinationAccountId));
    }
}