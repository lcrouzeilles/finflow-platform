package com.finflow.transaction_service.payment.stripe;

import com.stripe.StripeClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No network: only verifies that the configuration binds from properties
 * and that a context can start without any Stripe credential.
 */
class StripeClientConfigTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(StripeClientConfig.class)
                    .withPropertyValues(
                            "finflow.payments.stripe.success-url=http://localhost:8080/deposits/success",
                            "finflow.payments.stripe.cancel-url=http://localhost:8080/deposits/cancel",
                            "finflow.payments.stripe.connect-timeout=3s",
                            "finflow.payments.stripe.read-timeout=10s"
                    );

    @Test
    void shouldStartWithoutStripeCredentials() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(StripeClient.class);

            StripeProperties properties = context.getBean(StripeProperties.class);

            assertThat(properties.isConfigured()).isFalse();
            assertThat(properties.isWebhookConfigured()).isFalse();
            assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
            assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(10));
        });
    }

    @Test
    void shouldBindSecretsFromProperties() {
        contextRunner
                .withPropertyValues(
                        "finflow.payments.stripe.secret-key=sk_test_dummy",
                        "finflow.payments.stripe.webhook-secret=whsec_dummy"
                )
                .run(context -> {
                    StripeProperties properties =
                            context.getBean(StripeProperties.class);

                    assertThat(properties.isConfigured()).isTrue();
                    assertThat(properties.isWebhookConfigured()).isTrue();
                    assertThat(properties.secretKey()).isEqualTo("sk_test_dummy");
                });
    }

    @Test
    void shouldUseTheRealStripeApiUnlessOverridden() {
        contextRunner.run(context ->
                assertThat(context.getBean(StripeProperties.class).hasApiBaseOverride())
                        .isFalse());

        contextRunner
                .withPropertyValues("finflow.payments.stripe.api-base=http://fake-stripe:8080")
                .run(context -> {
                    assertThat(context).hasSingleBean(StripeClient.class);
                    StripeProperties properties = context.getBean(StripeProperties.class);
                    assertThat(properties.hasApiBaseOverride()).isTrue();
                    assertThat(properties.apiBase()).isEqualTo("http://fake-stripe:8080");
                });
    }

    @Test
    void shouldRejectMissingRedirectUrls() {
        new ApplicationContextRunner()
                .withUserConfiguration(StripeClientConfig.class)
                .withPropertyValues(
                        "finflow.payments.stripe.connect-timeout=3s",
                        "finflow.payments.stripe.read-timeout=10s"
                )
                .run(context -> assertThat(context).hasFailed());
    }
}
