package com.finflow.transaction_service.payment.reconciliation;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Window of the reconciliation job ({@code finflow.reconciliation.*}; the
 * schedule itself is {@code finflow.reconciliation.cron}).
 *
 * @param lookback how far back from the end of the window to compare
 * @param overlap  extra time added to the start so successive runs overlap
 *                 and nothing falls between two windows
 * @param settle   gap left before "now" so webhooks still in flight are not
 *                 reported as lost
 */
@ConfigurationProperties(prefix = "finflow.reconciliation")
@Validated
public record ReconciliationProperties(
        @DefaultValue("PT48H") @NotNull Duration lookback,
        @DefaultValue("PT1H") @NotNull Duration overlap,
        @DefaultValue("PT5M") @NotNull Duration settle
) {
}
