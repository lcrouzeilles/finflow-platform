package com.finflow.transaction_service.payment.stripe;

import java.util.UUID;

/**
 * What the adapter needs to create a Checkout Session.
 *
 * @param depositId      local deposit id, sent as {@code client_reference_id}
 *                       and {@code metadata.depositId}
 * @param amountMinor    amount in the currency's minor unit (cents for USD)
 * @param currency       ISO 4217 code, upper case
 * @param idempotencyKey key forwarded to Stripe so that a retried request
 *                       returns the same session
 */
public record CreateCheckoutSession(
        UUID depositId,
        long amountMinor,
        String currency,
        String idempotencyKey
) {
}
