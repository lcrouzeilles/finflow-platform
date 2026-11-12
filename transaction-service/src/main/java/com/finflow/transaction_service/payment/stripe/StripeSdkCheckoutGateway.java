package com.finflow.transaction_service.payment.stripe;

import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.checkout.SessionCreateParams;
import com.stripe.param.checkout.SessionListParams;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.functions.CheckedSupplier;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Stripe implementation of the checkout port, on top of the official SDK,
 * wrapped in a retry and a circuit breaker.
 *
 * <p>Amounts arrive already converted to minor units; this class never does
 * arithmetic on money. The local deposit id travels in
 * {@code client_reference_id} and {@code metadata.depositId} so the webhook
 * and the reconciliation job can correlate the session with the ledger.
 *
 * <p>Resilience: the SDK's own retries are disabled (see
 * {@link StripeClientConfig}); every attempt here goes through the breaker
 * first and the retry policy around it, so an open breaker short-circuits
 * the remaining attempts. Exhausted retries and an open breaker surface as
 * {@link PaymentProviderUnavailableException} (503); non-retryable provider
 * errors keep their {@link PaymentProviderException} (502). The customer
 * path ({@value #CHECKOUT_POLICY}) and the reconciliation path
 * ({@value #RECONCILIATION_POLICY}) use separate breaker instances, so a
 * failing bulk job cannot block deposits.
 */
@Component
public class StripeSdkCheckoutGateway implements StripeCheckoutGateway {

    private static final Logger log =
            LoggerFactory.getLogger(StripeSdkCheckoutGateway.class);

    static final String PRODUCT_NAME = "FinFlow deposit";
    static final String METADATA_DEPOSIT_ID = "depositId";
    static final Duration SESSION_LIFETIME = Duration.ofHours(1);
    static final long LIST_PAGE_SIZE = 100L;
    private static final int HTTP_NOT_FOUND = 404;

    /** Breaker and retry instance for session creation (customer path). */
    public static final String CHECKOUT_POLICY = "stripe-checkout";

    /** Breaker and retry instance for the reconciliation job. */
    public static final String RECONCILIATION_POLICY = "stripe-reconciliation";

    /** One logical call, including its retries and breaker rejections. */
    static final String OBSERVATION_CALL = "finflow.stripe.call";
    /** One HTTP attempt to Stripe, a child of the call. */
    static final String OBSERVATION_ATTEMPT = "finflow.stripe.attempt";

    private final StripeClient client;
    private final StripeProperties properties;
    private final CircuitBreaker checkoutBreaker;
    private final Retry checkoutRetry;
    private final CircuitBreaker reconciliationBreaker;
    private final Retry reconciliationRetry;
    private final ObservationRegistry observationRegistry;

    public StripeSdkCheckoutGateway(
            StripeClient client,
            StripeProperties properties,
            CircuitBreakerRegistry circuitBreakerRegistry,
            RetryRegistry retryRegistry,
            ObservationRegistry observationRegistry
    ) {
        this.client = client;
        this.properties = properties;
        this.observationRegistry = observationRegistry;
        this.checkoutBreaker = circuitBreakerRegistry.circuitBreaker(CHECKOUT_POLICY);
        this.checkoutRetry = retryRegistry.retry(CHECKOUT_POLICY);
        this.reconciliationBreaker = circuitBreakerRegistry.circuitBreaker(RECONCILIATION_POLICY);
        this.reconciliationRetry = retryRegistry.retry(RECONCILIATION_POLICY);
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

        Session session = resilient(
                "checkout.sessions.create",
                checkoutBreaker,
                checkoutRetry,
                () -> client.v1().checkout().sessions().create(params, options)
        );

        return new CheckoutSession(
                session.getId(),
                session.getUrl(),
                session.getPaymentIntent()
        );
    }

    @Override
    public List<ProviderCheckoutSession> listCheckoutSessions(Instant from, Instant to) {
        SessionListParams params = SessionListParams.builder()
                .setCreated(
                        SessionListParams.Created.builder()
                                .setGte(from.getEpochSecond())
                                .setLte(to.getEpochSecond())
                                .build()
                )
                .setLimit(LIST_PAGE_SIZE)
                .build();

        return resilient("checkout.sessions.list", reconciliationBreaker, reconciliationRetry, () -> {
            List<ProviderCheckoutSession> sessions = new ArrayList<>();

            for (Session session : client.v1().checkout().sessions().list(params).autoPagingIterable()) {
                sessions.add(toProviderSession(session));
            }

            return sessions;
        });
    }

    @Override
    public Optional<ProviderCheckoutSession> findCheckoutSession(String sessionId) {
        try {
            Session session = resilient(
                    "checkout.sessions.retrieve",
                    reconciliationBreaker,
                    reconciliationRetry,
                    () -> client.v1().checkout().sessions().retrieve(sessionId)
            );

            return Optional.of(toProviderSession(session));
        } catch (PaymentProviderException exception) {
            if (Integer.valueOf(HTTP_NOT_FOUND).equals(exception.getStatusCode())) {
                return Optional.empty();
            }
            throw exception;
        }
    }

    static ProviderCheckoutSession toProviderSession(Session session) {
        return new ProviderCheckoutSession(
                session.getId(),
                session.getPaymentIntent(),
                session.getStatus(),
                session.getPaymentStatus(),
                session.getAmountTotal(),
                session.getCurrency(),
                session.getClientReferenceId()
        );
    }

    /**
     * Runs one SDK call as Retry(CircuitBreaker(call)): every attempt is
     * recorded by the breaker, and once it is open the retry stops because
     * {@link CallNotPermittedException} is not retryable.
     *
     * <p>Traced as a {@value #OBSERVATION_CALL} span with one
     * {@value #OBSERVATION_ATTEMPT} child per HTTP attempt, so a slow
     * request shows how long Stripe took, how many retries it needed and
     * whether the breaker rejected it without calling Stripe at all.
     */
    <T> T resilient(String operation, CircuitBreaker breaker, Retry retry, StripeCall<T> call) {
        return Observation.createNotStarted(OBSERVATION_CALL, observationRegistry)
                .contextualName("stripe " + operation)
                .lowCardinalityKeyValue("operation", operation)
                .lowCardinalityKeyValue("policy", breaker.getName())
                .observe(() -> resilientCall(operation, breaker, retry, call));
    }

    private <T> T resilientCall(String operation, CircuitBreaker breaker, Retry retry, StripeCall<T> call) {
        CheckedSupplier<T> translated = () -> Observation
                .createNotStarted(OBSERVATION_ATTEMPT, observationRegistry)
                .contextualName("stripe attempt")
                .lowCardinalityKeyValue("operation", operation)
                .observe(() -> {
                    try {
                        return call.execute();
                    } catch (StripeException exception) {
                        throw StripeExceptions.translate(exception);
                    }
                });

        CheckedSupplier<T> decorated = Retry.decorateCheckedSupplier(
                retry,
                CircuitBreaker.decorateCheckedSupplier(breaker, translated)
        );

        try {
            return decorated.get();
        } catch (CallNotPermittedException exception) {
            log.warn("Stripe call rejected: circuit breaker {} is open", breaker.getName());
            throw new PaymentProviderUnavailableException(
                    "Payment provider is unavailable (circuit open)", exception);
        } catch (PaymentProviderException exception) {
            if (exception.isRetryable()) {
                log.warn("Stripe call failed after retries: {}", exception.getMessage());
                throw new PaymentProviderUnavailableException(exception.getMessage(), exception);
            }
            throw exception;
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Throwable throwable) {
            throw new IllegalStateException("Unexpected failure calling Stripe", throwable);
        }
    }

    @FunctionalInterface
    interface StripeCall<T> {
        T execute() throws StripeException;
    }
}
