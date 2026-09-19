package com.finflow.transaction_service.api.account;

import com.finflow.transaction_service.domain.account.Account;
import com.finflow.transaction_service.service.AccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/accounts")
@Tag(name = "Accounts", description = "Account management operations")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an account")
    public AccountResponse createAccount(
            @Valid @RequestBody CreateAccountRequest request
    ) {
        Account account = accountService.createAccount(
                request.ownerId(),
                request.currency()
        );

        return AccountResponse.from(account);
    }

    @GetMapping("/{accountId}")
    @Operation(summary = "Get an account by ID")
    public AccountResponse getAccount(@PathVariable UUID accountId) {
        Account account = accountService.getAccount(accountId);

        return AccountResponse.from(account);
    }

}
