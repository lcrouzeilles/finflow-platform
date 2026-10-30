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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
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

    private static RequestPostProcessor tokenFor(String subject) {
        return jwt().jwt(jwt -> jwt.subject(subject));
    }

    private Owner ownerWithSubject(String subject) {
        return ownerRepository.saveAndFlush(new Owner(subject));
    }

    @Test
    void shouldCreateAccountForAuthenticatedOwner() throws Exception {
        Owner owner = ownerWithSubject("subject-create-001");

        CreateAccountRequest request = new CreateAccountRequest("ARS");

        mockMvc.perform(
                        post("/accounts")
                                .with(tokenFor("subject-create-001"))
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
    void shouldProvisionOwnerOnFirstAuthenticatedRequest() throws Exception {
        String subject = "subject-first-time-001";

        assertThat(ownerRepository.findByKeycloakSubject(subject)).isEmpty();

        mockMvc.perform(
                        post("/accounts")
                                .with(tokenFor(subject))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                    {
                                      "currency": "USD"
                                    }
                                    """)
                )
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerId").exists());

        Owner provisioned = ownerRepository.findByKeycloakSubject(subject)
                .orElseThrow();

        assertThat(accountRepository.findAll())
                .anySatisfy(account ->
                        assertThat(account.getOwnerId())
                                .isEqualTo(provisioned.getId())
                );
    }

    @Test
    void shouldIgnoreOwnerIdSuppliedInRequestBody() throws Exception {
        Owner victim = ownerWithSubject("subject-victim-001");
        Owner attacker = ownerWithSubject("subject-attacker-001");

        mockMvc.perform(
                        post("/accounts")
                                .with(tokenFor("subject-attacker-001"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                    {
                                      "ownerId": "%s",
                                      "currency": "ARS"
                                    }
                                    """.formatted(victim.getId()))
                )
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerId").value(attacker.getId().toString()));
    }

    @Test
    void shouldRejectUnauthenticatedAccountCreation() throws Exception {
        long accountsBefore = accountRepository.count();

        mockMvc.perform(
                        post("/accounts")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                    {
                                      "currency": "ARS"
                                    }
                                    """)
                )
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));

        assertThat(accountRepository.count()).isEqualTo(accountsBefore);
    }

    @Test
    void shouldRejectUnsupportedCurrency() throws Exception {
        String requestBody = """
            {
              "currency": "EUR"
            }
            """;

        mockMvc.perform(
                        post("/accounts")
                                .with(tokenFor("subject-currency-001"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody)
                )
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRejectRequestWithoutCurrency() throws Exception {
        String requestBody = """
            {
            }
            """;

        mockMvc.perform(
                        post("/accounts")
                                .with(tokenFor("subject-currency-002"))
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
    void shouldRetrieveOwnAccountById() throws Exception {
        Owner owner = ownerWithSubject("subject-get-001");

        Account account = new Account(
                new AccountNumber("FF-GET12345"),
                "ARS",
                owner.getId()
        );

        Account savedAccount = accountRepository.saveAndFlush(account);

        mockMvc.perform(get("/accounts/{accountId}", savedAccount.getId())
                        .with(tokenFor("subject-get-001")))
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
    void shouldNotRevealAccountOwnedByAnotherOwner() throws Exception {
        Owner owner = ownerWithSubject("subject-owner-001");
        ownerWithSubject("subject-intruder-001");

        Account savedAccount = accountRepository.saveAndFlush(
                new Account(
                        new AccountNumber("FF-IDOR1234"),
                        "ARS",
                        owner.getId()
                )
        );

        // Same response as for a nonexistent account: the intruder learns
        // nothing about the account (not even that the ID exists).
        mockMvc.perform(get("/accounts/{accountId}", savedAccount.getId())
                        .with(tokenFor("subject-intruder-001")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message")
                        .value("Account not found with id: "
                                + savedAccount.getId()))
                .andExpect(jsonPath("$.balance").doesNotExist())
                .andExpect(jsonPath("$.accountNumber").doesNotExist())
                .andExpect(jsonPath("$.ownerId").doesNotExist());
    }

    @Test
    void shouldRejectUnauthenticatedAccountRetrieval() throws Exception {
        Owner owner = ownerWithSubject("subject-get-002");

        Account savedAccount = accountRepository.saveAndFlush(
                new Account(
                        new AccountNumber("FF-GET54321"),
                        "ARS",
                        owner.getId()
                )
        );

        mockMvc.perform(get("/accounts/{accountId}", savedAccount.getId()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.balance").doesNotExist());
    }

    @Test
    void shouldReturnNotFoundWhenAccountDoesNotExist() throws Exception {
        UUID nonexistentAccountId = UUID.fromString(
                "22222222-2222-2222-2222-222222222222"
        );

        mockMvc.perform(get("/accounts/{accountId}", nonexistentAccountId)
                        .with(tokenFor("subject-get-003")))
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
