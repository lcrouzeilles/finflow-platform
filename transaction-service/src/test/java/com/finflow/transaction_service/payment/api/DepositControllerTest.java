package com.finflow.transaction_service.payment.api;

import com.finflow.transaction_service.config.SecurityConfig;
import com.finflow.transaction_service.exception.AccountNotFoundException;
import com.finflow.transaction_service.exception.IdempotencyKeyConflictException;
import com.finflow.transaction_service.payment.domain.Deposit;
import com.finflow.transaction_service.payment.service.CreateDepositCommand;
import com.finflow.transaction_service.payment.service.DepositNotFoundException;
import com.finflow.transaction_service.payment.service.DepositService;
import com.finflow.transaction_service.payment.service.InvalidDepositException;
import com.finflow.transaction_service.payment.stripe.PaymentProviderException;
import com.finflow.transaction_service.service.OwnerIdentityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Runs against the real {@link SecurityConfig} so the 401/403 contract of
 * {@code /deposits} is exercised at the MVC layer.
 */
@WebMvcTest(DepositController.class)
@Import(SecurityConfig.class)
class DepositControllerTest {

    private static final String SUBJECT = "keycloak-subject-deposit";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DepositService depositService;

    @MockitoBean
    private OwnerIdentityService ownerIdentityService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private UUID requestingOwnerId;
    private UUID accountId;

