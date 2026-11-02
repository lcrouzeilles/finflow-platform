package com.finflow.transaction_service.payment.reconciliation;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Persists one discrepancy in its own transaction, so that a unique
 * violation (the same discrepancy already recorded by an earlier run or by
 * the webhook handler) only aborts that insert and never the whole run.
 */
@Service
public class DiscrepancyRecorder {

    private final ReconciliationDiscrepancyRepository repository;

    public DiscrepancyRecorder(ReconciliationDiscrepancyRepository repository) {
        this.repository = repository;
    }

    /**
     * @return {@code true} when a new row was written, {@code false} when
     * the same (type, session) was already recorded
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean record(
            DiscrepancyType type,
            UUID depositId,
            String stripeSessionId,
            String detail
    ) {
        if (stripeSessionId != null
                && repository.existsByTypeAndStripeSessionId(type, stripeSessionId)) {
            return false;
        }

        repository.saveAndFlush(
                new ReconciliationDiscrepancy(type, depositId, stripeSessionId, detail)
        );

        return true;
    }
}
