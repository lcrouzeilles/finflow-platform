package com.finflow.transaction_service.event;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * A Kafka event that a consumer has applied. Rows are only ever inserted
 * through {@link ProcessedKafkaEventRepository#insertIfAbsent}; the entity
 * exists so the table is part of the JPA model and schema validation.
 */
@Entity
@Getter
@Table(name = "processed_kafka_events")
public class ProcessedKafkaEvent {

    @EmbeddedId
    private Key key;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ProcessedKafkaEvent() {
    }

    @Embeddable
    public record Key(
            @Column(name = "consumer", length = 100) String consumer,
            @Column(name = "event_id") UUID eventId
    ) implements Serializable {
    }
}
