package com.finflow.transaction_service.event;

import com.finflow.transaction_service.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.finflow.transaction_service.event.TransferCompletedHandler.CONSUMER;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The outbox relays events at least once, so Kafka can hand the consumer
 * the same event more than once, even concurrently (two consumers of the
 * group during a rebalance). Each event must be applied exactly once.
 */
@SpringBootTest
@ActiveProfiles("test")
class TransferCompletedHandlerIntegrationTest extends AbstractIntegrationTest {

    private static final int PARALLEL_DELIVERIES = 4;

    @Autowired
    private TransferCompletedHandler handler;

    @Autowired
    private ProcessedKafkaEventRepository processedEvents;

    private static TransferCompletedEvent newEvent() {
        return new TransferCompletedEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    }

    private boolean isClaimed(TransferCompletedEvent event) {
        return processedEvents.existsById(new ProcessedKafkaEvent.Key(CONSUMER, event.eventId()));
    }

    @Test
    void shouldIgnoreARedeliveredEvent() {
        TransferCompletedEvent event = newEvent();

        assertThat(handler.handle(event)).isTrue();
        assertThat(handler.handle(event)).isFalse();
        assertThat(isClaimed(event)).isTrue();
    }

    @Test
    void shouldApplyDistinctEvents() {
        TransferCompletedEvent first = newEvent();
        TransferCompletedEvent second = newEvent();

        assertThat(handler.handle(first)).isTrue();
        assertThat(handler.handle(second)).isTrue();
        assertThat(isClaimed(first)).isTrue();
        assertThat(isClaimed(second)).isTrue();
    }

    @Test
    void shouldApplyAnEventDeliveredInParallelExactlyOnce() throws Exception {
        TransferCompletedEvent event = newEvent();

        ExecutorService executor = Executors.newFixedThreadPool(PARALLEL_DELIVERIES);
        CountDownLatch ready = new CountDownLatch(PARALLEL_DELIVERIES);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < PARALLEL_DELIVERIES; i++) {
                Callable<Boolean> task = () -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to start");
                    }
                    return handler.handle(event);
                };
                futures.add(executor.submit(task));
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).as("all tasks ready").isTrue();
            start.countDown();

            List<Boolean> applied = new ArrayList<>();
            for (Future<Boolean> future : futures) {
                // No delivery may fail: duplicates are acknowledged.
                applied.add(future.get(30, TimeUnit.SECONDS));
            }

            assertThat(applied).containsOnlyOnce(true);
            assertThat(isClaimed(event)).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }
}
