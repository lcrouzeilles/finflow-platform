package com.finflow.transaction_service.event;

import com.finflow.transaction_service.AbstractIntegrationTest;
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

/**
 * The eviction must be visible as soon as the committing call returns: the
 * listener evicts synchronously ({@code evictIfPresent}), otherwise this
 * assertion races with an asynchronous {@code DEL} and fails intermittently
 * (observed in CI).
 *
 * <p>The cache is seeded with {@code putIfAbsent} for the same reason:
 * {@code put} is also fire-and-forget, so its {@code SET} could land after
 * the eviction or not yet be visible to the assertions (also observed in CI).
 */
@SpringBootTest
@ActiveProfiles("test")
class TransferCacheListenerIntegrationTest extends AbstractIntegrationTest {

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
        accountsCache.putIfAbsent(sourceAccountId, "source");
        accountsCache.putIfAbsent(destinationAccountId, "destination");

        TransferCompletedEvent event =
                new TransferCompletedEvent(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
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
        accountsCache.putIfAbsent(sourceAccountId, "source");
        accountsCache.putIfAbsent(destinationAccountId, "destination");

        TransferCompletedEvent event =
                new TransferCompletedEvent(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
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