package com.finflow.transaction_service.repository;

import com.finflow.transaction_service.domain.transaction.Transaction;
import com.finflow.transaction_service.domain.transaction.TransactionStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class TransactionRepositoryTest {

    @Autowired
    private TransactionRepository transactionRepository;

    @Test
    void shouldPersistAndRetrieveTransaction() {
        UUID sourceAccountId = UUID.randomUUID();
        UUID destinationAccountId = UUID.randomUUID();

        Transaction transaction = new Transaction(
                sourceAccountId,
                destinationAccountId,
                new BigDecimal("100.0000"),
                "ARS"
        );

        Transaction saved = transactionRepository.save(transaction);

        assertThat(saved.getId()).isNotNull();

        Transaction retrieved = transactionRepository
                .findById(saved.getId())
                .orElseThrow();

        assertThat(retrieved.getSourceAccountId())
                .isEqualTo(sourceAccountId);

        assertThat(retrieved.getDestinationAccountId())
                .isEqualTo(destinationAccountId);

        assertThat(retrieved.getAmount())
                .isEqualByComparingTo("100.0000");

        assertThat(retrieved.getCurrency())
                .isEqualTo("ARS");

        assertThat(retrieved.getStatus())
                .isEqualTo(TransactionStatus.PENDING);
    }

    @Test
    void shouldSaveAndFindTransaction() {
        Transaction transaction = new Transaction(
                UUID.randomUUID(),
                UUID.randomUUID(),
                new BigDecimal("1250.5000"),
                "ARS"
        );

        Transaction saved = transactionRepository.save(transaction);

        Optional<Transaction> found =
                transactionRepository.findById(saved.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getAmount())
                .isEqualByComparingTo("1250.5000");
        assertThat(found.get().getCurrency())
                .isEqualTo("ARS");
        assertThat(found.get().getStatus())
                .isEqualTo(TransactionStatus.PENDING);
    }

}