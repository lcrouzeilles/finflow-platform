package com.finflow.transaction_service.payment.webhook;

/**
 * A signature-verified Stripe event, reduced to what processing needs.
 *
 * @param eventId       {@code evt_...}, the deduplication key
 * @param type          e.g. {@code checkout.session.completed}
 * @param rawDataObject the {@code data.object} JSON exactly as received
 */
public record StripeWebhookEvent(
        String eventId,
        String type,
        String rawDataObject
) {
}
