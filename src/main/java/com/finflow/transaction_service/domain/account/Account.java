package com.finflow.transaction_service.domain.account;

import jakarta.persistence.*;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "owner_id", nullable = false)
    @Getter
    private UUID ownerId;

    @Embedded
    private AccountNumber accountNumber;

    @Column(nullable = false, precision = 19, scale = 4)
    @Getter
    private BigDecimal balance;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountStatus status;

    protected Account() {
    }

    public Account(AccountNumber accountNumber, String currency, UUID ownerId) {
        //Domain invariants - an object cannot be created with these states
        if (ownerId == null) {
            throw new IllegalArgumentException("Owner ID cannot be null");
        }
        if (accountNumber == null) {
            throw new IllegalArgumentException("Account number cannot be null");
        }
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("Currency cannot be null or blank");
        }
        this.ownerId = ownerId;
        this.balance = BigDecimal.ZERO;
        this.accountNumber = accountNumber;
        this.currency = currency;
        this.status = AccountStatus.ACTIVE;
    }

    public void withdraw (BigDecimal amount) {
        //Business rules - the object is responsible for maintaining valid states
        if (amount == null) {
            throw new IllegalArgumentException("Amount cannot be null");
        }
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Amount to be withdrawn cannot be negative or zero");
        }
        if (amount.compareTo(balance) > 0) {
            throw new IllegalArgumentException("Amount to be withdrawn cannot be greater than balance");
        }
        balance = balance.subtract(amount);
    }

    public void deposit (BigDecimal amount) {
        //Business rules - the object is responsible for maintaining valid states
        if (amount == null) {
            throw new IllegalArgumentException("Amount cannot be null");
        }
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Amount to be deposited must be greater than zero");
        }
        balance = balance.add(amount);
    }
}