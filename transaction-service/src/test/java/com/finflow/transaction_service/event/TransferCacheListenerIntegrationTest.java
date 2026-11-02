package com.finflow.transaction_service.event;

import com.finflow.transaction_service.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

/**
 * DIAGNOSTIC VERSION (ci/cache-eviction-diagnosis): records every step of
 * the publish -> commit -> AFTER_COMMIT -> DEL path so the CI log tells us
 * which link is missing when the key survives the commit.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "logging.level.org.springframework.transaction=DEBUG",
        "logging.level.org.springframework.transaction.event=DEBUG",
        "logging.level.org.springframework.context.event=DEBUG",
        "logging.level.org.springframework.data.redis=DEBUG",
        "logging.level.org.springframework.cache=TRACE"
})
@Import(TransferCacheListenerIntegrationTest.EventRecorder.class)
class TransferCacheListenerIntegrationTest extends AbstractIntegrationTest {

    private static final Logger log =
            LoggerFactory.getLogger(TransferCacheListenerIntegrationTest.class);

    @TestConfiguration
    static class EventRecorder {

        final List<TransferCompletedEvent> immediate = new CopyOnWriteArrayList<>();
        final List<TransferCompletedEvent> afterCommit = new CopyOnWriteArrayList<>();

        @EventListener
        public void onPublish(TransferCompletedEvent event) {
            immediate.add(event);
            log.info("DIAG immediate listener: {} syncActive={} txActive={}",
                    event,
                    TransactionSynchronizationManager.isSynchronizationActive(),
                    TransactionSynchronizationManager.isActualTransactionActive());
        }

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        public void onAfterCommit(TransferCompletedEvent event) {
            afterCommit.add(event);
            log.info("DIAG after-commit listener: {}", event);
        }
    }

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private RedisConnectionFactory redisConnectionFactory;

    @Autowired
    private EventRecorder recorder;

    @MockitoSpyBean
    private TransferCacheListener transferCacheListener;

    @Test
    void shouldEvictCacheAfterTransactionCommits() {
        UUID sourceAccountId = UUID.randomUUID();
        UUID destinationAccountId = UUID.randomUUID();

        log.info("DIAG txManager={} ; cacheManager={} ; connectionFactory={}",
                transactionManager.getClass().getName(),
                cacheManager.getClass().getName(),
                redisConnectionFactory.getClass().getName());

        Cache accountsCache = cacheManager.getCache("accounts");

        assertNotNull(accountsCache);
        accountsCache.put(sourceAccountId, "source");
        accountsCache.put(destinationAccountId, "destination");

        log.info("DIAG after put: rawExists source={} destination={}",
                rawExists(sourceAccountId), rawExists(destinationAccountId));

        TransferCompletedEvent event =
                new TransferCompletedEvent(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        sourceAccountId,
                        destinationAccountId
                );

        TransactionTemplate transactionTemplate =
                new TransactionTemplate(transactionManager);

        transactionTemplate.executeWithoutResult(status -> {
            log.info("DIAG inside template: syncActive={} txActive={} txName={}",
                    TransactionSynchronizationManager.isSynchronizationActive(),
                    TransactionSynchronizationManager.isActualTransactionActive(),
                    TransactionSynchronizationManager.getCurrentTransactionName());
            eventPublisher.publishEvent(event);
        });

        boolean sourceRaw = rawExists(sourceAccountId);
        boolean destinationRaw = rawExists(destinationAccountId);
        Cache.ValueWrapper sourceCached = accountsCache.get(sourceAccountId);
        Cache.ValueWrapper destinationCached = accountsCache.get(destinationAccountId);

        log.info("DIAG after commit: immediate={} afterCommit={} rawExists source={} destination={} cacheGet source={} destination={}",
                recorder.immediate.size(), recorder.afterCommit.size(),
                sourceRaw, destinationRaw, sourceCached, destinationCached);

        assertAll(
                () -> assertTrue(recorder.immediate.contains(event), "immediate @EventListener invoked"),
                () -> assertTrue(recorder.afterCommit.contains(event), "AFTER_COMMIT recorder invoked"),
                () -> verify(transferCacheListener).handleTransferCompleted(event),
                () -> assertTrue(!sourceRaw, "raw key accounts::source still exists in Redis"),
                () -> assertTrue(!destinationRaw, "raw key accounts::destination still exists in Redis"),
                () -> assertNull(sourceCached, "cache.get(source)"),
                () -> assertNull(destinationCached, "cache.get(destination)")
        );
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

    private boolean rawExists(UUID accountId) {
        byte[] key = ("accounts::" + accountId).getBytes(StandardCharsets.UTF_8);
        try (RedisConnection connection = redisConnectionFactory.getConnection()) {
            Boolean exists = connection.keyCommands().exists(key);
            return Boolean.TRUE.equals(exists);
        }
    }
}
