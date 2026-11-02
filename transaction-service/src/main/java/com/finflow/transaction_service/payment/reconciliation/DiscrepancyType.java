package com.finflow.transaction_service.payment.reconciliation;

public enum DiscrepancyType {
    /** Stripe reports a paid session with no COMPLETED deposit locally (lost webhook). */
    PAID_WITHOUT_LOCAL_COMPLETION,
    /** A deposit is COMPLETED locally but Stripe has no payment for it. Must never happen. */
    COMPLETED_WITHOUT_PROVIDER_PAYMENT,
    /** Paid and completed, but the amounts or currencies differ. */
    AMOUNT_MISMATCH,
    /** A webhook referenced a Checkout Session this service never created. */
    UNKNOWN_SESSION
}
