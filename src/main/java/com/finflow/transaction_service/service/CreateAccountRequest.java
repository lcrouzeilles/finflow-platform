package com.finflow.transaction_service.service;

import java.util.UUID;

public record CreateAccountRequest(
        UUID ownerId,
        String currency
) {
}
