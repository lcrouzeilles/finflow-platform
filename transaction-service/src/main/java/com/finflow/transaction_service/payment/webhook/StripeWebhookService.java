package com.finflow.transaction_service.payment.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finflow.transaction_service.payment.service.DepositCompletionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Applies signature-verified Stripe events exactly once.
 *
 * <p>Deduplication and effect are atomic: the event id is inserted into
 * {@code processed_webhook_events} as the first statement of the
 * transaction, and the business effect runs in the same transaction. A
 * redelivery (same event id) inserts nothing and returns without effects;
 * a failure rolls back both the claim and the effect, so Stripe's retry
 * gets a clean second attempt. Stripe delivers at least once and out of
 * order, so every handler is a state transition that tolerates both.
 *
 * <p>Kept separate from the controller so that processing can move to an
 * asynchronous worker later without touching the HTTP surface.
 */
@Service
public class StripeWebhookService {

    private static final Logger log =
            LoggerFactory.getLogger(StripeWebhookService.class);

    static final String CHECKOUT_SESSION_COMPLETED = "checkout.session.completed";
    static final String CHECKOUT_SESSION_EXPIRED = "checkout.session.expired";

    private final ProcessedWebhookEventRepository processedEvents;
    private final DepositCompletionService depositCompletionService;
    private final ObjectMapper objectMapper;

    public StripeWebhookService(
            ProcessedWebhookEventRepository processedEvents,
            DepositCompletionService depositCompletionService,
            ObjectMapper objectMapper
    ) {
        this.processedEvents = processedEvents;
        this.depositCompletionService = depositCompletionService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public WebhookOutcome process(StripeWebhookEvent event) {
        int claimed = processedEvents.insertIfAbsent(
                event.eventId(),
                event.type(),
                Instant.now()
        );

        if (claimed == 0) {
            log.info("Stripe event {} ({}) already processed; ignoring redelivery",
                    event.eventId(), event.type());
            return WebhookOutcome.DUPLICATE;
        }

        return switch (event.type()) {
            case CHECKOUT_SESSION_COMPLETED -> onCheckoutSessionCompleted(event);
            case CHECKOUT_SESSION_EXPIRED -> onCheckoutSessionExpired(event);
            default -> {
                log.info("Stripe event {} of type {} is not handled; acknowledged",
                        event.eventId(), event.type());
                yield WebhookOutcome.IGNORED;
            }
        };
    }

    private WebhookOutcome onCheckoutSessionCompleted(StripeWebhookEvent event) {
        CheckoutSessionPayload session = parse(event);

        if (!session.isPaid()) {
            // Delayed payment methods report "unpaid" here and confirm later
            // through checkout.session.async_payment_succeeded, which this
            // service does not handle (card payments only).
            log.info("Stripe event {}: session {} completed with payment_status={}; no credit",
                    event.eventId(), session.id(), session.paymentStatus());
            return WebhookOutcome.IGNORED;
        }

        DepositCompletionService.Result result = depositCompletionService.complete(
                session.id(),
                session.amountTotal(),
                session.currency(),
                session.paymentIntentId()
        );

        log.info("Stripe event {}: session {} -> {}", event.eventId(), session.id(), result);

        return WebhookOutcome.PROCESSED;
    }

    private WebhookOutcome onCheckoutSessionExpired(StripeWebhookEvent event) {
        CheckoutSessionPayload session = parse(event);

        DepositCompletionService.Result result =
                depositCompletionService.expire(session.id());

        log.info("Stripe event {}: session {} expired -> {}",
                event.eventId(), session.id(), result);

        return WebhookOutcome.PROCESSED;
    }

    private CheckoutSessionPayload parse(StripeWebhookEvent event) {
        try {
            JsonNode object = objectMapper.readTree(event.rawDataObject());
            return CheckoutSessionPayload.from(object);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Cannot parse data.object of Stripe event " + event.eventId(),
                    exception
            );
        }
    }
}
