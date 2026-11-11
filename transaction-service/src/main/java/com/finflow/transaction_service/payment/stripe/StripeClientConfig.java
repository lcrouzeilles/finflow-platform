package com.finflow.transaction_service.payment.stripe;

import com.stripe.StripeClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the single {@link StripeClient} used by the payment adapter.
 *
 * <p>The SDK's own network retries are disabled on purpose: retries, backoff
 * and the circuit breaker are owned by the resilience layer around the
 * adapter, so that an outage is retried exactly once per policy instead of
 * {@code sdkRetries x policyRetries} times. Connect and read timeouts are
 * explicit because the SDK defaults (30 s / 80 s) would hold a request
 * thread for far too long while Stripe is unreachable.
 */
@Configuration
@EnableConfigurationProperties(StripeProperties.class)
public class StripeClientConfig {

    private static final Logger log =
            LoggerFactory.getLogger(StripeClientConfig.class);

    static final int SDK_NETWORK_RETRIES = 0;

    @Bean
    public StripeClient stripeClient(StripeProperties properties) {
        if (!properties.isConfigured()) {
            log.warn(
                    "Stripe secret key is not configured (STRIPE_SECRET_KEY); "
                            + "deposit creation will fail until it is set"
            );
        }

        if (!properties.isWebhookConfigured()) {
            log.warn(
                    "Stripe webhook secret is not configured "
                            + "(STRIPE_WEBHOOK_SECRET); incoming webhooks will be rejected"
            );
        }

        String apiKey = properties.isConfigured() ? properties.secretKey() : "";

        StripeClient.StripeClientBuilder builder = StripeClient.builder()
                .setApiKey(apiKey)
                .setConnectTimeout((int) properties.connectTimeout().toMillis())
                .setReadTimeout((int) properties.readTimeout().toMillis())
                .setMaxNetworkRetries(SDK_NETWORK_RETRIES);

        if (properties.hasApiBaseOverride()) {
            log.warn(
                    "Stripe API base overridden to {}; this is for local "
                            + "load testing only, no request reaches Stripe",
                    properties.apiBase()
            );
            builder.setApiBase(properties.apiBase());
        }

        return builder.build();
    }
}
