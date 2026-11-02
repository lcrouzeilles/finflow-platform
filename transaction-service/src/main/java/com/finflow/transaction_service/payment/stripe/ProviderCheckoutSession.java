package com.finflow.transaction_service.payment.stripe;

/**
 * A Checkout Session as the provider reports it, used by reconciliation.
 *
 * @param sessionId         {@code cs_...}
 * @param paymentIntentId   {@code pi_...}, may be null
 * @param status            {@code open}, {@code complete} or {@code expired}
 * @param paymentStatus     {@code paid}, {@code unpaid} or {@code no_payment_required}
 * @param amountTotal       in minor units, may be null
 * @param currency          lower case as Stripe sends it, may be null
 * @param clientReferenceId the local deposit id sent at creation, may be null
 */
public record ProviderCheckoutSession(
        String sessionId,
        String paymentIntentId,
        String status,
        String paymentStatus,
        Long amountTotal,
        String currency,
        String clientReferenceId
) {

    public boolean isPaid() {
        return "paid".equalsIgnoreCase(paymentStatus);
    }
}
