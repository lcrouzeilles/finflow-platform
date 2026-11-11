package com.finflow.transaction_service.payment.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.finflow.transaction_service.domain.account.Account;
import com.finflow.transaction_service.domain.outbox.OutboxEvent;
import com.finflow.transaction_service.domain.transaction.Transaction;
import com.finflow.transaction_service.exception.AccountNotFoundException;
import com.finflow.transaction_service.payment.domain.Deposit;
import com.finflow.transaction_service.payment.domain.DepositRepository;
import com.finflow.transaction_service.payment.domain.DepositStatus;
import com.finflow.transaction_service.payment.event.DepositCompletedEvent;
import com.finflow.transaction_service.payment.event.DepositEventProducer;
import com.finflow.transaction_service.payment.reconciliation.DiscrepancyType;
import com.finflow.transaction_service.payment.reconciliation.ReconciliationDiscrepancy;
import com.finflow.transaction_service.payment.reconciliation.ReconciliationDiscrepancyRepository;
import com.finflow.transaction_service.repository.AccountRepository;
import com.finflow.transaction_service.repository.OutboxEventRepository;
import com.finflow.transaction_service.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The only code path that credits a deposit. Invoked by the webhook handler
 * inside its transaction, after the event has been claimed for
 * deduplication.
 *
 * <p>Locking order: deposit row ({@code FOR UPDATE}) then the account row
 * ({@code FOR UPDATE}, the same lock transfers take). Transfers lock two
 * accounts in UUID order and never a deposit, so the two paths cannot
 * deadlock. Completing and expiring the same session serialize on the
 * deposit row.
 *
 * <p>Terminal mismatches (unknown session, amount or currency different
 * from what was requested) are not retried by Stripe once acknowledged, so
 * they are recorded as reconciliation discrepancies for a human instead
 * of being silently logged.
 */
@Service
public class DepositCompletionService {

    private static final Logger log =
            LoggerFactory.getLogger(DepositCompletionService.class);

    static final String AGGREGATE_TYPE = "DEPOSIT";

    public enum Result {
        CREDITED,
        ALREADY_COMPLETED,
        EXPIRED,
        ALREADY_FINAL,
        UNKNOWN_SESSION,
        AMOUNT_MISMATCH,
        NOT_PENDING
    }

