package com.finflow.transaction_service.api.transaction;

import com.finflow.transaction_service.api.transfer.CreateTransferRequest;
import com.finflow.transaction_service.api.transfer.TransferController;
import com.finflow.transaction_service.domain.transaction.Transaction;
import com.finflow.transaction_service.domain.transaction.TransactionStatus;
import com.finflow.transaction_service.domain.transaction.TransferRequest;
import com.finflow.transaction_service.exception.AccountNotFoundException;
import com.finflow.transaction_service.exception.InsufficientFundsException;
import com.finflow.transaction_service.service.TransferService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TransferController.class)
class TransferControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private TransferService transferService;

    @Test
    void shouldCreateTransfer() throws Exception {
        UUID sourceAccountId = UUID.randomUUID();
        UUID destinationAccountId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();

        CreateTransferRequest request = new CreateTransferRequest(
                sourceAccountId,
                destinationAccountId,
                new BigDecimal("150.00")
        );

        Transaction transaction = new Transaction(
                sourceAccountId,
                destinationAccountId,
                new BigDecimal("150.00"),
                "ARS"
        );

        transaction.markAsCompleted();

        when(transferService.transfer(any(TransferRequest.class)))
                .thenReturn(transaction);

        mockMvc.perform(
                        post("/transfers")
                                .contentType(APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request))
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
    void shouldRejectRequestWithoutSourceAccountId() throws Exception {
        String request = """
        {
          "destinationAccountId": "%s",
          "amount": 150.00
        }
        """.formatted(UUID.randomUUID());

        mockMvc.perform(post("/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
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
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
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
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
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
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
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
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
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

        when(transferService.transfer(any(TransferRequest.class)))
                .thenThrow(
                        new InsufficientFundsException(
                                new BigDecimal("150.00"),
                                new BigDecimal("100.00")
                        )
                );

        mockMvc.perform(post("/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
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

        when(transferService.transfer(any(TransferRequest.class)))
                .thenThrow(new AccountNotFoundException(sourceAccountId));

        mockMvc.perform(post("/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().is(404))
                .andExpect(jsonPath("$.status").value(404));
    }

}
