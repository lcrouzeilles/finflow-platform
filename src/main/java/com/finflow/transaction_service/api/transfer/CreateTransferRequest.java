package com.finflow.transaction_service.api.transfer;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record CreateTransferRequest(

        @NotNull(message = "Source account ID cannot be null")
        UUID sourceAccountId,

        @NotNull(message = "Destination account ID cannot be null")
        UUID destinationAccountId,

        @NotNull(message = "Amount cannot be null")
        @DecimalMin(
                value = "0.01",
                message = "Amount must be greater than zero"
        )
        BigDecimal amount
) {
}
