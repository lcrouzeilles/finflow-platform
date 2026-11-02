package com.finflow.transaction_service.payment.reconciliation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * A difference between the provider's view and the ledger's view of a
 * deposit. Reported for a human to act on; never corrected automatically.
 */
@Entity
@Getter
@Table(name = "reconciliation_discrepancies")
public class ReconciliationDiscrepancy {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private DiscrepancyType type;

    @Column(name = "deposit_id")
    private UUID depositId;

    @Column(name = "stripe_session_id", length = 255)
    private String stripeSessionId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String detail;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    protected ReconciliationDiscrepancy() {
    }

    public ReconciliationDiscrepancy(
            DiscrepancyType type,
            UUID depositId,
            String stripeSessionId,
            String detail
    ) {
        if (type == null) {
            throw new IllegalArgumentException("Discrepancy type cannot be null");
        }
        if (detail == null || detail.isBlank()) {
            throw new IllegalArgumentException("Discrepancy detail cannot be blank");
        }

        this.type = type;
        this.depositId = depositId;
        this.stripeSessionId = stripeSessionId;
        this.detail = detail;
        this.detectedAt = Instant.now();
    }
}
