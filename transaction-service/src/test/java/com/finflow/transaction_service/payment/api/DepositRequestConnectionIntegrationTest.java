package com.finflow.transaction_service.payment.api;

import com.finflow.transaction_service.AbstractIntegrationTest;
import com.finflow.transaction_service.config.TestSecurityConfig;
import com.finflow.transaction_service.domain.account.Account;
import com.finflow.transaction_service.domain.account.AccountNumber;
import com.finflow.transaction_service.domain.account.Owner;
import com.finflow.transaction_service.payment.stripe.CheckoutSession;
import com.finflow.transaction_service.payment.stripe.CreateCheckoutSession;
import com.finflow.transaction_service.payment.stripe.StripeCheckoutGateway;
import com.finflow.transaction_service.repository.AccountRepository;
import com.finflow.transaction_service.repository.OwnerRepository;
import com.finflow.transaction_service.service.OwnerIdentityService;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A deposit request must not hold a database connection while it waits for
 * Stripe. With open-in-view the request-scoped EntityManager, and the pooled
 * connection it acquired for the idempotency lookup, stayed bound to the
 * thread for the whole request, so a slow Stripe could exhaust the pool and
 * take down transfers too.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
class DepositRequestConnectionIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private OwnerRepository ownerRepository;

    @MockitoBean
    private StripeCheckoutGateway checkoutGateway;

    @MockitoBean
    private OwnerIdentityService ownerIdentityService;

    @Test
    void shouldNotHoldAnEntityManagerWhileCallingStripe() throws Exception {
        UUID ownerId = ownerRepository.save(new Owner()).getId();
        UUID accountId = accountRepository
                .saveAndFlush(new Account(uniqueAccountNumber(), "USD", ownerId))
                .getId();

        when(ownerIdentityService.resolveOwnerId(any())).thenReturn(ownerId);

        AtomicReference<Boolean> entityManagerBound = new AtomicReference<>();

        when(checkoutGateway.createCheckoutSession(any(CreateCheckoutSession.class)))
                .thenAnswer(invocation -> {
                    entityManagerBound.set(
                            TransactionSynchronizationManager.hasResource(entityManagerFactory));
                    return new CheckoutSession(
                            "cs_test_connection",
                            "https://checkout.stripe.com/c/pay/cs_test_connection",
                            null
                    );
                });

        mockMvc.perform(post("/deposits")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId": "%s", "amount": 20.00, "currency": "USD"}
                                """.formatted(accountId)))
                .andExpect(status().isCreated());

        assertThat(entityManagerBound.get()).isFalse();
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
