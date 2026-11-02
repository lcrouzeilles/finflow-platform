package com.finflow.transaction_service.payment.service;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;

/**
 * Business limits for deposits ({@code finflow.payments.*}).
 *
 * @param supportedCurrencies currencies accepted for deposits (whitelist;
 *                            the Stripe account is not multi-currency)
 * @param minimumAmount       smallest accepted amount in major units;
 *                            Stripe rejects charges under USD 0.50, and the
 *                            service validates it locally so that the client
 *                            gets a 422 instead of a provider error
 */
@ConfigurationProperties(prefix = "finflow.payments")
@Validated
public record PaymentsProperties(
        @NotEmpty Set<String> supportedCurrencies,
        @NotNull @DecimalMin("0.01") BigDecimal minimumAmount
) {

    public boolean supports(String currency) {
        return currency != null
                && supportedCurrencies.contains(currency.toUpperCase(Locale.ROOT));
    }
}
