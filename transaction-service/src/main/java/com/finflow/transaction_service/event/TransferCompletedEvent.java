package com.finflow.transaction_service.event;

import java.util.UUID;

public record TransferCompletedEvent(
        UUID sourceAccountId,
        UUID destinationAccountId
) {
}