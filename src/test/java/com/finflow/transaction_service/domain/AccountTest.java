package com.finflow.transaction_service.domain;
import com.finflow.transaction_service.domain.account.Account;
import com.finflow.transaction_service.domain.account.AccountNumber;
import com.finflow.transaction_service.domain.account.AccountNumberGenerator;
import com.finflow.transaction_service.domain.account.AccountStatus;
import com.finflow.transaction_service.exception.InsufficientFundsException;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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
        Account account = createAccount();

        account.deposit(new BigDecimal("1000.00"));
        account.withdraw(new BigDecimal("250.00"));

        assertThat(account.getBalance())
                .isEqualByComparingTo("750.00");
    }

    @Test
    void shouldAllowWithdrawOfEntireBalance() {
        Account account = createAccount();

        account.deposit(new BigDecimal("1000.00"));
        account.withdraw(new BigDecimal("1000.00"));

        assertTrue(
                account.getBalance().compareTo(BigDecimal.ZERO) == 0
        );
    }

    @Test
    void shouldRejectNullWithdrawalAmount() {
        Account account = createAccount();

        assertThrows(
                IllegalArgumentException.class,
                () -> account.withdraw(null)
        );
    }

    @Test
    void shouldRejectZeroWithdrawalAmount() {
        Account account = createAccount();

        assertThrows(
                IllegalArgumentException.class,
                () -> account.withdraw(BigDecimal.ZERO)
        );
    }

    @Test
    void shouldRejectNegativeWithdrawalAmount() {
        Account account = createAccount();

        assertThrows(
                IllegalArgumentException.class,
                () -> account.withdraw(new BigDecimal("-100.00"))
        );
    }

    @Test
    void shouldRejectWithdrawalGreaterThanBalance() {
        Account account = createAccount();

        assertThrows(
                InsufficientFundsException.class,
                () -> account.withdraw(new BigDecimal("1000.01"))
        );
    }

    @Test
    void shouldDepositAmountIntoBalance() {
        Account account = createAccount();

        account.deposit(new BigDecimal("1000.00"));
        account.deposit(new BigDecimal("250.00"));

        assertThat(account.getBalance())
                .isEqualByComparingTo("1250.00");
    }

    @Test
    void shouldRejectNullDepositAmount() {
        Account account = createAccount();

        assertThrows(
                IllegalArgumentException.class,
                () -> account.deposit(null)
        );
    }

    @Test
    void shouldRejectZeroDepositAmount() {
        Account account = createAccount();

        assertThrows(
                IllegalArgumentException.class,
                () -> account.deposit(BigDecimal.ZERO)
        );
    }

    @Test
    void shouldRejectNegativeDepositAmount() {
        Account account = createAccount();

        assertThrows(
                IllegalArgumentException.class,
                () -> account.deposit(new BigDecimal("-100.00"))
        );
    }

    @Test
    void shouldRejectNullOwnerId() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new Account(
                        new AccountNumber("FF-7K4M9P2X"),
                        "USD",
                        null
                )
        );
    }

    @Test
    void shouldKeepBalanceUnchangedWhenWithdrawalFails() {
        Account account = createAccount();

        account.deposit(new BigDecimal("100.00"));

        assertThrows(
                InsufficientFundsException.class,
                () -> account.withdraw(new BigDecimal("100.01"))
        );

        assertThat(account.getBalance())
                .isEqualByComparingTo("100.00");
    }

    @Test
    void shouldStartWithZeroBalance() {
        Account account = createAccount();

        assertThat(account.getBalance())
                .isEqualByComparingTo("0.00");
    }

    private Account createAccount() {
        AccountNumberGenerator generator =
                new AccountNumberGenerator();

        return new Account(
                generator.generate(),
                "ARS",
                UUID.randomUUID()
        );
    }

}
