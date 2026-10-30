package com.finflow.transaction_service.api.account;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * The owner is never taken from the body: it is derived from the
 * authenticated token in the controller.
 */
public record CreateAccountRequest(
        @NotNull
        @Pattern(regexp = "ARS|USD", message = "Currency must be ARS or USD")
        String currency
) {
}
