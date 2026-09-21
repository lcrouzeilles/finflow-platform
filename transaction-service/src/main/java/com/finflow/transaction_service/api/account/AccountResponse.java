package com.finflow.transaction_service.api.account;

import com.finflow.transaction_service.domain.account.Account;

import java.math.BigDecimal;
import java.util.UUID;

public record AccountResponse(
        UUID id,
        UUID ownerId,
        String accountNumber,
        String currency,
        BigDecimal balance,
        String status
) {
    public static AccountResponse from(Account account) {
        return new AccountResponse(
                account.getId(),
                account.getOwnerId(),
                account.getAccountNumber().getValue(),
                account.getCurrency(),
                account.getBalance(),
                account.getStatus().name()
        );
    }
}