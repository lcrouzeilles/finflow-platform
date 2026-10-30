package com.finflow.transaction_service.api.account;

import com.finflow.transaction_service.domain.account.Account;
import com.finflow.transaction_service.service.AccountService;
import com.finflow.transaction_service.service.OwnerIdentityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/accounts")
@Tag(name = "Accounts", description = "Account management operations")
public class AccountController {

    private final AccountService accountService;
    private final OwnerIdentityService ownerIdentityService;

    public AccountController(
            AccountService accountService,
            OwnerIdentityService ownerIdentityService
    ) {
        this.accountService = accountService;
        this.ownerIdentityService = ownerIdentityService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an account owned by the authenticated user")
    public AccountResponse createAccount(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateAccountRequest request
    ) {
        UUID ownerId = ownerIdentityService.resolveOwnerId(jwt);

        Account account = accountService.createAccount(
                ownerId,
                request.currency()
        );

        return AccountResponse.from(account);
    }

    @GetMapping("/{accountId}")
    @Operation(summary = "Get one of the authenticated user's accounts by ID")
    public AccountResponse getAccount(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID accountId
    ) {
        UUID ownerId = ownerIdentityService.resolveOwnerId(jwt);

        Account account = accountService.getOwnedAccount(accountId, ownerId);

        return AccountResponse.from(account);
    }

    @GetMapping("/admin/test")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<String> adminTest() {
        return ResponseEntity.ok("Admin access granted");
    }

}
