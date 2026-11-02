package com.finflow.transaction_service.payment.stripe;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Stripe settings. The secrets come exclusively from environment variables
 * ({@code STRIPE_SECRET_KEY}, {@code STRIPE_WEBHOOK_SECRET}); they are never
 * written to a properties file or committed. A blank secret key is allowed so
 * that the application (and every test context) can start without Stripe
 * credentials; deposit creation fails until the key is configured.
 */
@ConfigurationProperties(prefix = "finflow.payments.stripe")
@Validated
public record StripeProperties(
        String secretKey,
        String webhookSecret,
        @NotBlank String successUrl,
        @NotBlank String cancelUrl,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout
) {

    public boolean isConfigured() {
        return secretKey != null && !secretKey.isBlank();
    }

    public boolean isWebhookConfigured() {
        return webhookSecret != null && !webhookSecret.isBlank();
    }
}
