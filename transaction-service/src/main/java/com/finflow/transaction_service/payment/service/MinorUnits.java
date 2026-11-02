package com.finflow.transaction_service.payment.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Conversion between the ledger's decimal amounts and the provider's
 * integer minor units. Used only at the Stripe boundary; the ledger never
 * stores minor units.
 *
 * <p>Only two-decimal currencies are supported (USD). Zero-decimal (JPY) or
 * three-decimal (KWD) currencies would need a per-currency exponent.
 */
public final class MinorUnits {

    static final int SCALE = 2;

    private MinorUnits() {
    }

    public static long toMinorUnits(BigDecimal amount) {
        if (amount == null) {
            throw new IllegalArgumentException("Amount cannot be null");
        }

        try {
            return amount.setScale(SCALE, RoundingMode.UNNECESSARY)
                    .movePointRight(SCALE)
                    .longValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                    "Amount " + amount + " cannot be expressed in minor units",
                    exception
            );
        }
    }

    public static BigDecimal fromMinorUnits(long amountMinor) {
        return BigDecimal.valueOf(amountMinor).movePointLeft(SCALE);
    }
}