    private static RequestPostProcessor userToken() {
        return jwt()
                .jwt(jwt -> jwt.subject(SUBJECT))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    @BeforeEach
    void setUp() {
        requestingOwnerId = UUID.randomUUID();
        accountId = UUID.randomUUID();

        when(ownerIdentityService.resolveOwnerId(any(Jwt.class)))
                .thenReturn(requestingOwnerId);
    }

    @Test
    void shouldRejectUnauthenticatedDepositCreation() throws Exception {
        mockMvc.perform(post("/deposits")
                        .contentType(APPLICATION_JSON)
                        .header("Idempotency-Key", "no-token")
                        .content(body("25.00", "USD")))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(depositService);
    }

    @Test
    void shouldRejectUnauthenticatedDepositRead() throws Exception {
        mockMvc.perform(get("/deposits/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(depositService);
    }

    @Test
    void shouldCreateDepositAndReturnCheckoutUrl() throws Exception {
        Deposit deposit = deposit(requestingOwnerId, "25.00");

        when(depositService.createDeposit(any(CreateDepositCommand.class), eq(requestingOwnerId)))
                .thenReturn(deposit);

        mockMvc.perform(post("/deposits")
                        .with(userToken())
                        .contentType(APPLICATION_JSON)
                        .header("Idempotency-Key", "deposit-001")
                        .content(body("25.00", "USD")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.depositId").value(deposit.getId().toString()))
                .andExpect(jsonPath("$.accountId").value(accountId.toString()))
                .andExpect(jsonPath("$.amount").value(25.00))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.checkoutUrl").value("https://checkout.test/session"));

        ArgumentCaptor<CreateDepositCommand> captor =
                ArgumentCaptor.forClass(CreateDepositCommand.class);
        verify(depositService).createDeposit(captor.capture(), eq(requestingOwnerId));

        CreateDepositCommand command = captor.getValue();
        assertThat(command.accountId()).isEqualTo(accountId);
        assertThat(command.amount()).isEqualByComparingTo("25.00");
        assertThat(command.currency()).isEqualTo("USD");
        assertThat(command.idempotencyKey()).isEqualTo("deposit-001");

        ArgumentCaptor<Jwt> jwtCaptor = ArgumentCaptor.forClass(Jwt.class);
        verify(ownerIdentityService).resolveOwnerId(jwtCaptor.capture());
        assertThat(jwtCaptor.getValue().getSubject()).isEqualTo(SUBJECT);
    }

    @Test
    void shouldRequireIdempotencyKeyHeader() throws Exception {
        mockMvc.perform(post("/deposits")
                        .with(userToken())
                        .contentType(APPLICATION_JSON)
                        .content(body("25.00", "USD")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value(containsString("Idempotency-Key")))
                .andExpect(jsonPath("$.path").value("/deposits"));

        verifyNoInteractions(depositService);
    }

    @Test
    void shouldReturnFieldErrorsForInvalidBody() throws Exception {
        mockMvc.perform(post("/deposits")
                        .with(userToken())
                        .contentType(APPLICATION_JSON)
                        .header("Idempotency-Key", "deposit-invalid")
                        .content(body("10.123", "usd")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Request validation failed"))
                .andExpect(jsonPath("$.fieldErrors.amount").exists())
                .andExpect(jsonPath("$.fieldErrors.currency").exists());

        verifyNoInteractions(depositService);
    }

    @Test
    void shouldReturn403WhenReplayingAnotherOwnersKey() throws Exception {
        when(depositService.createDeposit(any(), any()))
                .thenThrow(new AccessDeniedException("You are not authorized to access this deposit"));

        mockMvc.perform(post("/deposits")
                        .with(userToken())
                        .contentType(APPLICATION_JSON)
                        .header("Idempotency-Key", "foreign-key")
                        .content(body("25.00", "USD")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(jsonPath("$.message").value("You are not authorized to access this deposit"))
                .andExpect(jsonPath("$.path").value("/deposits"));
    }

    @Test
    void shouldReturn404ForAccountOfAnotherOwner() throws Exception {
        when(depositService.createDeposit(any(), any()))
                .thenThrow(new AccountNotFoundException(accountId));

        mockMvc.perform(post("/deposits")
                        .with(userToken())
                        .contentType(APPLICATION_JSON)
                        .header("Idempotency-Key", "foreign-account")
                        .content(body("25.00", "USD")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"));
    }

    @Test
    void shouldReturn409WhenKeyReusedWithDifferentData() throws Exception {
        when(depositService.createDeposit(any(), any()))
                .thenThrow(new IdempotencyKeyConflictException(
                        "Idempotency-Key was already used with different deposit data"));

        mockMvc.perform(post("/deposits")
                        .with(userToken())
                        .contentType(APPLICATION_JSON)
                        .header("Idempotency-Key", "reused-key")
                        .content(body("25.00", "USD")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"));
    }

    @Test
    void shouldReturn422ForInvalidDeposit() throws Exception {
        when(depositService.createDeposit(any(), any()))
                .thenThrow(new InvalidDepositException("Currency ARS is not supported for deposits"));

        mockMvc.perform(post("/deposits")
                        .with(userToken())
                        .contentType(APPLICATION_JSON)
                        .header("Idempotency-Key", "ars-key")
                        .content(body("25.00", "ARS")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.error").value("Unprocessable Content"))
                .andExpect(jsonPath("$.message").value("Currency ARS is not supported for deposits"))
                .andExpect(jsonPath("$.fieldErrors").isEmpty());
    }

    @Test
    void shouldReturn502WhenProviderRejectsTheRequest() throws Exception {
        when(depositService.createDeposit(any(), any()))
                .thenThrow(new PaymentProviderException(
                        "Payment provider rejected the request: Invalid API Key provided",
                        false, 401, null));

        mockMvc.perform(post("/deposits")
                        .with(userToken())
                        .contentType(APPLICATION_JSON)
                        .header("Idempotency-Key", "provider-error")
                        .content(body("25.00", "USD")))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value(502))
                .andExpect(jsonPath("$.error").value("Bad Gateway"))
                // Provider details never reach the client.
                .andExpect(jsonPath("$.message").value("Payment provider rejected the request"));
    }

    @Test
    void shouldReturnOwnedDeposit() throws Exception {
        Deposit deposit = deposit(requestingOwnerId, "40.00");

        when(depositService.getOwnedDeposit(deposit.getId(), requestingOwnerId))
                .thenReturn(deposit);

        mockMvc.perform(get("/deposits/{id}", deposit.getId()).with(userToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.depositId").value(deposit.getId().toString()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.amount").value(40.00));
    }

    @Test
    void shouldReturn404ForDepositOfAnotherOwner() throws Exception {
        UUID depositId = UUID.randomUUID();

        when(depositService.getOwnedDeposit(depositId, requestingOwnerId))
                .thenThrow(new DepositNotFoundException(depositId));

        mockMvc.perform(get("/deposits/{id}", depositId).with(userToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.checkoutUrl").doesNotExist());
    }

    private String body(String amount, String currency) {
        return """
                {
                  "accountId": "%s",
                  "amount": %s,
                  "currency": "%s"
                }
                """.formatted(accountId, amount, currency);
    }

    private Deposit deposit(UUID ownerId, String amount) {
        return new Deposit(
                UUID.randomUUID(),
                accountId,
                ownerId,
                new BigDecimal(amount),
                "USD",
                "controller-key",
                "cs_test_controller",
                "https://checkout.test/session",
                null
        );
    }
}
