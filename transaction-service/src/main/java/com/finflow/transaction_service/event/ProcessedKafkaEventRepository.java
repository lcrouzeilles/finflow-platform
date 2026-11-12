package com.finflow.transaction_service.event;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

public interface ProcessedKafkaEventRepository
        extends JpaRepository<ProcessedKafkaEvent, ProcessedKafkaEvent.Key> {

    /**
     * Claims an event for a consumer. Returns {@code 1} when this call
     * inserted the row (first delivery) and {@code 0} when it already
     * existed (duplicate). Must be the first statement of the consumer's
     * transaction so that the claim and the effect commit or roll back
     * together.
     */
    @Modifying
    @Query(
            value = """
                    INSERT INTO processed_kafka_events (consumer, event_id, processed_at)
                    VALUES (:consumer, :eventId, :processedAt)
                    ON CONFLICT (consumer, event_id) DO NOTHING
                    """,
            nativeQuery = true
    )
    int insertIfAbsent(
            @Param("consumer") String consumer,
            @Param("eventId") UUID eventId,
            @Param("processedAt") Instant processedAt
    );

    /** Deletes the claims older than {@code cutoff}; returns how many. */
    @Transactional
    @Modifying
    @Query("DELETE FROM ProcessedKafkaEvent e WHERE e.processedAt < :cutoff")
    int deleteProcessedBefore(@Param("cutoff") Instant cutoff);
}
