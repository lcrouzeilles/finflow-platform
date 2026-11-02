package com.finflow.transaction_service.payment.event;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Published when a deposit has been credited to the ledger. The same
 * payload is stored in the outbox (relayed to Kafka at least once; the
 * {@code eventId} equals the outbox row id so consumers can deduplicate)
 * and published in-process for the AFTER_COMMIT cache eviction.
 */
public record DepositCompletedEvent(
        UUID eventId,
        UUID depositId,
        UUID transactionId,
        UUID accountId,
        BigDecimal amount,
        String currency
) {
}
