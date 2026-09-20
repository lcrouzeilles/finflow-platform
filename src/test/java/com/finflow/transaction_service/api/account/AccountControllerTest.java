package com.finflow.transaction_service.api.account;

import com.finflow.transaction_service.config.TestSecurityConfig;
import com.finflow.transaction_service.domain.account.Account;
import com.finflow.transaction_service.domain.account.AccountNumber;
import com.finflow.transaction_service.domain.account.Owner;
import com.finflow.transaction_service.repository.AccountRepository;
import com.finflow.transaction_service.repository.OwnerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Import(TestSecurityConfig.class)
class AccountControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OwnerRepository ownerRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Test
    void shouldCreateAccount() throws Exception {
        Owner owner = ownerRepository.saveAndFlush(new Owner());

        CreateAccountRequest request = new CreateAccountRequest(
                owner.getId(),
                "ARS"
        );

        mockMvc.perform(
                        post("/accounts")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request))
                )
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.ownerId").value(owner.getId().toString()))
                .andExpect(jsonPath("$.accountNumber").isString())
                .andExpect(jsonPath("$.currency").value("ARS"))
                .andExpect(jsonPath("$.balance").value(0))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void shouldRejectRequestWithoutOwnerId() throws Exception {
        String requestBody = """
            {
              "currency": "ARS"
            }
            """;

        mockMvc.perform(
                        post("/accounts")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody)
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.ownerId").exists());
    }

    @Test
    void shouldRejectUnsupportedCurrency() throws Exception {
        String requestBody = """
            {
              "ownerId": "7f3c2a91-6d84-4b17-9e52-1a6f8c3d0b45",
              "currency": "EUR"
            }
            """;

        mockMvc.perform(
                        post("/accounts")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody)
                )
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRejectRequestWithoutCurrency() throws Exception {
        String requestBody = """
            {
              "ownerId": "7f3c2a91-6d84-4b17-9e52-1a6f8c3d0b45"
            }
            """;

        mockMvc.perform(
                        post("/accounts")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody)
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message")
                        .value("Request validation failed"))
                .andExpect(jsonPath("$.path").value("/accounts"))
                .andExpect(jsonPath("$.fieldErrors.currency")
                        .value("must not be null"));
    }

    @Test
    void shouldReturnNotFoundWhenOwnerDoesNotExist() throws Exception {
        String requestBody = """
            {
              "ownerId": "11111111-1111-1111-1111-111111111111",
              "currency": "ARS"
            }
            """;

        mockMvc.perform(
                        post("/accounts")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody)
                )
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message")
                        .value("Owner not found with id: 11111111-1111-1111-1111-111111111111"))
                .andExpect(jsonPath("$.path").value("/accounts"));
    }

    @Test
    void shouldRetrieveAccountById() throws Exception {
        Owner owner = ownerRepository.saveAndFlush(new Owner());

        Account account = new Account(
                new AccountNumber("FF-GET12345"),
                "ARS",
                owner.getId()
        );

        Account savedAccount = accountRepository.saveAndFlush(account);

        mockMvc.perform(get("/accounts/{accountId}", savedAccount.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id")
                        .value(savedAccount.getId().toString()))
                .andExpect(jsonPath("$.ownerId")
                        .value(owner.getId().toString()))
                .andExpect(jsonPath("$.accountNumber")
                        .value("FF-GET12345"))
                .andExpect(jsonPath("$.currency")
                        .value("ARS"))
                .andExpect(jsonPath("$.balance")
                        .value(0))
                .andExpect(jsonPath("$.status")
                        .value("ACTIVE"));
    }

    @Test
    void shouldReturnNotFoundWhenAccountDoesNotExist() throws Exception {
        UUID nonexistentAccountId = UUID.fromString(
                "22222222-2222-2222-2222-222222222222"
        );

        mockMvc.perform(get("/accounts/{accountId}", nonexistentAccountId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message")
                        .value("Account not found with id: "
                                + nonexistentAccountId))
                .andExpect(jsonPath("$.path")
                        .value("/accounts/" + nonexistentAccountId));
    }

}
