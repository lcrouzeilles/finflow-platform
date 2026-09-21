package com.finflow.transaction_service.domain;

import com.finflow.transaction_service.domain.account.AccountNumber;
import com.finflow.transaction_service.domain.account.AccountNumberGenerator;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class AccountNumberGeneratorTest {

    private static final Pattern ACCOUNT_NUMBER_PATTERN =
            Pattern.compile("^FF-[A-Z0-9]{8}$");

    private final AccountNumberGenerator generator =
            new AccountNumberGenerator();

    @Test
    void shouldGenerateAccountNumberWithValidFormat() {
        AccountNumber accountNumber = generator.generate();

        assertNotNull(accountNumber);
        assertTrue(
                ACCOUNT_NUMBER_PATTERN.matcher(accountNumber.getValue()).matches()
        );
    }

    @Test
    void shouldGenerateAccountNumberWithCorrectLength() {
        AccountNumber accountNumber = generator.generate();

        assertEquals(11, accountNumber.getValue().length());
    }

    @Test
    void shouldGenerateAccountNumberWithFinFlowPrefix() {
        AccountNumber accountNumber = generator.generate();

        assertTrue(accountNumber.getValue().startsWith("FF-"));
    }

    @Test
    void shouldGenerateAccountNumberWithEightRandomCharacters() {
        AccountNumber accountNumber = generator.generate();

        String randomPart = accountNumber.getValue().substring(3);

        assertEquals(8, randomPart.length());
        assertTrue(randomPart.matches("[A-Z0-9]{8}"));
    }

    @Test
    void shouldGenerateDifferentAccountNumbers() {
        AccountNumber first = generator.generate();
        AccountNumber second = generator.generate();

        assertNotEquals(first, second);
    }

    @Test
    void shouldGenerateUniqueAccountNumbersAcrossMultipleGenerations() {
        Set<AccountNumber> generatedNumbers = new HashSet<>();

        for (int i = 0; i < 1000; i++) {
            generatedNumbers.add(generator.generate());
        }

        assertEquals(1000, generatedNumbers.size());
    }

    @Test
    void shouldGenerateValidAccountNumberObjects() {
        AccountNumber accountNumber = generator.generate();

        assertDoesNotThrow(() ->
                new AccountNumber(accountNumber.getValue())
        );
    }
}