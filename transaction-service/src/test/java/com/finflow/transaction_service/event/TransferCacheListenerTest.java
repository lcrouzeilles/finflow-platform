package com.finflow.transaction_service.event;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.util.UUID;

import static org.mockito.Mockito.*;

class TransferCacheListenerTest {

    private CacheManager cacheManager;
    private Cache accountsCache;
    private TransferCacheListener listener;

    @BeforeEach
    void setUp() {
        cacheManager = mock(CacheManager.class);
        accountsCache = mock(Cache.class);

        when(cacheManager.getCache("accounts"))
                .thenReturn(accountsCache);

        listener = new TransferCacheListener(cacheManager);
    }

    @Test
    void shouldEvictSourceAndDestinationAccounts() {
        UUID sourceAccountId = UUID.randomUUID();
        UUID destinationAccountId = UUID.randomUUID();

        TransferCompletedEvent event =
                new TransferCompletedEvent(
                        sourceAccountId,
                        destinationAccountId
                );

        listener.handleTransferCompleted(event);

        verify(accountsCache).evict(sourceAccountId);
        verify(accountsCache).evict(destinationAccountId);
    }

    @Test
    void shouldDoNothingWhenAccountsCacheDoesNotExist() {
        when(cacheManager.getCache("accounts"))
                .thenReturn(null);

        TransferCompletedEvent event =
                new TransferCompletedEvent(
                        UUID.randomUUID(),
                        UUID.randomUUID()
                );

        listener.handleTransferCompleted(event);

        verifyNoInteractions(accountsCache);
    }
}
