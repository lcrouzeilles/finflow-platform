package com.finflow.transaction_service.exception;

import java.math.BigDecimal;

public class InsufficientFundsException extends RuntimeException {

    public InsufficientFundsException(
            BigDecimal requestedAmount,
            BigDecimal availableBalance
    ) {
        super(
                "Insufficient funds. Requested: "
                        + requestedAmount
                        + ", available: "
                        + availableBalance
        );
    }
}