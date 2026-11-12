package com.finflow.transaction_service.event;

import java.util.UUID;

/**
 * Published (at least once) when a transfer has been committed.
 *
 * @param eventId       stable ID of the outbox event; consumers deduplicate
 *                      on it because re-delivery is possible (see
 *                      {@link TransferCompletedHandler})
 * @param transactionId ID of the committed transaction, for correlation
 */
public record TransferCompletedEvent(
        UUID eventId,
        UUID transactionId,
        UUID sourceAccountId,
        UUID destinationAccountId
) {
}
