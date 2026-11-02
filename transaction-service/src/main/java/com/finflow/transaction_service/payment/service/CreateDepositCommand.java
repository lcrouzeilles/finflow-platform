package com.finflow.transaction_service.payment.service;

import java.math.BigDecimal;
import java.util.UUID;

public record CreateDepositCommand(
        UUID accountId,
        BigDecimal amount,
        String currency,
        String idempotencyKey
) {
}
