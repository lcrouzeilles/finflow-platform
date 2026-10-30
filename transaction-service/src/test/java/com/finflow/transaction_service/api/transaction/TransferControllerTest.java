package com.finflow.transaction_service.api.transaction;

import com.finflow.transaction_service.api.transfer.CreateTransferRequest;
import com.finflow.transaction_service.api.transfer.TransferController;
import com.finflow.transaction_service.config.SecurityConfig;
import com.finflow.transaction_service.domain.transaction.Transaction;
import com.finflow.transaction_service.domain.transaction.TransferRequest;
import com.finflow.transaction_service.exception.AccountNotFoundException;
import com.finflow.transaction_service.exception.IdempotencyKeyConflictException;
import com.finflow.transaction_service.exception.InsufficientFundsException;
import com.finflow.transaction_service.exception.InvalidTransferException;
import com.finflow.transaction_service.service.OwnerIdentityService;
import com.finflow.transaction_service.service.TransferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Runs against the real {@link SecurityConfig} (JWT resource server, roles)
 * so that the {@code @AuthenticationPrincipal Jwt} resolution and the
 * 401/403 contract are exercised end to end at the MVC layer.
 */
@WebMvcTest(TransferController.class)
@Import(SecurityConfig.class)
class TransferControllerTest {

    private static final String SUBJECT = "keycloak-subject-001";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private TransferService transferService;

    @MockitoBean
    private OwnerIdentityService ownerIdentityService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private UUID requestingOwnerId;

