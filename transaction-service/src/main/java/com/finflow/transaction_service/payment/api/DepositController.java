package com.finflow.transaction_service.payment.api;

import com.finflow.transaction_service.payment.domain.Deposit;
import com.finflow.transaction_service.payment.service.CreateDepositCommand;
import com.finflow.transaction_service.payment.service.DepositService;
import com.finflow.transaction_service.service.OwnerIdentityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Deposits via Stripe Checkout.
 *
 * <p>{@code POST /deposits} creates a {@code PENDING} deposit and returns the
 * hosted checkout URL. The response does not credit anything: the balance
 * changes only when Stripe's webhook confirms the payment. Clients poll
 * {@code GET /deposits/{id}} (or the account) to observe {@code COMPLETED}.
 */
@RestController
@RequestMapping("/deposits")
@Tag(name = "Deposits", description = "Fund an account through Stripe Checkout")
public class DepositController {

    private final DepositService depositService;
    private final OwnerIdentityService ownerIdentityService;

    public DepositController(
            DepositService depositService,
            OwnerIdentityService ownerIdentityService
    ) {
        this.depositService = depositService;
        this.ownerIdentityService = ownerIdentityService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Start a deposit into one of the authenticated user's accounts")
    public DepositResponse createDeposit(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateDepositRequest request
    ) {
        UUID requestingOwnerId = ownerIdentityService.resolveOwnerId(jwt);

        Deposit deposit = depositService.createDeposit(
                new CreateDepositCommand(
                        request.accountId(),
                        request.amount(),
                        request.currency(),
                        idempotencyKey
                ),
                requestingOwnerId
        );

        return DepositResponse.from(deposit);
    }

    @GetMapping("/{depositId}")
    @Operation(summary = "Get one of the authenticated user's deposits by ID")
    public DepositResponse getDeposit(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID depositId
    ) {
        UUID requestingOwnerId = ownerIdentityService.resolveOwnerId(jwt);

        return DepositResponse.from(
                depositService.getOwnedDeposit(depositId, requestingOwnerId)
        );
    }
}
