package com.finflow.transaction_service.payment.stripe;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Retry and circuit breaker policies for outbound Stripe calls, built from
 * {@code finflow.payments.stripe.resilience.*}.
 *
 * <p>Only {@link PaymentProviderException#isRetryable() retryable} failures
 * (network errors, 429, 5xx, 409 in-flight idempotent request) are retried
 * and counted by the breaker; client errors (400, 401, 402, 403) fail fast
 * and do not trip it. Retrying is safe because every call carries an
 * idempotency key. Metrics are exposed through Micrometer as
 * {@code resilience4j.circuitbreaker.*} and {@code resilience4j.retry.*}.
 *
 * <p>Each operation gets its own breaker instance (see
 * {@link StripeSdkCheckoutGateway}), so a failing bulk job cannot block
 * customer deposits.
 */
@Configuration
public class StripeResilienceConfig {

    @Bean
    public CircuitBreakerRegistry stripeCircuitBreakerRegistry(
            StripeProperties properties,
            MeterRegistry meterRegistry
    ) {
        StripeProperties.Resilience resilience = properties.resilience();

        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(resilience.slidingWindowSize())
                .minimumNumberOfCalls(resilience.minimumNumberOfCalls())
                .failureRateThreshold(resilience.failureRateThreshold())
                .waitDurationInOpenState(resilience.waitInOpen())
                .permittedNumberOfCallsInHalfOpenState(resilience.permittedCallsInHalfOpen())
                .recordException(StripeResilienceConfig::isRetryableFailure)
                .build();

        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);

        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry)
                .bindTo(meterRegistry);

        return registry;
    }

    @Bean
    public RetryRegistry stripeRetryRegistry(
            StripeProperties properties,
            MeterRegistry meterRegistry
    ) {
        StripeProperties.Resilience resilience = properties.resilience();

        RetryConfig config = RetryConfig.custom()
                .maxAttempts(resilience.maxAttempts())
                .intervalFunction(IntervalFunction.ofExponentialBackoff(
                        resilience.initialWait(), 2.0))
                .retryOnException(StripeResilienceConfig::isRetryableFailure)
                .failAfterMaxAttempts(false)
                .build();

        RetryRegistry registry = RetryRegistry.of(config);

        TaggedRetryMetrics.ofRetryRegistry(registry).bindTo(meterRegistry);

        return registry;
    }

    /**
     * {@code CallNotPermittedException} (open breaker) is not a
     * {@link PaymentProviderException}, so it is never retried.
     */
    static boolean isRetryableFailure(Throwable throwable) {
        return throwable instanceof PaymentProviderException exception
                && exception.isRetryable();
    }
}
