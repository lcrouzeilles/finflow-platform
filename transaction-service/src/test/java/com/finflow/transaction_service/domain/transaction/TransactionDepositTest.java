package com.finflow.transaction_service.domain.transaction;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionDepositTest {

    @Test
    void transferConstructorKeepsTypeTransfer() {
        Transaction transfer = new Transaction(
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"), "USD", "k-1");

        assertThat(transfer.getType()).isEqualTo(TransactionType.TRANSFER);
        assertThat(transfer.getSourceAccountId()).isNotNull();
    }

    @Test
    void depositHasNoSourceAndIsPending() {
        UUID destination = UUID.randomUUID();

        Transaction deposit = Transaction.deposit(
                destination, new BigDecimal("25.50"), "USD", "deposit:" + UUID.randomUUID());

        assertThat(deposit.getType()).isEqualTo(TransactionType.DEPOSIT);
        assertThat(deposit.getSourceAccountId()).isNull();
        assertThat(deposit.getDestinationAccountId()).isEqualTo(destination);
        assertThat(deposit.getAmount()).isEqualByComparingTo("25.50");
        assertThat(deposit.getStatus()).isEqualTo(TransactionStatus.PENDING);

        deposit.markAsCompleted();

        assertThat(deposit.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
    }

    @Test
    void depositValidatesItsArguments() {
        assertThatThrownBy(() -> Transaction.deposit(null, new BigDecimal("1.00"), "USD", "k"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Transaction.deposit(UUID.randomUUID(), BigDecimal.ZERO, "USD", "k"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Transaction.deposit(UUID.randomUUID(), new BigDecimal("1.00"), "US", "k"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Transaction.deposit(UUID.randomUUID(), new BigDecimal("1.00"), "USD", " "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
