package com.finflow.transaction_service.domain;
import com.finflow.transaction_service.domain.account.Account;
import com.finflow.transaction_service.domain.account.AccountNumber;
import com.finflow.transaction_service.domain.account.AccountStatus;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AccountTest {

    @Test
    void shouldCreateAccountWithValidValues() {
        Account account = new Account(
                new AccountNumber("FF-7K4M9P2X"),
                "USD",
                UUID.fromString("7f3c2a91-6d84-4b17-9e52-1a6f8c3d0b45")
        );

        assertEquals("FF-7K4M9P2X", account.getAccountNumber().getValue());
        assertEquals("USD", account.getCurrency());
        assertEquals(AccountStatus.ACTIVE, account.getStatus());
    }

    @Test
    void shouldRejectNullAccountNumber() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new Account(
                        null,
                        "USD",
                        UUID.fromString("7f3c2a91-6d84-4b17-9e52-1a6f8c3d0b45")
                )
        );
    }

    @Test
    void shouldRejectBlankAccountNumber() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new Account(
                        new AccountNumber("   "),
                        "USD",
                        UUID.fromString("7f3c2a91-6d84-4b17-9e52-1a6f8c3d0b45")
                )
        );
    }

    @Test
    void shouldRejectNullCurrency() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new Account(
                        new AccountNumber("FF-7K4M9P2X"),
                        null,
                        UUID.fromString("7f3c2a91-6d84-4b17-9e52-1a6f8c3d0b45")
                )
        );
    }

    @Test
    void shouldRejectBlankCurrency() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new Account(
                        new AccountNumber("FF-7K4M9P2X"),
                        "   ",
                        UUID.fromString("7f3c2a91-6d84-4b17-9e52-1a6f8c3d0b45")
                )
        );
    }

    @Test
    void shouldWithdrawAmountFromBalance() {
        Account account = new Account(
                new AccountNumber("FF-7K4M9P2X"),
                "USD",
                UUID.fromString("7f3c2a91-6d84-4b17-9e52-1a6f8c3d0b45")
        );
        account.deposit(new BigDecimal("1000.00"));
        account.withdraw(new BigDecimal("250.00"));

        assertEquals(
                new BigDecimal("750.00"),
                account.getBalance()
        );
    }

    @Test
    void shouldAllowWithdrawOfEntireBalance() {
        Account account = new Account(
                new AccountNumber("FF-7K4M9P2X"),
                "USD",
                UUID.fromString("7f3c2a91-6d84-4b17-9e52-1a6f8c3d0b45")
        );
        account.deposit(new BigDecimal("1000.00"));
        account.withdraw(new BigDecimal("1000.00"));

        assertTrue(
                account.getBalance().compareTo(BigDecimal.ZERO) == 0
        );
    }

    @Test
    void shouldRejectNullWithdrawalAmount() {
        Account account = new Account(
                new AccountNumber("FF-7K4M9P2X"),
                "USD",
                UUID.fromString("7f3c2a91-6d84-4b17-9e52-1a6f8c3d0b45")
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> account.withdraw(null)
        );
    }

    @Test
    void shouldRejectZeroWithdrawalAmount() {
        Account account = new Account(
                new AccountNumber("FF-7K4M9P2X"),
                "USD",
                UUID.fromString("7f3c2a91-6d84-4b17-9e52-1a6f8c3d0b45")
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> account.withdraw(BigDecimal.ZERO)
        );
    }

    @Test
    void shouldRejectNegativeWithdrawalAmount() {
        Account account = new Account(
                new AccountNumber("FF-7K4M9P2X"),
                "USD",
                UUID.fromString("7f3c2a91-6d84-4b17-9e52-1a6f8c3d0b45")
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> account.withdraw(new BigDecimal("-100.00"))
        );
    }

    @Test
    void shouldRejectWithdrawalGreaterThanBalance() {
        Account account = new Account(
                new AccountNumber("FF-7K4M9P2X"),
                "USD",
                UUID.fromString("7f3c2a91-6d84-4b17-9e52-1a6f8c3d0b45")
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> account.withdraw(new BigDecimal("1000.01"))
        );
    }

    @Test
    void shouldDepositAmountIntoBalance() {
        Account account = new Account(
                new AccountNumber("FF-7K4M9P2X"),
                "USD",
                UUID.fromString("7f3c2a91-6d84-4b17-9e52-1a6f8c3d0b45")
        );
        account.deposit(new BigDecimal("1000.00"));
        account.deposit(new BigDecimal("250.00"));

        assertEquals(
                new BigDecimal("1250.00"),
                account.getBalance()
        );
    }

    @Test
    void shouldRejectNullDepositAmount() {
        Account account = new Account(
                new AccountNumber("FF-7K4M9P2X"),
                "USD",
                UUID.fromString("7f3c2a91-6d84-4b17-9e52-1a6f8c3d0b45")
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> account.deposit(null)
        );
    }

    @Test
    void shouldRejectZeroDepositAmount() {
        Account account = new Account(
                new AccountNumber("FF-7K4M9P2X"),
                "USD",
                UUID.fromString("7f3c2a91-6d84-4b17-9e52-1a6f8c3d0b45")
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> account.deposit(BigDecimal.ZERO)
        );
    }

    @Test
    void shouldRejectNegativeDepositAmount() {
        Account account = new Account(
                new AccountNumber("FF-7K4M9P2X"),
                "USD",
                UUID.fromString("7f3c2a91-6d84-4b17-9e52-1a6f8c3d0b45")
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> account.deposit(new BigDecimal("-100.00"))
        );
    }
}
