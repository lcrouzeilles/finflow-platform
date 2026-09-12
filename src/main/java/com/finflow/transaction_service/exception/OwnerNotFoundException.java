package com.finflow.transaction_service.exception;

import java.util.UUID;

public class OwnerNotFoundException extends RuntimeException {

    public OwnerNotFoundException(UUID ownerId) {
        super("Owner not found with id: " + ownerId);
    }
}
