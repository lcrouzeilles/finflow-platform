package com.finflow.transaction_service.domain.account;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

@Component
public class AccountNumberGenerator {
    private static final String CHARACTERS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private static final int RANDOM_LENGTH = 8;

    private final SecureRandom random = new SecureRandom();

    public AccountNumber generate() {
        StringBuilder tail = new StringBuilder();

        for (int i = 0; i < 8; i++) {
            int index = random.nextInt(CHARACTERS.length());
            tail.append(CHARACTERS.charAt(index));
        }
        String value = "FF-" + tail;

        return new AccountNumber(value);
    }

}
