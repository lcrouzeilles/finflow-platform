package com.finflow.transaction_service.payment.webhook;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The fields of a Checkout Session the webhook handler needs, read from the
 * raw {@code data.object} JSON. Parsing the raw JSON instead of the SDK's
 * typed model keeps the handler independent of the API version pinned by
 * the SDK (the typed deserializer returns nothing when the event's
 * {@code api_version} differs).
 *
 * @param amountTotal in minor units, may be null for unpaid sessions
 * @param currency    lower case as Stripe sends it, may be null
 */
public record CheckoutSessionPayload(
        String id,
        String status,
        String paymentStatus,
        String clientReferenceId,
        String depositIdMetadata,
        Long amountTotal,
        String currency,
        String paymentIntentId
) {

    public static final String PAYMENT_STATUS_PAID = "paid";

    public boolean isPaid() {
        return PAYMENT_STATUS_PAID.equalsIgnoreCase(paymentStatus);
    }

    public static CheckoutSessionPayload from(JsonNode object) {
        if (object == null || object.isNull() || object.isMissingNode()) {
            throw new IllegalArgumentException("Webhook payload has no data.object");
        }

        return new CheckoutSessionPayload(
                text(object, "id"),
                text(object, "status"),
                text(object, "payment_status"),
                text(object, "client_reference_id"),
                text(object.path("metadata"), "depositId"),
                number(object, "amount_total"),
                text(object, "currency"),
                idOf(object.path("payment_intent"))
        );
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : null;
    }

    private static Long number(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.asLong() : null;
    }

    /** Expandable reference: either the id string or an object with an id. */
    private static String idOf(JsonNode node) {
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isObject()) {
            return text(node, "id");
        }
        return null;
    }
}
