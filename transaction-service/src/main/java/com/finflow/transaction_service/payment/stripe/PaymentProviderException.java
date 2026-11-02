package com.finflow.transaction_service.payment.stripe;

/**
 * A call to the payment provider failed.
 *
 * <p>{@link #isRetryable()} separates transient failures (network errors,
 * rate limiting, provider 5xx, idempotent-request-in-flight conflicts) from
 * failures that will not succeed when repeated with the same parameters
 * (invalid request, authentication, idempotency key reuse, card errors).
 * The resilience layer retries and trips the circuit breaker only on the
 * former.
 */
public class PaymentProviderException extends RuntimeException {

    private final boolean retryable;
    private final Integer statusCode;

    public PaymentProviderException(
            String message,
            boolean retryable,
            Integer statusCode,
            Throwable cause
    ) {
        super(message, cause);
        this.retryable = retryable;
        this.statusCode = statusCode;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public Integer getStatusCode() {
        return statusCode;
    }
}
