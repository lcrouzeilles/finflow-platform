
package com.finflow.transaction_service.domain.outbox;

import jakarta.persistence.*;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, length = 100)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false, length = 150)
    private String eventType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "claimed_by", length = 200)
    private String claimedBy;

    @Column(name = "claim_until")
    private Instant claimUntil;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    protected OutboxEvent() {
        // Required by JPA
    }

    public OutboxEvent(
            String aggregateType,
            UUID aggregateId,
            String eventType,
            String payload
    ) {
        this.id = UUID.randomUUID();
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.createdAt = Instant.now();
        this.attempts = 0;
    }

    public void markPublished() {
        this.publishedAt = Instant.now();
        this.lastError = null;
        this.nextAttemptAt = null;
        clearClaim();
    }

    public void markFailed(String error) {
        this.attempts++;

        long delaySeconds = Math.min(
                300L,
                1L << Math.min(this.attempts - 1, 9)
        );

        this.nextAttemptAt = Instant.now().plusSeconds(delaySeconds);
        this.lastError = error;
        clearClaim();
    }

    public void claim(String workerId, Instant until) {
        this.claimedBy = workerId;
        this.claimUntil = until;
    }

    public void clearClaim() {
        this.claimedBy = null;
        this.claimUntil = null;
    }

}
