package com.finflow.transaction_service.payment.service;

import com.finflow.transaction_service.AbstractIntegrationTest;
import com.finflow.transaction_service.config.TestSecurityConfig;
import com.finflow.transaction_service.domain.account.Account;
import com.finflow.transaction_service.domain.account.AccountNumber;
import com.finflow.transaction_service.domain.account.Owner;
import com.finflow.transaction_service.payment.domain.DepositRepository;
import com.finflow.transaction_service.payment.stripe.FakeStripeTransport;
import com.finflow.transaction_service.repository.AccountRepository;
import com.finflow.transaction_service.repository.OwnerRepository;
import com.stripe.StripeClient;
import com.stripe.exception.ApiConnectionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Stripe unreachable, end to end: real controller, service, resilience
 * policies and SDK client over a transport that always fails. Verifies the
 * 503 contract, that no deposit row is left behind, that the retry policy
 * is honoured and that the open breaker fails fast.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@TestPropertySource(properties = {
        "finflow.payments.stripe.resilience.max-attempts=3",
        "finflow.payments.stripe.resilience.initial-wait=1ms",
        "finflow.payments.stripe.resilience.sliding-window-size=3",
        "finflow.payments.stripe.resilience.minimum-number-of-calls=3",
        "finflow.payments.stripe.resilience.wait-in-open=5m"
})
class DepositServiceResilienceIntegrationTest extends AbstractIntegrationTest {

    static final FakeStripeTransport TRANSPORT = new FakeStripeTransport()
            .failWith(() -> new ApiConnectionException("Stripe unreachable"));

    @TestConfiguration
    static class UnreachableStripe {

        @Bean
        @Primary
        StripeClient unreachableStripeClient() {
            return new StripeClient(TRANSPORT);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OwnerRepository ownerRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private DepositRepository depositRepository;

    private String subject;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        subject = "resilience-" + UUID.randomUUID();
        UUID ownerId = ownerRepository.saveAndFlush(new Owner(subject)).getId();
        accountId = accountRepository
                .saveAndFlush(new Account(uniqueAccountNumber(), "USD", ownerId))
                .getId();
    }

    @Test
    void outageYields503WithoutLeavingADepositAndThenFailsFast() throws Exception {
        long depositsBefore = depositRepository.count();
        int callsBefore = TRANSPORT.calls.get();

        mockMvc.perform(post("/deposits")
                        .with(token())
                        .contentType(APPLICATION_JSON)
                        .header("Idempotency-Key", "outage-" + UUID.randomUUID())
                        .content(body()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.error").value("Service Unavailable"))
                .andExpect(jsonPath("$.message").value("Payment provider unavailable, retry later"))
                .andExpect(jsonPath("$.path").value("/deposits"));

        assertThat(TRANSPORT.calls.get() - callsBefore)
                .as("retry policy: three attempts")
                .isEqualTo(3);
        assertThat(depositRepository.count()).isEqualTo(depositsBefore);

        // Three recorded failures with a window and minimum of three: OPEN.
        int callsAfterFirstRequest = TRANSPORT.calls.get();

        mockMvc.perform(post("/deposits")
                        .with(token())
                        .contentType(APPLICATION_JSON)
                        .header("Idempotency-Key", "outage-" + UUID.randomUUID())
                        .content(body()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("Payment provider unavailable, retry later"));

        assertThat(TRANSPORT.calls.get())
                .as("open breaker: Stripe not called")
                .isEqualTo(callsAfterFirstRequest);
        assertThat(depositRepository.count()).isEqualTo(depositsBefore);

        mockMvc.perform(get("/actuator/metrics/resilience4j.circuitbreaker.state")
                        .param("tag", "name:stripe-checkout")
                        .param("tag", "state:open"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.measurements[0].value").value(1.0));
    }

    private RequestPostProcessor token() {
        return jwt().jwt(jwt -> jwt.subject(subject));
    }

    private String body() {
        return """
                {"accountId": "%s", "amount": 25.00, "currency": "USD"}
                """.formatted(accountId);
    }

    private static AccountNumber uniqueAccountNumber() {
        String suffix = UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 8)
                .toUpperCase();

        return new AccountNumber("FF-" + suffix);
    }
}
