package com.finflow.transaction_service.payment.reconciliation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Runs the reconciliation on {@code finflow.reconciliation.cron} (daily at
 * 03:00 by default). Disabled in the test profile, where
 * {@link ReconciliationService} is driven directly.
 */
@Component
@Profile("!test")
public class ReconciliationScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(ReconciliationScheduler.class);

    private final ReconciliationService reconciliationService;
    private final ReconciliationProperties properties;

    public ReconciliationScheduler(
            ReconciliationService reconciliationService,
            ReconciliationProperties properties
    ) {
        this.reconciliationService = reconciliationService;
        this.properties = properties;
    }

    @Scheduled(cron = "${finflow.reconciliation.cron:0 0 3 * * *}")
    public void run() {
        ReconciliationWindow window =
                ReconciliationWindow.endingBefore(Instant.now(), properties);

        try {
            ReconciliationReport report = reconciliationService.reconcile(window);

            if (report.totalFound() > 0) {
                log.warn("reconciliation finished with {} discrepancies ({} new): {}",
                        report.totalFound(), report.recorded(), report.found());
            }
        } catch (RuntimeException exception) {
            // Typically the provider is unavailable; the next run covers the
            // same period again thanks to the overlap.
            log.error("reconciliation run failed for window {}: {}",
                    window, exception.getMessage(), exception);
        }
    }
}
