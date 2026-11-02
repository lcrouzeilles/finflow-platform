package com.finflow.transaction_service.payment.reconciliation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReconciliationDiscrepancyRepository
        extends JpaRepository<ReconciliationDiscrepancy, UUID> {

    boolean existsByTypeAndStripeSessionId(DiscrepancyType type, String stripeSessionId);

    List<ReconciliationDiscrepancy> findAllByStripeSessionId(String stripeSessionId);

    long countByType(DiscrepancyType type);
}
