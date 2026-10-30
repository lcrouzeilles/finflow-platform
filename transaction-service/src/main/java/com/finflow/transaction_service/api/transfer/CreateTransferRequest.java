package com.finflow.transaction_service.api.transfer;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
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
        // Balances are stored as NUMERIC(19,4); amounts are money with at
        // most 2 decimals. Anything else is rejected explicitly instead of
        // being silently rounded by the database or failing with a 500.
        @Digits(
                integer = 15,
                fraction = 2,
                message = "Amount must have at most 15 integer digits and 2 decimal places"
        )
        BigDecimal amount
) {
}
