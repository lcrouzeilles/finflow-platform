package com.finflow.transaction_service.payment.webhook;

import com.finflow.transaction_service.config.SecurityConfig;
import com.finflow.transaction_service.payment.stripe.StripeClientConfig;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static com.finflow.transaction_service.payment.webhook.StripeWebhookTestSupport.TEST_WEBHOOK_SECRET;
import static com.finflow.transaction_service.payment.webhook.StripeWebhookTestSupport.envelope;
import static com.finflow.transaction_service.payment.webhook.StripeWebhookTestSupport.eventId;
import static com.finflow.transaction_service.payment.webhook.StripeWebhookTestSupport.paidSessionObject;
import static com.finflow.transaction_service.payment.webhook.StripeWebhookTestSupport.sign;
import static com.finflow.transaction_service.payment.webhook.StripeWebhookTestSupport.signAt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Runs with the real {@link SecurityConfig}: the webhook route needs no JWT,
 * and the signature is the only authentication.
 */
@WebMvcTest(StripeWebhookController.class)
@Import({SecurityConfig.class, StripeClientConfig.class})
@TestPropertySource(properties = {
        "finflow.payments.stripe.webhook-secret=" + TEST_WEBHOOK_SECRET,
        "finflow.payments.stripe.success-url=http://localhost/success",
        "finflow.payments.stripe.cancel-url=http://localhost/cancel",
        "finflow.payments.stripe.connect-timeout=3s",
        "finflow.payments.stripe.read-timeout=10s"
})
class StripeWebhookControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StripeWebhookService webhookService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void shouldAcceptValidSignatureWithoutJwtAndForwardTheEvent() throws Exception {
        String id = eventId();
        String dataObject = paidSessionObject("cs_test_ctrl", 2500L, "usd", null);
        String payload = envelope(id, "checkout.session.completed", dataObject);

        when(webhookService.process(any())).thenReturn(WebhookOutcome.PROCESSED);

        mockMvc.perform(post("/webhooks/stripe")
                        .contentType(APPLICATION_JSON)
                        .header("Stripe-Signature", sign(payload))
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(true))
                .andExpect(jsonPath("$.outcome").value("PROCESSED"));

        ArgumentCaptor<StripeWebhookEvent> captor =
                ArgumentCaptor.forClass(StripeWebhookEvent.class);
        verify(webhookService).process(captor.capture());

        StripeWebhookEvent forwarded = captor.getValue();
        assertThat(forwarded.eventId()).isEqualTo(id);
        assertThat(forwarded.type()).isEqualTo("checkout.session.completed");
        assertThat(forwarded.rawDataObject()).contains("\"id\":\"cs_test_ctrl\"");
    }

    @Test
    void shouldRejectInvalidSignatureWithStandardErrorBodyAndNoProcessing() throws Exception {
        String payload = envelope(eventId(), "checkout.session.completed",
                paidSessionObject("cs_test_bad", 2500L, "usd", null));

        mockMvc.perform(post("/webhooks/stripe")
                        .contentType(APPLICATION_JSON)
                        .header("Stripe-Signature", sign(payload, "whsec_someone_else"))
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Invalid webhook signature"))
                .andExpect(jsonPath("$.path").value("/webhooks/stripe"));

        verifyNoInteractions(webhookService);
    }

    @Test
    void shouldRejectTamperedPayload() throws Exception {
        String payload = envelope(eventId(), "checkout.session.completed",
                paidSessionObject("cs_test_tamper", 2500L, "usd", null));
        String signature = sign(payload);
        String tampered = payload.replace("2500", "9900");

        mockMvc.perform(post("/webhooks/stripe")
                        .contentType(APPLICATION_JSON)
                        .header("Stripe-Signature", signature)
                        .content(tampered))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(webhookService);
    }

    @Test
    void shouldRejectStaleSignature() throws Exception {
        String payload = envelope(eventId(), "checkout.session.completed",
                paidSessionObject("cs_test_stale", 2500L, "usd", null));
        long oneHourAgo = Instant.now().minusSeconds(3600).getEpochSecond();

        mockMvc.perform(post("/webhooks/stripe")
                        .contentType(APPLICATION_JSON)
                        .header("Stripe-Signature", signAt(payload, oneHourAgo))
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid webhook signature"));

        verifyNoInteractions(webhookService);
    }

    @Test
    void shouldRejectMissingSignatureHeader() throws Exception {
        String payload = envelope(eventId(), "checkout.session.completed",
                paidSessionObject("cs_test_nohdr", 2500L, "usd", null));

        mockMvc.perform(post("/webhooks/stripe")
                        .contentType(APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        verifyNoInteractions(webhookService);
    }

    @Test
    void shouldAcknowledgeDuplicatesAndUnknownTypesWith200() throws Exception {
        String payload = envelope(eventId(), "payment_intent.created", "{\"id\": \"pi_x\"}");

        when(webhookService.process(any())).thenReturn(WebhookOutcome.IGNORED);

        mockMvc.perform(post("/webhooks/stripe")
                        .contentType(APPLICATION_JSON)
                        .header("Stripe-Signature", sign(payload))
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("IGNORED"));
    }
}
