package com.finflow.transaction_service.payment.api;

import com.finflow.transaction_service.payment.domain.Deposit;

import java.math.BigDecimal;
import java.util.UUID;

public record DepositResponse(
        UUID depositId,
        UUID accountId,
        BigDecimal amount,
        String currency,
        String status,
        String checkoutUrl
) {

    public static DepositResponse from(Deposit deposit) {
        return new DepositResponse(
                deposit.getId(),
                deposit.getAccountId(),
                deposit.getAmount(),
                deposit.getCurrency(),
                deposit.getStatus().name(),
                deposit.getCheckoutUrl()
        );
    }
}
