package com.finflow.transaction_service.domain.account;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.Getter;

import java.util.Objects;
import java.util.regex.Pattern;

@Getter
@Embeddable
public class AccountNumber {

    private static final String PREFIX = "FF-";
    private static final Pattern FORMAT = Pattern.compile("^FF-[A-Z0-9]{8}$");

    @Column(name = "account_number", nullable = false, unique = true, length = 11)
    private String value;

    protected AccountNumber() {
        // Required by JPA
    }

    public AccountNumber(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Account number cannot be null or blank"
            );
        }

        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Invalid account number format"
            );
        }

        this.value = value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AccountNumber that)) return false;
        return value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
