package com.finflow.transaction_service.api.transfer;

import com.finflow.transaction_service.domain.transaction.Transaction;
import com.finflow.transaction_service.domain.transaction.TransferRequest;
import com.finflow.transaction_service.service.OwnerIdentityService;
import com.finflow.transaction_service.service.TransferService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/transfers")
@RequiredArgsConstructor
@Tag(name = "Transfers")
public class TransferController {

    private final TransferService transferService;
    private final OwnerIdentityService ownerIdentityService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a financial transfer from one of the authenticated user's accounts")
    public TransferResponse transfer(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateTransferRequest request
    ) {
        UUID requestingOwnerId = ownerIdentityService.resolveOwnerId(jwt);

        Transaction transaction = transferService.transfer(
                new TransferRequest(
                        request.sourceAccountId(),
                        request.destinationAccountId(),
                        request.amount(),
                        idempotencyKey
                ),
                requestingOwnerId
        );

        return new TransferResponse(
                transaction.getId(),
                transaction.getSourceAccountId(),
                transaction.getDestinationAccountId(),
                transaction.getAmount(),
                transaction.getCurrency(),
                transaction.getStatus().name()
        );
    }
}
