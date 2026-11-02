package com.finflow.transaction_service.payment.service;

import java.util.UUID;

/**
 * Also thrown for deposits of other owners, so the response does not reveal
 * whether the id exists.
 */
public class DepositNotFoundException extends RuntimeException {

    public DepositNotFoundException(UUID depositId) {
        super("Deposit not found with id: " + depositId);
    }
}
