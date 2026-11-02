package com.finflow.transaction_service.payment.stripe;

/**
 * The payment provider could not be reached within the retry policy, or
 * the circuit breaker is open. Mapped to 503: the client should retry
 * later with the same Idempotency-Key.
 */
public class PaymentProviderUnavailableException extends PaymentProviderException {

    public PaymentProviderUnavailableException(String message, Throwable cause) {
        super(message, true, null, cause);
    }
}
