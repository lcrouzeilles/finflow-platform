package com.finflow.transaction_service.repository;

import com.finflow.transaction_service.domain.outbox.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository
        extends JpaRepository<OutboxEvent, UUID> {
    @Query(value = """
    SELECT *
    FROM outbox_events
    WHERE published_at IS NULL
      AND (claim_until IS NULL OR claim_until < :now)
      AND (next_attempt_at IS NULL OR next_attempt_at <= :now)
    ORDER BY created_at
    LIMIT :batchSize
    FOR UPDATE SKIP LOCKED
    """, nativeQuery = true)
    List<OutboxEvent> findClaimableForUpdate(
            @Param("now") Instant now,
            @Param("batchSize") int batchSize
    );
}
