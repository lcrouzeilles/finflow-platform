package com.finflow.transaction_service.payment.stripe;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
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
        @NotNull Duration readTimeout,
        @DefaultValue @Valid Resilience resilience,
        String apiBase
) {

    public boolean isConfigured() {
        return secretKey != null && !secretKey.isBlank();
    }

    /**
     * True when {@code apiBase} points the client somewhere other than
     * Stripe, e.g. the simulated Stripe used by the load tests in perf/.
     */
    public boolean hasApiBaseOverride() {
        return apiBase != null && !apiBase.isBlank();
    }

    public boolean isWebhookConfigured() {
        return webhookSecret != null && !webhookSecret.isBlank();
    }

    /**
     * Retry and circuit breaker policy for outbound Stripe calls
     * ({@code finflow.payments.stripe.resilience.*}).
     *
     * @param maxAttempts              total attempts per call, including the first
     * @param initialWait              wait before the first retry; doubles on each retry
     * @param slidingWindowSize        number of recent calls the breaker evaluates
     * @param minimumNumberOfCalls     calls needed before the failure rate is evaluated
     * @param failureRateThreshold     percentage of failures that opens the breaker
     * @param waitInOpen               how long the breaker stays open before probing
     * @param permittedCallsInHalfOpen probe calls allowed while half-open
     */
    public record Resilience(
            @DefaultValue("3") @Min(1) int maxAttempts,
            @DefaultValue("200ms") @NotNull Duration initialWait,
            @DefaultValue("10") @Min(1) int slidingWindowSize,
            @DefaultValue("5") @Min(1) int minimumNumberOfCalls,
            @DefaultValue("50") @DecimalMin("1") @DecimalMax("100") float failureRateThreshold,
            @DefaultValue("30s") @NotNull Duration waitInOpen,
            @DefaultValue("2") @Min(1) int permittedCallsInHalfOpen
    ) {
    }
}
