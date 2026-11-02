package com.finflow.transaction_service.payment.stripe;

import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.ApiException;
import com.stripe.exception.RateLimitException;
import com.stripe.exception.StripeException;

/**
 * Maps the SDK's checked exceptions to {@link PaymentProviderException}.
 */
final class StripeExceptions {

    private StripeExceptions() {
    }

    static PaymentProviderException translate(StripeException exception) {
        Integer status = exception.getStatusCode();

        boolean retryable = isRetryable(exception, status);

        String message = retryable
                ? "Payment provider is unavailable"
                : "Payment provider rejected the request";

        return new PaymentProviderException(
                message + ": " + safeMessage(exception),
                retryable,
                status,
                exception
        );
    }

    /**
     * Network failures, rate limiting, provider errors (5xx) and Stripe's
     * 409 "a request with this idempotency key is in flight" are worth
     * retrying with the same idempotency key. Everything else (400 invalid
     * request, 401/403, 402 card errors, idempotency key reused with other
     * parameters) will fail again and must not be retried.
     */
    static boolean isRetryable(StripeException exception, Integer status) {
        if (exception instanceof ApiConnectionException
                || exception instanceof RateLimitException) {
            return true;
        }

        if (exception instanceof ApiException && status != null) {
            return status >= 500 || status == 409;
        }

        return false;
    }

    private static String safeMessage(StripeException exception) {
        String message = exception.getMessage();

        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }

        return message;
    }
}
