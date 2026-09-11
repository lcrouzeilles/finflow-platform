package com.finflow.transaction_service.domain;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class AccountTest {

    @Test
    void shouldCreateAccountWithValidValues() {
        Account account = new Account(
                "ACC-123",
                new BigDecimal("1000.00"),
                "USD"
        );

        assertEquals("ACC-123", account.getAccountNumber());
        assertEquals(new BigDecimal("1000.00"), account.getBalance());
        assertEquals("USD", account.getCurrency());
        assertEquals(AccountStatus.ACTIVE, account.getStatus());
    }

    @Test
    void shouldRejectNullBalance() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new Account("ACC-123", null, "USD")
        );
    }

    @Test
    void shouldRejectNegativeBalance() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new Account(
                        "ACC-123",
                        new BigDecimal("-100.00"),
                        "USD"
                )
        );
    }

    @Test
    void shouldRejectNullAccountNumber() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new Account(
                        null,
                        new BigDecimal("1000.00"),
                        "USD"
                )
        );
    }

    @Test
    void shouldRejectBlankAccountNumber() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new Account(
                        "   ",
                        new BigDecimal("1000.00"),
                        "USD"
                )
        );
    }

    @Test
    void shouldRejectNullCurrency() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new Account(
                        "ACC-123",
                        new BigDecimal("1000.00"),
                        null
                )
        );
    }

    @Test
    void shouldRejectBlankCurrency() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new Account(
                        "ACC-123",
                        new BigDecimal("1000.00"),
                        "   "
                )
        );
    }

    @Test
    void shouldWithdrawAmountFromBalance() {
        Account account = new Account(
                "ACC-123",
                new BigDecimal("1000.00"),
                "USD"
        );

        account.withdraw(new BigDecimal("250.00"));

        assertEquals(
                new BigDecimal("750.00"),
                account.getBalance()
        );
    }

    @Test
    void shouldAllowWithdrawOfEntireBalance() {
        Account account = new Account(
                "ACC-123",
                new BigDecimal("1000.00"),
                "USD"
        );

        account.withdraw(new BigDecimal("1000.00"));

        assertTrue(
                account.getBalance().compareTo(BigDecimal.ZERO) == 0
        );
    }

    @Test
    void shouldRejectNullWithdrawalAmount() {
        Account account = new Account(
                "ACC-123",
                new BigDecimal("1000.00"),
                "USD"
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> account.withdraw(null)
        );
    }

    @Test
    void shouldRejectZeroWithdrawalAmount() {
        Account account = new Account(
                "ACC-123",
                new BigDecimal("1000.00"),
                "USD"
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> account.withdraw(BigDecimal.ZERO)
        );
    }

    @Test
    void shouldRejectNegativeWithdrawalAmount() {
        Account account = new Account(
                "ACC-123",
                new BigDecimal("1000.00"),
                "USD"
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> account.withdraw(new BigDecimal("-100.00"))
        );
    }

    @Test
    void shouldRejectWithdrawalGreaterThanBalance() {
        Account account = new Account(
                "ACC-123",
                new BigDecimal("1000.00"),
                "USD"
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> account.withdraw(new BigDecimal("1000.01"))
        );
    }

    @Test
    void shouldDepositAmountIntoBalance() {
        Account account = new Account(
                "ACC-123",
                new BigDecimal("1000.00"),
                "USD"
        );

        account.deposit(new BigDecimal("250.00"));

        assertEquals(
                new BigDecimal("1250.00"),
                account.getBalance()
        );
    }

    @Test
    void shouldRejectNullDepositAmount() {
        Account account = new Account(
                "ACC-123",
                new BigDecimal("1000.00"),
                "USD"
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> account.deposit(null)
        );
    }

    @Test
    void shouldRejectZeroDepositAmount() {
        Account account = new Account(
                "ACC-123",
                new BigDecimal("1000.00"),
                "USD"
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> account.deposit(BigDecimal.ZERO)
        );
    }

    @Test
    void shouldRejectNegativeDepositAmount() {
        Account account = new Account(
                "ACC-123",
                new BigDecimal("1000.00"),
                "USD"
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> account.deposit(new BigDecimal("-100.00"))
        );
    }
}
