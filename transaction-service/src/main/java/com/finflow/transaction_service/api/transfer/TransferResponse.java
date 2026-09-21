package com.finflow.transaction_service.api.transfer;

import java.math.BigDecimal;
import java.util.UUID;

public record TransferResponse(
        UUID transactionId,
        UUID sourceAccountId,
        UUID destinationAccountId,
        BigDecimal amount,
        String currency,
        String status
) {
}
