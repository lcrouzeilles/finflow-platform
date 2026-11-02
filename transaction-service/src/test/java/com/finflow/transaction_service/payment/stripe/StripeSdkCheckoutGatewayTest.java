package com.finflow.transaction_service.payment.stripe;

import com.stripe.StripeClient;
import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.ApiException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.model.checkout.Session;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real SDK client and real resilience policies over a fake transport: no
 * network, deterministic failures.
 */
class StripeSdkCheckoutGatewayTest {

    private static final int MAX_ATTEMPTS = 3;

    private FakeStripeTransport transport;
    private CircuitBreakerRegistry circuitBreakers;
    private StripeSdkCheckoutGateway gateway;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        transport = new FakeStripeTransport();
        meterRegistry = new SimpleMeterRegistry();

        StripeProperties properties = new StripeProperties(
                "sk_test_dummy",
                "whsec_dummy",
                "http://localhost/success",
                "http://localhost/cancel",
                Duration.ofSeconds(3),
                Duration.ofSeconds(10),
                new StripeProperties.Resilience(
                        MAX_ATTEMPTS,
                        Duration.ofMillis(1),
                        4,      // sliding window
                        4,      // minimum calls before the failure rate counts
                        50f,
                        Duration.ofMinutes(5),
                        1
                )
        );

        StripeResilienceConfig config = new StripeResilienceConfig();
        circuitBreakers = config.stripeCircuitBreakerRegistry(properties, meterRegistry);
        RetryRegistry retries = config.stripeRetryRegistry(properties, meterRegistry);

        gateway = new StripeSdkCheckoutGateway(
                new StripeClient(transport),
                properties,
                circuitBreakers,
                retries
        );
    }

    @Test
    void shouldCreateSessionWithDepositReferenceAndIdempotencyKey() {
        Session session = new Session();
        session.setId("cs_test_ok");
        session.setUrl("https://checkout.stripe.com/c/pay/cs_test_ok");
        session.setPaymentIntent("pi_test_ok");
        transport.respondWith(session);

        UUID depositId = UUID.randomUUID();

        CheckoutSession result = gateway.createCheckoutSession(
                new CreateCheckoutSession(depositId, 1050L, "USD", "deposit:owner:key"));

        assertThat(result.sessionId()).isEqualTo("cs_test_ok");
        assertThat(result.url()).isEqualTo("https://checkout.stripe.com/c/pay/cs_test_ok");
        assertThat(result.paymentIntentId()).isEqualTo("pi_test_ok");

        assertThat(transport.calls.get()).isEqualTo(1);
        assertThat(transport.options.get(0).getIdempotencyKey()).isEqualTo("deposit:owner:key");

        Map<String, Object> params = transport.params.get(0);
        assertThat(params.get("mode")).isEqualTo("payment");
        assertThat(params.get("client_reference_id")).isEqualTo(depositId.toString());
        assertThat(params.get("metadata")).isEqualTo(Map.of("depositId", depositId.toString()));
        assertThat(params.get("success_url")).isEqualTo("http://localhost/success");
        assertThat(params).containsKey("line_items");
        assertThat(params.toString()).contains("unit_amount=1050").contains("currency=usd");
    }

    @Test
    void shouldRetryNetworkFailuresThenReport503() {
        transport.failWith(() -> new ApiConnectionException("connection reset"));

        assertThatThrownBy(() -> gateway.createCheckoutSession(command()))
                .isInstanceOf(PaymentProviderUnavailableException.class);

        assertThat(transport.calls.get()).isEqualTo(MAX_ATTEMPTS);
        assertThat(meterRegistry.get("resilience4j.retry.calls")
                .tag("kind", "failed_with_retry").functionCounter().count())
                .isEqualTo(1.0);
    }

    @Test
    void shouldRetryProviderConflictsAndServerErrors() {
        transport.failWith(() -> new ApiException(
                "idempotent request in flight", "req_1", "lock_timeout", 409, null));

        assertThatThrownBy(() -> gateway.createCheckoutSession(command()))
                .isInstanceOf(PaymentProviderUnavailableException.class);

        assertThat(transport.calls.get()).isEqualTo(MAX_ATTEMPTS);
    }

    @Test
    void shouldNotRetryClientErrorsAndKeepThem502() {
        transport.failWith(() -> new InvalidRequestException(
                "Invalid currency", "currency", "req_2", "parameter_invalid", 400, null));

        assertThatThrownBy(() -> gateway.createCheckoutSession(command()))
                .isInstanceOf(PaymentProviderException.class)
                .isNotInstanceOf(PaymentProviderUnavailableException.class)
                .matches(exception -> !((PaymentProviderException) exception).isRetryable())
                .matches(exception -> ((PaymentProviderException) exception).getStatusCode() == 400);

        assertThat(transport.calls.get()).isEqualTo(1);
        assertThat(breaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void shouldOpenTheCircuitAndFailFastWithoutCallingStripe() {
        transport.failWith(() -> new ApiConnectionException("down"));

        // First call: 3 attempts, 3 recorded failures, below the minimum of 4.
        assertThatThrownBy(() -> gateway.createCheckoutSession(command()))
                .isInstanceOf(PaymentProviderUnavailableException.class);
        assertThat(breaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(transport.calls.get()).isEqualTo(3);

        // Second call: the 4th failure reaches the minimum at 100%: OPEN, and
        // the breaker rejects the remaining attempts of that same call.
        assertThatThrownBy(() -> gateway.createCheckoutSession(command()))
                .isInstanceOf(PaymentProviderUnavailableException.class);
        assertThat(breaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(transport.calls.get()).isEqualTo(4);

        int callsBefore = transport.calls.get();

        assertThatThrownBy(() -> gateway.createCheckoutSession(command()))
                .isInstanceOf(PaymentProviderUnavailableException.class)
                .hasMessageContaining("circuit open");

        assertThat(transport.calls.get()).isEqualTo(callsBefore);
        assertThat(meterRegistry.get("resilience4j.circuitbreaker.not.permitted.calls")
                .tag("name", StripeSdkCheckoutGateway.CHECKOUT_POLICY)
                .counter().count())
                .isGreaterThanOrEqualTo(1.0);
    }

    @Test
    void clientErrorsDoNotTripTheCircuit() {
        transport.failWith(() -> new InvalidRequestException(
                "bad", "amount", "req_3", "parameter_invalid", 400, null));

        for (int i = 0; i < 6; i++) {
            assertThatThrownBy(() -> gateway.createCheckoutSession(command()))
                    .isInstanceOf(PaymentProviderException.class);
        }

        assertThat(breaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(transport.calls.get()).isEqualTo(6);
    }

    private CircuitBreaker breaker() {
        return circuitBreakers.circuitBreaker(StripeSdkCheckoutGateway.CHECKOUT_POLICY);
    }

    private static CreateCheckoutSession command() {
        return new CreateCheckoutSession(UUID.randomUUID(), 1000L, "USD", "deposit:o:" + UUID.randomUUID());
    }
}
