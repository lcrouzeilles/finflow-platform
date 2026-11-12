
package com.finflow.transaction_service.domain.outbox;

import com.finflow.transaction_service.config.CorrelationIdFilter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import jakarta.persistence.*;
import lombok.Getter;
import org.slf4j.MDC;

import java.time.Instant;
import java.util.UUID;

@Getter
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    /** Upper bound of the exponential backoff between retries. */
    static final long MAX_BACKOFF_SECONDS = 300L;

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

    /**
     * Set when the event exhausted its retry budget. Terminal: the event is
     * no longer claimable and needs operator intervention.
     */
    @Column(name = "failed_at")
    private Instant failedAt;

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

    /** Correlation id of the originating request, if there was one. */
    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    /** W3C traceparent of the originating request, if it was traced. */
    @Column(name = "trace_parent", length = 55)
    private String traceParent;

    protected OutboxEvent() {
        // Required by JPA
    }

    public OutboxEvent(
            String aggregateType,
            UUID aggregateId,
            String eventType,
            String payload
    ) {
        this(UUID.randomUUID(), aggregateType, aggregateId, eventType, payload);
    }

    /**
     * Creates an event with a caller-supplied ID so the same ID can be
     * embedded in the payload (consumers deduplicate on it).
     */
    public OutboxEvent(
            UUID id,
            String aggregateType,
            UUID aggregateId,
            String eventType,
            String payload
    ) {
        if (id == null) {
            throw new IllegalArgumentException("Event ID cannot be null");
        }
        this.id = id;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.createdAt = Instant.now();
        this.attempts = 0;
        this.correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        this.traceParent = currentTraceParent();
    }

    /**
     * The current span as a W3C traceparent ({@code 00-traceId-spanId-flags}),
     * or {@code null} outside a span. Micrometer's OpenTelemetry bridge keeps
     * the current span in the OpenTelemetry context, so it is visible here.
     */
    private static String currentTraceParent() {
        SpanContext span = Span.current().getSpanContext();
        if (!span.isValid()) {
            return null;
        }
        return "00-" + span.getTraceId() + "-" + span.getSpanId()
                + "-" + span.getTraceFlags().asHex();
    }

    public void markPublished() {
        this.publishedAt = Instant.now();
        this.lastError = null;
        this.nextAttemptAt = null;
        clearClaim();
    }

    /**
     * Records a failed attempt and schedules the next one with exponential
     * backoff (1s, 2s, 4s, ... capped at {@value #MAX_BACKOFF_SECONDS}s).
     * Never becomes terminal; see {@link #markFailed(String, int)}.
     */
    public void markFailed(String error) {
        markFailed(error, Integer.MAX_VALUE);
    }

    /**
     * Records a failed attempt. When the number of attempts reaches
     * {@code maxAttempts} the event becomes terminal ({@link #getFailedAt()}
     * set, no next attempt) and is excluded from claiming.
     *
     * @return {@code true} if this failure exhausted the retry budget
     */
    public boolean markFailed(String error, int maxAttempts) {
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException(
                    "Max attempts must be positive"
            );
        }

        this.attempts++;
        this.lastError = error;
        clearClaim();

        if (this.attempts >= maxAttempts) {
            this.failedAt = Instant.now();
            this.nextAttemptAt = null;
            return true;
        }

        long delaySeconds = Math.min(
                MAX_BACKOFF_SECONDS,
                1L << Math.min(this.attempts - 1, 9)
        );

        this.nextAttemptAt = Instant.now().plusSeconds(delaySeconds);
        return false;
    }

    public boolean isExhausted() {
        return failedAt != null;
    }

    /**
     * Puts an exhausted event back in the queue with a fresh retry budget,
     * once an operator has fixed the cause. The last error is kept until
     * the next attempt replaces or clears it.
     */
    public void requeue() {
        if (!isExhausted()) {
            throw new IllegalStateException(
                    "Only an exhausted outbox event can be requeued: " + id
            );
        }
        this.failedAt = null;
        this.attempts = 0;
        this.nextAttemptAt = null;
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
