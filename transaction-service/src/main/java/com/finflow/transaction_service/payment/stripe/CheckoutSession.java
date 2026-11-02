package com.finflow.transaction_service.payment.stripe;

/**
 * The part of a Stripe Checkout Session the service keeps.
 *
 * @param sessionId       {@code cs_...}
 * @param url             hosted payment page the client is redirected to
 * @param paymentIntentId {@code pi_...}, may be null until Stripe creates it
 */
public record CheckoutSession(
        String sessionId,
        String url,
        String paymentIntentId
) {
}
