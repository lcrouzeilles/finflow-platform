package com.finflow.transaction_service.payment.webhook;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Instant;

/**
 * A Stripe event id that has been applied (or deliberately ignored). Rows
 * are only ever inserted through
 * {@link ProcessedWebhookEventRepository#insertIfAbsent}; the entity exists
 * so the table is part of the JPA model and schema validation.
 */
@Entity
@Getter
@Table(name = "processed_webhook_events")
public class ProcessedWebhookEvent {

    @Id
    @Column(name = "event_id", length = 255)
    private String eventId;

    @Column(nullable = false, length = 100)
    private String type;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected ProcessedWebhookEvent() {
    }
}
