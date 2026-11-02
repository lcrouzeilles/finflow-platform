package com.finflow.transaction_service.payment.domain;

public enum DepositStatus {
    /** Checkout Session created, waiting for the webhook. */
    PENDING,
    /** Payment confirmed by the webhook and credited to the ledger. */
    COMPLETED,
    /** The Checkout Session expired unpaid. */
    EXPIRED,
    /** Terminal failure recorded by the service. */
    FAILED
}
