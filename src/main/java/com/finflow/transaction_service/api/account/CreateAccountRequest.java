package com.finflow.transaction_service.api.account;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record CreateAccountRequest(
        @NotNull
        UUID ownerId,

        @NotNull
        @Pattern(regexp = "ARS|USD", message = "Currency must be ARS or USD")
        String currency
) {
}
