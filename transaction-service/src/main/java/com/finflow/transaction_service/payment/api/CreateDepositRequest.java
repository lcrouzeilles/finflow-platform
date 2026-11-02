package com.finflow.transaction_service.payment.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The owner is never taken from the body: it is derived from the
 * authenticated token, and the account must belong to that owner.
 */
public record CreateDepositRequest(
        @NotNull(message = "Account ID cannot be null")
        UUID accountId,

        @NotNull(message = "Amount cannot be null")
        @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
        @Digits(integer = 15, fraction = 2,
                message = "Amount must have at most 15 integer digits and 2 decimal places")
        BigDecimal amount,

        @NotNull(message = "Currency cannot be null")
        @Pattern(regexp = "[A-Z]{3}", message = "Currency must be a 3-letter ISO 4217 code")
        String currency
) {
}
