package com.finflow.transaction_service.payment.stripe;

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
}
