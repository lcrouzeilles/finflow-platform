package com.finflow.transaction_service.service;

import com.finflow.transaction_service.AbstractIntegrationTest;
import com.finflow.transaction_service.domain.outbox.OutboxEvent;
import com.finflow.transaction_service.exception.OutboxEventNotFoundException;
import com.finflow.transaction_service.exception.OutboxEventNotRetryableException;
import com.finflow.transaction_service.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class OutboxAdminServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private OutboxAdminService adminService;

    @Autowired
    private OutboxEventRepository repository;

    @BeforeEach
    void cleanOutbox() {
        repository.deleteAll();
    }

    private OutboxEvent exhaustedEvent(String error) {
        OutboxEvent event = new OutboxEvent(
                "TRANSACTION", UUID.randomUUID(), "TRANSFER_COMPLETED", "{}");
        event.markFailed(error, 1);
        return repository.saveAndFlush(event);
    }

    @Test
    void shouldListExhaustedEventsOldestFailureFirst() {
        OutboxEvent first = exhaustedEvent("first");
        OutboxEvent second = exhaustedEvent("second");
        repository.saveAndFlush(new OutboxEvent(
                "TRANSACTION", UUID.randomUUID(), "TRANSFER_COMPLETED", "{}"));

        assertThat(adminService.findExhausted(10))
                .extracting(OutboxEvent::getId)
                .containsExactly(first.getId(), second.getId());
        assertThat(adminService.findExhausted(1)).hasSize(1);
    }

    @Test
    void shouldMakeARetriedEventClaimableAgain() {
        OutboxEvent event = exhaustedEvent("Kafka unavailable");

        adminService.retry(event.getId(), "operator-1");

        OutboxEvent reloaded = repository.findById(event.getId()).orElseThrow();
        assertThat(reloaded.isExhausted()).isFalse();
        assertThat(reloaded.getAttempts()).isZero();
        assertThat(adminService.findExhausted(10)).isEmpty();
        assertThat(repository.countByPublishedAtIsNullAndFailedAtIsNull()).isEqualTo(1);

        List<OutboxEvent> claimable = repository.findClaimableForUpdate(Instant.now(), 10);
        assertThat(claimable).extracting(OutboxEvent::getId).containsExactly(event.getId());
    }

    @Test
    void shouldRejectRetryingAPendingEvent() {
        OutboxEvent pending = repository.saveAndFlush(new OutboxEvent(
                "TRANSACTION", UUID.randomUUID(), "TRANSFER_COMPLETED", "{}"));

        assertThatThrownBy(() -> adminService.retry(pending.getId(), "operator-1"))
                .isInstanceOf(OutboxEventNotRetryableException.class);
    }

    @Test
    void shouldReportAnUnknownEvent() {
        assertThatThrownBy(() -> adminService.retry(UUID.randomUUID(), "operator-1"))
                .isInstanceOf(OutboxEventNotFoundException.class);
    }
}
