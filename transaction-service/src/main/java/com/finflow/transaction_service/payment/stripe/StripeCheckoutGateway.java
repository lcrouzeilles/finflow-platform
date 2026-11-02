package com.finflow.transaction_service.payment.stripe;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Port to the payment provider. The only implementation talks to Stripe;
 * tests replace it with a mock so that no test ever reaches the network.
 */
public interface StripeCheckoutGateway {

    /**
     * Creates a hosted Checkout Session for a deposit.
     *
     * @throws PaymentProviderException when Stripe rejects the request or
     *                                  cannot be reached
     */
    CheckoutSession createCheckoutSession(CreateCheckoutSession command);

    /**
     * Lists the Checkout Sessions created in {@code [from, to]} (by the
     * provider's creation timestamp), following pagination.
     */
    List<ProviderCheckoutSession> listCheckoutSessions(Instant from, Instant to);

    /** Looks one session up; empty when the provider does not know it. */
    Optional<ProviderCheckoutSession> findCheckoutSession(String sessionId);
}