    private static RequestPostProcessor userToken() {
        return jwt()
                .jwt(jwt -> jwt.subject(SUBJECT))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    @BeforeEach
    void setUp() {
        requestingOwnerId = UUID.randomUUID();

        when(ownerIdentityService.resolveOwnerId(any(Jwt.class)))
                .thenReturn(requestingOwnerId);
    }

    @Test
    void shouldCreateTransfer() throws Exception {
        UUID sourceAccountId = UUID.randomUUID();
        UUID destinationAccountId = UUID.randomUUID();

        CreateTransferRequest request = new CreateTransferRequest(
                sourceAccountId,
                destinationAccountId,
                new BigDecimal("150.00")
        );

        Transaction transaction = new Transaction(
                sourceAccountId,
                destinationAccountId,
                new BigDecimal("150.00"),
                "ARS",
                "controller-test-key"
        );

        transaction.markAsCompleted();

        when(transferService.transfer(any(TransferRequest.class), any(UUID.class)))
                .thenReturn(transaction);

        mockMvc.perform(
                        post("/transfers")
                                .with(userToken())
                                .contentType(APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request))
                                .header("Idempotency-Key", "controller-success-001")
                )
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sourceAccountId")
                        .value(sourceAccountId.toString()))
                .andExpect(jsonPath("$.destinationAccountId")
                        .value(destinationAccountId.toString()))
                .andExpect(jsonPath("$.amount")
                        .value(150.00))
                .andExpect(jsonPath("$.currency")
                        .value("ARS"))
                .andExpect(jsonPath("$.status")
                        .value("COMPLETED"));
    }

    @Test
    void shouldPassOwnerResolvedFromTokenToService() throws Exception {
        UUID sourceAccountId = UUID.randomUUID();
        UUID destinationAccountId = UUID.randomUUID();

        Transaction transaction = new Transaction(
                sourceAccountId,
                destinationAccountId,
                new BigDecimal("10.00"),
                "ARS",
                "owner-from-token-001"
        );
        transaction.markAsCompleted();

        when(transferService.transfer(any(TransferRequest.class), any(UUID.class)))
                .thenReturn(transaction);

        mockMvc.perform(
                        post("/transfers")
                                .with(userToken())
                                .contentType(APPLICATION_JSON)
                                .content("""
                                {
                                  "sourceAccountId": "%s",
                                  "destinationAccountId": "%s",
                                  "amount": 10.00
                                }
                                """.formatted(sourceAccountId, destinationAccountId))
                                .header("Idempotency-Key", "owner-from-token-001")
                )
                .andExpect(status().isCreated());

        ArgumentCaptor<Jwt> jwtCaptor = ArgumentCaptor.forClass(Jwt.class);
        verify(ownerIdentityService).resolveOwnerId(jwtCaptor.capture());
        assertThat(jwtCaptor.getValue().getSubject()).isEqualTo(SUBJECT);

        ArgumentCaptor<TransferRequest> requestCaptor =
                ArgumentCaptor.forClass(TransferRequest.class);
        verify(transferService).transfer(
                requestCaptor.capture(),
                eq(requestingOwnerId)
        );
        assertThat(requestCaptor.getValue().idempotencyKey())
                .isEqualTo("owner-from-token-001");
    }

    @Test
    void shouldReturn403WhenSourceAccountBelongsToAnotherOwner() throws Exception {
        when(transferService.transfer(any(TransferRequest.class), any(UUID.class)))
                .thenThrow(new AccessDeniedException(
                        "You are not authorized to transfer from this account"
                ));

        mockMvc.perform(post("/transfers")
                        .with(userToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "cross-owner-001")
                        .content("""
                    {
                      "sourceAccountId": "11111111-1111-1111-1111-111111111111",
                      "destinationAccountId": "22222222-2222-2222-2222-222222222222",
                      "amount": 200.00
                    }
                    """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(jsonPath("$.message").value(
                        "You are not authorized to transfer from this account"
                ))
                .andExpect(jsonPath("$.path").value("/transfers"));
    }

    @Test
    void shouldRejectUnauthenticatedRequest() throws Exception {
        mockMvc.perform(post("/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "no-token-001")
                        .content("""
                    {
                      "sourceAccountId": "11111111-1111-1111-1111-111111111111",
                      "destinationAccountId": "22222222-2222-2222-2222-222222222222",
                      "amount": 200.00
                    }
                    """))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(transferService);
    }

    @Test
    void shouldRejectRequestWithoutSourceAccountId() throws Exception {
        String request = """
        {
          "destinationAccountId": "%s",
          "amount": 150.00
        }
        """.formatted(UUID.randomUUID());

        mockMvc.perform(post("/transfers")
                        .with(userToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request)
                        .header("Idempotency-Key", "no-source-account-001"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRejectRequestWithoutDestinationAccountId() throws Exception {
        String request = """
        {
          "sourceAccountId": "%s",
          "amount": 150.00
        }
        """.formatted(UUID.randomUUID());

        mockMvc.perform(post("/transfers")
                        .with(userToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request)
                        .header("Idempotency-Key", "no-destination-account-001"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRejectRequestWithoutAmount() throws Exception {
        String request = """
        {
          "sourceAccountId": "%s",
          "destinationAccountId": "%s"
        }
        """.formatted(UUID.randomUUID(), UUID.randomUUID());

        mockMvc.perform(post("/transfers")
                        .with(userToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request)
                        .header("Idempotency-Key", "no-amount-001"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRejectZeroAmount() throws Exception {
        String request = """
        {
          "sourceAccountId": "%s",
          "destinationAccountId": "%s",
          "amount": 0
        }
        """.formatted(UUID.randomUUID(), UUID.randomUUID());

        mockMvc.perform(post("/transfers")
                        .with(userToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request)
                        .header("Idempotency-Key", "zero-amount-001"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRejectNegativeAmount() throws Exception {
        String request = """
        {
          "sourceAccountId": "%s",
          "destinationAccountId": "%s",
          "amount": -10
        }
        """.formatted(UUID.randomUUID(), UUID.randomUUID());

        mockMvc.perform(post("/transfers")
                        .with(userToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request)
                        .header("Idempotency-Key", "negative-amount-001"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldReturn422WhenSourceAccountHasInsufficientFunds() throws Exception {
        UUID sourceAccountId = UUID.randomUUID();
        UUID destinationAccountId = UUID.randomUUID();

        String request = """
        {
          "sourceAccountId": "%s",
          "destinationAccountId": "%s",
          "amount": 150.00
        }
        """.formatted(sourceAccountId, destinationAccountId);

        when(transferService.transfer(any(TransferRequest.class), any(UUID.class)))
                .thenThrow(
                        new InsufficientFundsException(
                                new BigDecimal("150.00"),
                                new BigDecimal("100.00")
                        )
                );

        mockMvc.perform(post("/transfers")
                        .with(userToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request)
                        .header("Idempotency-Key", "insufficient-funds-001"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.error").value("Unprocessable Content"))
                .andExpect(jsonPath("$.message").value(
                        "Insufficient funds. Requested: 150.00, available: 100.00"
                ))
                .andExpect(jsonPath("$.path").value("/transfers"))
                .andExpect(jsonPath("$.fieldErrors").isEmpty());
    }

    @Test
    void shouldReturn404WhenAccountDoesNotExist() throws Exception {
        UUID sourceAccountId = UUID.randomUUID();
        UUID destinationAccountId = UUID.randomUUID();

        String request = """
        {
          "sourceAccountId": "%s",
          "destinationAccountId": "%s",
          "amount": 150.00
        }
        """.formatted(sourceAccountId, destinationAccountId);

        when(transferService.transfer(any(TransferRequest.class), any(UUID.class)))
                .thenThrow(new AccountNotFoundException(sourceAccountId));

        mockMvc.perform(post("/transfers")
                        .with(userToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request)
                        .header("Idempotency-Key", "account-does-not-exist-001"))
                .andExpect(status().is(404))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void shouldReturn409WhenIdempotencyKeyConflicts()
            throws Exception {

        when(transferService.transfer(any(TransferRequest.class), any(UUID.class)))
                .thenThrow(new IdempotencyKeyConflictException(
                        "Idempotency-Key was already used with different transfer data"
                ));

        mockMvc.perform(post("/transfers")
                        .with(userToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "controller-conflict-001")
                        .content("""
                    {
                      "sourceAccountId": "11111111-1111-1111-1111-111111111111",
                      "destinationAccountId": "22222222-2222-2222-2222-222222222222",
                      "amount": 200.00
                    }
                    """))
                .andDo(print())
                .andExpect(status().isConflict());
    }

    // -----------------------------------------------------------------
    // Error contract (A1 / M1)
    // -----------------------------------------------------------------

    private static final String VALID_BODY = """
        {
          "sourceAccountId": "11111111-1111-1111-1111-111111111111",
          "destinationAccountId": "22222222-2222-2222-2222-222222222222",
          "amount": 200.00
        }
        """;

    @Test
    void shouldReturn400WhenIdempotencyKeyHeaderIsMissing() throws Exception {
        mockMvc.perform(post("/transfers")
                        .with(userToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("Idempotency-Key")))
                .andExpect(jsonPath("$.path").value("/transfers"));

        verifyNoInteractions(transferService);
    }

    @Test
    void shouldReturn400WhenBodyIsMalformedJson() throws Exception {
        mockMvc.perform(post("/transfers")
                        .with(userToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "malformed-001")
                        .content("{ not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.path").value("/transfers"));

        verifyNoInteractions(transferService);
    }

    @Test
    void shouldRejectAmountWithMoreThanTwoDecimals() throws Exception {
        String request = """
        {
          "sourceAccountId": "%s",
          "destinationAccountId": "%s",
          "amount": 10.123
        }
        """.formatted(UUID.randomUUID(), UUID.randomUUID());

        mockMvc.perform(post("/transfers")
                        .with(userToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request)
                        .header("Idempotency-Key", "scale-001"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Request validation failed"))
                .andExpect(jsonPath("$.fieldErrors.amount").exists());

        verifyNoInteractions(transferService);
    }

    @Test
    void shouldRejectAmountWithTooManyIntegerDigits() throws Exception {
        String request = """
        {
          "sourceAccountId": "%s",
          "destinationAccountId": "%s",
          "amount": 1234567890123456.00
        }
        """.formatted(UUID.randomUUID(), UUID.randomUUID());

        mockMvc.perform(post("/transfers")
                        .with(userToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request)
                        .header("Idempotency-Key", "magnitude-001"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.amount").exists());

        verifyNoInteractions(transferService);
    }

    @Test
    void shouldReturn422WhenTransferIsInvalid() throws Exception {
        when(transferService.transfer(any(TransferRequest.class), any(UUID.class)))
                .thenThrow(new InvalidTransferException(
                        "Source and destination currencies must match"
                ));

        mockMvc.perform(post("/transfers")
                        .with(userToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "invalid-transfer-001")
                        .content(VALID_BODY))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.error").value("Unprocessable Content"))
                .andExpect(jsonPath("$.message").value(
                        "Source and destination currencies must match"))
                .andExpect(jsonPath("$.path").value("/transfers"));
    }

    @Test
    void shouldReturn400WhenIdempotencyKeyIsRejectedByDomain() throws Exception {
        when(transferService.transfer(any(TransferRequest.class), any(UUID.class)))
                .thenThrow(new IllegalArgumentException(
                        "Idempotency-Key must not exceed 100 characters"
                ));

        mockMvc.perform(post("/transfers")
                        .with(userToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "x".repeat(101))
                        .content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(
                        "Idempotency-Key must not exceed 100 characters"));
    }

    @Test
    void shouldReturn409OnUnresolvedDataIntegrityViolation() throws Exception {
        when(transferService.transfer(any(TransferRequest.class), any(UUID.class)))
                .thenThrow(new DataIntegrityViolationException(
                        "ERROR: duplicate key value violates unique constraint"
                ));

        mockMvc.perform(post("/transfers")
                        .with(userToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "integrity-001")
                        .content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value(
                        "Request conflicts with existing data"));
    }

    @Test
    void shouldReturnGeneric500WithoutInternalDetails() throws Exception {
        when(transferService.transfer(any(TransferRequest.class), any(UUID.class)))
                .thenThrow(new IllegalStateException(
                        "Failed to serialize transfer completed event"
                ));

        mockMvc.perform(post("/transfers")
                        .with(userToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "unexpected-001")
                        .content(VALID_BODY))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.error").value("Internal Server Error"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andExpect(jsonPath("$.path").value("/transfers"))
                .andExpect(jsonPath("$.fieldErrors").isEmpty());
    }

    @Test
    void shouldReturn405ForUnsupportedMethodUsingErrorContract() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/transfers")
                        .with(userToken()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.path").value("/transfers"));
    }

}
