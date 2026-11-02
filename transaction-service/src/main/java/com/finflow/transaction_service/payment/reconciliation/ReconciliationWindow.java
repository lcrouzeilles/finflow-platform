package com.finflow.transaction_service.payment.reconciliation;

import java.time.Instant;

/** The period a reconciliation run compares, {@code [from, to]}. */
public record ReconciliationWindow(Instant from, Instant to) {

    public ReconciliationWindow {
        if (from == null || to == null) {
            throw new IllegalArgumentException("Window bounds cannot be null");
        }
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("Window start must be before its end");
        }
    }

    /**
     * Ends {@code settle} before {@code now} (webhooks may still be in
     * flight) and starts {@code lookback + overlap} before that end, so
     * consecutive runs overlap.
     */
    public static ReconciliationWindow endingBefore(Instant now, ReconciliationProperties properties) {
        Instant to = now.minus(properties.settle());
        Instant from = to.minus(properties.lookback()).minus(properties.overlap());

        return new ReconciliationWindow(from, to);
    }
}