    private final DepositRepository depositRepository;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ReconciliationDiscrepancyRepository discrepancyRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public DepositCompletionService(
            DepositRepository depositRepository,
            AccountRepository accountRepository,
            TransactionRepository transactionRepository,
            OutboxEventRepository outboxEventRepository,
            ReconciliationDiscrepancyRepository discrepancyRepository,
            ApplicationEventPublisher eventPublisher,
            ObjectMapper objectMapper
    ) {
        this.depositRepository = depositRepository;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.discrepancyRepository = discrepancyRepository;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    /**
     * Credits the deposit behind {@code sessionId} if it is still pending
     * and the paid amount matches what was requested.
     *
     * @param amountMinor     {@code amount_total} reported by Stripe
     * @param currency        currency reported by Stripe (any case)
     * @param paymentIntentId {@code pi_...}, may be null
     */
    @Transactional
    public Result complete(
            String sessionId,
            Long amountMinor,
            String currency,
            String paymentIntentId
    ) {
        Deposit deposit = depositRepository
                .findByStripeSessionIdForUpdate(sessionId)
                .orElse(null);

        if (deposit == null) {
            recordDiscrepancy(
                    DiscrepancyType.UNKNOWN_SESSION,
                    null,
                    sessionId,
                    "checkout.session.completed received for a session this service "
                            + "did not create (amount_total=" + amountMinor
                            + ", currency=" + currency + ")"
            );
            log.warn("Paid Checkout Session {} is unknown; recorded as discrepancy", sessionId);
            return Result.UNKNOWN_SESSION;
        }

        if (deposit.getStatus() == DepositStatus.COMPLETED) {
            return Result.ALREADY_COMPLETED;
        }

        if (!deposit.isPending()) {
            log.error("Paid Checkout Session {} belongs to deposit {} in status {}; not credited",
                    sessionId, deposit.getId(), deposit.getStatus());
            return Result.NOT_PENDING;
        }

        if (!amountMatches(deposit, amountMinor, currency)) {
            recordDiscrepancy(
                    DiscrepancyType.AMOUNT_MISMATCH,
                    deposit.getId(),
                    sessionId,
                    "requested " + deposit.getAmount().toPlainString() + " "
                            + deposit.getCurrency() + " but Stripe reports amount_total="
                            + amountMinor + " currency=" + currency
            );
            log.error("Checkout Session {} paid a different amount than deposit {} requested; not credited",
                    sessionId, deposit.getId());
            return Result.AMOUNT_MISMATCH;
        }

        Account account = accountRepository
                .findByIdForUpdate(deposit.getAccountId())
                .orElseThrow(() -> new AccountNotFoundException(deposit.getAccountId()));

        account.deposit(deposit.getAmount());
        accountRepository.save(account);

        Transaction ledgerEntry = Transaction.deposit(
                account.getId(),
                deposit.getAmount(),
                deposit.getCurrency(),
                ledgerIdempotencyKey(deposit)
        );
        ledgerEntry.markAsCompleted();
        Transaction savedEntry = transactionRepository.save(ledgerEntry);

        deposit.markCompleted(savedEntry.getId(), paymentIntentId);
        depositRepository.save(deposit);

        UUID eventId = UUID.randomUUID();

        DepositCompletedEvent event = new DepositCompletedEvent(
                eventId,
                deposit.getId(),
                savedEntry.getId(),
                account.getId(),
                deposit.getAmount(),
                deposit.getCurrency()
        );

        outboxEventRepository.save(new OutboxEvent(
                eventId,
                AGGREGATE_TYPE,
                deposit.getId(),
                DepositEventProducer.EVENT_TYPE,
                serialize(event)
        ));

        // Kafka delivery goes through the outbox; the in-process event feeds
        // the AFTER_COMMIT cache eviction.
        eventPublisher.publishEvent(event);

        log.info("Deposit {} credited {} {} to account {} (ledger {})",
                deposit.getId(), deposit.getAmount(), deposit.getCurrency(),
                account.getId(), savedEntry.getId());

        return Result.CREDITED;
    }

    /** Marks the deposit behind an expired session, if it is still pending. */
    @Transactional
    public Result expire(String sessionId) {
        Deposit deposit = depositRepository
                .findByStripeSessionIdForUpdate(sessionId)
                .orElse(null);

        if (deposit == null) {
            log.warn("Expired Checkout Session {} is unknown; ignored", sessionId);
            return Result.UNKNOWN_SESSION;
        }

        if (!deposit.isPending()) {
            return Result.ALREADY_FINAL;
        }

        deposit.markExpired();
        depositRepository.save(deposit);

        return Result.EXPIRED;
    }

    static String ledgerIdempotencyKey(Deposit deposit) {
        return "deposit:" + deposit.getId();
    }

    private static boolean amountMatches(Deposit deposit, Long amountMinor, String currency) {
        if (amountMinor == null || currency == null) {
            return false;
        }

        BigDecimal paid = MinorUnits.fromMinorUnits(amountMinor);

        return paid.compareTo(deposit.getAmount()) == 0
                && deposit.getCurrency().equalsIgnoreCase(currency);
    }

    private void recordDiscrepancy(
            DiscrepancyType type,
            UUID depositId,
            String sessionId,
            String detail
    ) {
        if (discrepancyRepository.existsByTypeAndStripeSessionId(type, sessionId)) {
            return;
        }

        discrepancyRepository.save(
                new ReconciliationDiscrepancy(type, depositId, sessionId, detail)
        );
    }

    private String serialize(DepositCompletedEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    "Failed to serialize deposit completed event", exception
            );
        }
    }
}
