package com.finflow.transaction_service.payment.stripe;

import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.checkout.SessionCreateParams;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Stripe implementation of the checkout port, on top of the official SDK.
 *
 * <p>Amounts arrive already converted to minor units; this class never does
 * arithmetic on money. The local deposit id travels in
 * {@code client_reference_id} and {@code metadata.depositId} so the webhook
 * and the reconciliation job can correlate the session with the ledger.
 */
@Component
public class StripeSdkCheckoutGateway implements StripeCheckoutGateway {

    static final String PRODUCT_NAME = "FinFlow deposit";
    static final String METADATA_DEPOSIT_ID = "depositId";
    static final Duration SESSION_LIFETIME = Duration.ofHours(1);

    private final StripeClient client;
    private final StripeProperties properties;

    public StripeSdkCheckoutGateway(
            StripeClient client,
            StripeProperties properties
    ) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public CheckoutSession createCheckoutSession(CreateCheckoutSession command) {
        SessionCreateParams params = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setSuccessUrl(properties.successUrl())
                .setCancelUrl(properties.cancelUrl())
                .setClientReferenceId(command.depositId().toString())
                .putMetadata(METADATA_DEPOSIT_ID, command.depositId().toString())
                .setExpiresAt(Instant.now().plus(SESSION_LIFETIME).getEpochSecond())
                .addLineItem(
                        SessionCreateParams.LineItem.builder()
                                .setQuantity(1L)
                                .setPriceData(
                                        SessionCreateParams.LineItem.PriceData.builder()
                                                .setCurrency(command.currency().toLowerCase())
                                                .setUnitAmount(command.amountMinor())
                                                .setProductData(
                                                        SessionCreateParams.LineItem.PriceData.ProductData
                                                                .builder()
                                                                .setName(PRODUCT_NAME)
                                                                .build()
                                                )
                                                .build()
                                )
                                .build()
                )
                .build();

        RequestOptions options = RequestOptions.builder()
                .setIdempotencyKey(command.idempotencyKey())
                .build();

        try {
            Session session = client.v1().checkout().sessions().create(params, options);

            return new CheckoutSession(
                    session.getId(),
                    session.getUrl(),
                    session.getPaymentIntent()
            );
        } catch (StripeException exception) {
            throw StripeExceptions.translate(exception);
        }
    }
}
