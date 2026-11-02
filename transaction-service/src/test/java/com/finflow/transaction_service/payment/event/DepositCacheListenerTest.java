package com.finflow.transaction_service.payment.event;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.math.BigDecimal;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DepositCacheListenerTest {

    @Mock
    private CacheManager cacheManager;

    @Mock
    private Cache accountsCache;

    @Test
    void shouldEvictCreditedAccountSynchronously() {
        UUID accountId = UUID.randomUUID();

        when(cacheManager.getCache("accounts")).thenReturn(accountsCache);

        new DepositCacheListener(cacheManager).handleDepositCompleted(event(accountId));

        verify(accountsCache).evictIfPresent(accountId);
    }

    @Test
    void shouldIgnoreMissingCache() {
        when(cacheManager.getCache("accounts")).thenReturn(null);

        new DepositCacheListener(cacheManager).handleDepositCompleted(event(UUID.randomUUID()));

        verifyNoInteractions(accountsCache);
    }

    private static DepositCompletedEvent event(UUID accountId) {
        return new DepositCompletedEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                accountId, new BigDecimal("10.00"), "USD");
    }
}
