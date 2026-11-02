package com.finflow.transaction_service.payment.webhook;

import com.stripe.net.Webhook;

import java.time.Instant;
import java.util.UUID;

/**
 * Builds Stripe-shaped webhook payloads and valid signatures for tests.
 * The signing secret must match {@code finflow.payments.stripe.webhook-secret}
 * of the test configuration; it is not a real Stripe secret.
 */
public final class StripeWebhookTestSupport {

    public static final String TEST_WEBHOOK_SECRET = "whsec_test_secret_for_signing_only";

    private StripeWebhookTestSupport() {
    }

    public static String eventId() {
        return "evt_" + UUID.randomUUID().toString().replace("-", "");
    }

    /** The {@code data.object} of a Checkout Session as Stripe sends it. */
    public static String checkoutSessionObject(
            String sessionId,
            String status,
            String paymentStatus,
            Long amountTotal,
            String currency,
            String depositId,
            String paymentIntentId
    ) {
        String amount = amountTotal == null ? "null" : amountTotal.toString();
        String currencyJson = currency == null ? "null" : "\"" + currency + "\"";
        String paymentIntentJson = paymentIntentId == null ? "null" : "\"" + paymentIntentId + "\"";
        String reference = depositId == null ? "null" : "\"" + depositId + "\"";
        String metadata = depositId == null ? "{}" : "{\"depositId\": \"" + depositId + "\"}";

        return """
                {
                  "id": "%s",
                  "object": "checkout.session",
                  "amount_total": %s,
                  "currency": %s,
                  "client_reference_id": %s,
                  "metadata": %s,
                  "mode": "payment",
                  "payment_intent": %s,
                  "payment_status": "%s",
                  "status": "%s"
                }
                """.formatted(sessionId, amount, currencyJson, reference, metadata,
                paymentIntentJson, paymentStatus, status);
    }

    public static String paidSessionObject(String sessionId, long amountTotal, String currency, String depositId) {
        return checkoutSessionObject(sessionId, "complete", "paid", amountTotal, currency, depositId, "pi_" + sessionId);
    }

    /** A full event envelope, as delivered to the endpoint. */
    public static String envelope(String eventId, String type, String dataObject) {
        return """
                {
                  "id": "%s",
                  "object": "event",
                  "api_version": "2026-10-28.endive",
                  "created": %d,
                  "livemode": false,
                  "pending_webhooks": 1,
                  "type": "%s",
                  "data": {
                    "object": %s
                  }
                }
                """.formatted(eventId, Instant.now().getEpochSecond(), type, dataObject);
    }

    public static String sign(String payload) {
        return sign(payload, TEST_WEBHOOK_SECRET);
    }

    public static String sign(String payload, String secret) {
        try {
            return Webhook.Signature.generateSignatureHeader(payload, secret);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    public static String signAt(String payload, long timestampSeconds) {
        try {
            return Webhook.Signature.generateSignatureHeader(
                    payload, TEST_WEBHOOK_SECRET, timestampSeconds);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
