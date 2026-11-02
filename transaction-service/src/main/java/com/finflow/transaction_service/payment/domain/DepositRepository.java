package com.finflow.transaction_service.payment.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DepositRepository extends JpaRepository<Deposit, UUID> {

    Optional<Deposit> findByIdempotencyKey(String idempotencyKey);

    Optional<Deposit> findByStripeSessionId(String stripeSessionId);

    /**
     * Row lock for the webhook handler: completing and expiring the same
     * session serialize on the deposit row.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM Deposit d WHERE d.stripeSessionId = :stripeSessionId")
    Optional<Deposit> findByStripeSessionIdForUpdate(
            @Param("stripeSessionId") String stripeSessionId
    );

    Optional<Deposit> findByIdAndOwnerId(UUID id, UUID ownerId);

    List<Deposit> findAllByCreatedAtBetween(Instant from, Instant to);
}
