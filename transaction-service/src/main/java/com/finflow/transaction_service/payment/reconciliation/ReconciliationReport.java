package com.finflow.transaction_service.payment.reconciliation;

import java.util.Map;

/**
 * Outcome of one run.
 *
 * @param window           the compared period
 * @param providerSessions Checkout Sessions the provider listed
 * @param localDeposits    deposits created locally in the window
 * @param found            discrepancies detected in this run, by type
 * @param recorded         how many of them were new (not already recorded)
 */
public record ReconciliationReport(
        ReconciliationWindow window,
        int providerSessions,
        int localDeposits,
        Map<DiscrepancyType, Integer> found,
        int recorded
) {

    public int totalFound() {
        return found.values().stream().mapToInt(Integer::intValue).sum();
    }
}
