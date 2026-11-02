package com.finflow.transaction_service.payment.webhook;

import com.finflow.transaction_service.payment.stripe.StripeProperties;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.net.Webhook;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Receives Stripe webhooks.
 *
 * <p>There is no JWT on this route: the authentication <em>is</em> the
 * signature check. The body is bound as a raw {@link String} on purpose,
 * because the signature covers the exact bytes Stripe sent and any
 * re-serialization by a message converter would invalidate it. An invalid
 * or stale signature is a 400 and nothing is processed.
 *
 * <p>Stripe retries every non-2xx response, so the handler answers 200 for
 * duplicates and for event types it does not care about, and only lets a
 * genuine processing failure surface as 500 (which Stripe will retry).
 */
@RestController
@RequestMapping("/webhooks/stripe")
@Tag(name = "Webhooks", description = "Inbound events from the payment provider")
public class StripeWebhookController {

    public static final String SIGNATURE_HEADER = "Stripe-Signature";

    private final StripeWebhookService webhookService;
    private final StripeProperties properties;

    public StripeWebhookController(
            StripeWebhookService webhookService,
            StripeProperties properties
    ) {
        this.webhookService = webhookService;
        this.properties = properties;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Stripe webhook endpoint (signature-authenticated)")
    public Map<String, Object> receive(
            @RequestHeader(SIGNATURE_HEADER) String signature,
            @RequestBody String payload
    ) throws SignatureVerificationException {
        if (!properties.isWebhookConfigured()) {
            throw new SignatureVerificationException(
                    "Webhook signing secret is not configured",
                    signature
            );
        }

        Event event = Webhook.constructEvent(
                payload,
                signature,
                properties.webhookSecret()
        );

        WebhookOutcome outcome = webhookService.process(
                new StripeWebhookEvent(
                        event.getId(),
                        event.getType(),
                        event.getDataObjectDeserializer().getRawJson()
                )
        );

        return Map.of("received", true, "outcome", outcome.name());
    }
}
