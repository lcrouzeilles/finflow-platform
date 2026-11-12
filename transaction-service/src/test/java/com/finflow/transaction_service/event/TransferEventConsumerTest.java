package com.finflow.transaction_service.event;

import com.finflow.transaction_service.config.CorrelationIdFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferEventConsumerTest {

    @Mock
    private TransferCompletedHandler handler;

    @InjectMocks
    private TransferEventConsumer consumer;

    private final TransferCompletedEvent event = new TransferCompletedEvent(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void shouldHandleTheEventWithTheCorrelationIdInTheMdc() {
        AtomicReference<String> seen = new AtomicReference<>();
        doAnswer(invocation -> {
            seen.set(MDC.get(CorrelationIdFilter.MDC_KEY));
            return true;
        }).when(handler).handle(event);

        consumer.consume(event, "req-123".getBytes(StandardCharsets.UTF_8));

        assertThat(seen.get()).isEqualTo("req-123");
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void shouldClearTheMdcWhenTheHandlerFails() {
        when(handler.handle(event)).thenThrow(new IllegalStateException("database down"));

        assertThatThrownBy(() -> consumer.consume(event, "req-123".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }
}
