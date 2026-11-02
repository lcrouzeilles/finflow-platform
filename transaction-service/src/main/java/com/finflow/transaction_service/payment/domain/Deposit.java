package com.finflow.transaction_service.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Getter;
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A deposit funded through Stripe Checkout.
 *
 * <p>Golden rule: the account balance is <strong>never</strong> credited when
 * the customer lands on the success URL. Only the webhook handler, after
 * verifying Stripe's signature and the {@code paid} status, moves a deposit
 * to {@link DepositStatus#COMPLETED} and writes the ledger entry. The
 * redirect is user experience, not truth.
 *
 * <p>The id is assigned by the service (deterministic from the owner and
 * the idempotency key) rather than generated, so the entity implements
 * {@link Persistable}: a new instance is always inserted, and a concurrent
 * duplicate surfaces as a constraint violation instead of silently merging.
 */
@Entity
@Getter
@Table(name = "deposits")
public class Deposit implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DepositStatus status;

    @Column(name = "stripe_session_id", length = 255)
    private String stripeSessionId;

    @Column(name = "stripe_payment_intent_id", length = 255)
    private String stripePaymentIntentId;

    @Column(name = "checkout_url", columnDefinition = "TEXT")
    private String checkoutUrl;

    @Column(name = "idempotency_key", nullable = false, length = 100, unique = true)
    private String idempotencyKey;

    @Column(name = "transaction_id")
    private UUID transactionId;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Transient
    private boolean persisted;

    protected Deposit() {
    }

    public Deposit(
            UUID id,
            UUID accountId,
            UUID ownerId,
            BigDecimal amount,
            String currency,
            String idempotencyKey,
            String stripeSessionId,
            String checkoutUrl,
            String stripePaymentIntentId
    ) {
        if (id == null) {
            throw new IllegalArgumentException("Deposit ID cannot be null");
        }
        if (accountId == null) {
            throw new IllegalArgumentException("Account ID cannot be null");
        }
        if (ownerId == null) {
            throw new IllegalArgumentException("Owner ID cannot be null");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException(
                    "Deposit amount must be greater than zero"
            );
        }
        if (currency == null || currency.length() != 3) {
            throw new IllegalArgumentException(
                    "Deposit currency must contain 3 characters"
            );
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency key cannot be blank");
        }
        if (idempotencyKey.length() > 100) {
            throw new IllegalArgumentException(
                    "Idempotency key cannot exceed 100 characters"
            );
        }
        if (stripeSessionId == null || stripeSessionId.isBlank()) {
            throw new IllegalArgumentException(
                    "Stripe session ID cannot be blank"
            );
        }

        Instant now = Instant.now();

        this.id = id;
        this.accountId = accountId;
        this.ownerId = ownerId;
        this.amount = amount;
        this.currency = currency;
        this.idempotencyKey = idempotencyKey;
        this.stripeSessionId = stripeSessionId;
        this.checkoutUrl = checkoutUrl;
        this.stripePaymentIntentId = stripePaymentIntentId;
        this.status = DepositStatus.PENDING;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public boolean isPending() {
        return status == DepositStatus.PENDING;
    }

    /**
     * Records the confirmed payment. Only the webhook handler calls this,
     * inside the same transaction that credits the account and writes the
     * ledger row.
     */
    public void markCompleted(UUID transactionId, String stripePaymentIntentId) {
        requirePending("completed");

        if (transactionId == null) {
            throw new IllegalArgumentException("Transaction ID cannot be null");
        }

        this.transactionId = transactionId;
        if (stripePaymentIntentId != null && !stripePaymentIntentId.isBlank()) {
            this.stripePaymentIntentId = stripePaymentIntentId;
        }
        this.status = DepositStatus.COMPLETED;
        this.updatedAt = Instant.now();
    }

    public void markExpired() {
        requirePending("expired");

        this.status = DepositStatus.EXPIRED;
        this.updatedAt = Instant.now();
    }

    public void markFailed(String reason) {
        requirePending("failed");

        this.status = DepositStatus.FAILED;
        this.failureReason = reason;
        this.updatedAt = Instant.now();
    }

    private void requirePending(String target) {
        if (status != DepositStatus.PENDING) {
            throw new IllegalStateException(
                    "Only pending deposits can be marked " + target
                            + " (current status: " + status + ")"
            );
        }
    }

    @Override
    public boolean isNew() {
        return !persisted;
    }

    @PostPersist
    @PostLoad
    void markPersisted() {
        this.persisted = true;
    }
}
