package com.finflow.transaction_service.payment.service;

/** A deposit request that is well-formed but not acceptable (422). */
public class InvalidDepositException extends RuntimeException {

    public InvalidDepositException(String message) {
        super(message);
    }
}
