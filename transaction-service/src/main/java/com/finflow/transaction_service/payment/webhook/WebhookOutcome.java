package com.finflow.transaction_service.payment.webhook;

/** What the handler did with a delivery. Every value answers HTTP 200. */
public enum WebhookOutcome {
    /** First delivery of a handled type; the business effect was applied (or found already applied). */
    PROCESSED,
    /** The event id was seen before; nothing was done. */
    DUPLICATE,
    /** A type this service does not handle, or a handled type in a state that needs no action. */
    IGNORED
}
