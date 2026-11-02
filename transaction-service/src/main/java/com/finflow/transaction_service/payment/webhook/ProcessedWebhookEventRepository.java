package com.finflow.transaction_service.payment.webhook;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface ProcessedWebhookEventRepository
        extends JpaRepository<ProcessedWebhookEvent, String> {

    /**
     * Claims an event id. Returns {@code 1} when this call inserted the row
     * (first delivery) and {@code 0} when it already existed (duplicate).
     * Must be the first statement of the processing transaction so that
     * the claim and the business effect commit or roll back together.
     */
    @Modifying
    @Query(
            value = """
                    INSERT INTO processed_webhook_events (event_id, type, received_at)
                    VALUES (:eventId, :type, :receivedAt)
                    ON CONFLICT (event_id) DO NOTHING
                    """,
            nativeQuery = true
    )
    int insertIfAbsent(
            @Param("eventId") String eventId,
            @Param("type") String type,
            @Param("receivedAt") Instant receivedAt
    );
}
