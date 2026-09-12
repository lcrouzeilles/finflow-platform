package com.finflow.transaction_service.api.account;

import com.finflow.transaction_service.domain.account.Account;
import com.finflow.transaction_service.service.AccountService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse createAccount(
            @Valid @RequestBody CreateAccountRequest request
    ) {
        Account account = accountService.createAccount(
                request.ownerId(),
                request.currency()
        );

        return AccountResponse.from(account);
    }
}
