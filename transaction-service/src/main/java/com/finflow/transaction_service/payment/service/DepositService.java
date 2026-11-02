package com.finflow.transaction_service.payment.service;

import com.finflow.transaction_service.domain.account.Account;
import com.finflow.transaction_service.domain.account.AccountStatus;
import com.finflow.transaction_service.exception.IdempotencyKeyConflictException;
import com.finflow.transaction_service.payment.domain.Deposit;
import com.finflow.transaction_service.payment.domain.DepositRepository;
import com.finflow.transaction_service.payment.stripe.CheckoutSession;
import com.finflow.transaction_service.payment.stripe.CreateCheckoutSession;
import com.finflow.transaction_service.payment.stripe.StripeCheckoutGateway;
import com.finflow.transaction_service.service.AccountService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

/**
 * Creates deposits backed by Stripe Checkout Sessions.
 *
 * <p>Golden rule: this service never credits a balance. The success redirect
 * is user experience only; the webhook handler is the single place where a
 * deposit is completed and the ledger is written.
 *
 * <p>Ordering: the Checkout Session is created at Stripe first, outside of
 * any database transaction, and the {@code PENDING} row is inserted only
 * once the session exists. A provider failure therefore leaves no row
 * behind (no "zombie" deposit), and no database connection is held while
 * waiting for the provider.
 *
 * <p>Idempotency: the same {@code Idempotency-Key} replays the same deposit
 * (same id, same checkout URL) for its owner and is a conflict (409) when
 * reused with different data. The deposit id and the key forwarded to
 * Stripe are derived deterministically from the owner and the client key,
 * so a retried request sends byte-identical parameters to Stripe and gets
 * the same session back instead of an idempotency error, and two owners
 * reusing the same client key cannot collide at Stripe.
 */
@Service
public class DepositService {

    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 100;
    private static final int MAX_SCALE = 2;

    private final DepositRepository depositRepository;
    private final AccountService accountService;
    private final StripeCheckoutGateway checkoutGateway;
    private final PaymentsProperties paymentsProperties;
    private final TransactionTemplate transactionTemplate;

    public DepositService(
            DepositRepository depositRepository,
            AccountService accountService,
            StripeCheckoutGateway checkoutGateway,
            PaymentsProperties paymentsProperties,
            PlatformTransactionManager transactionManager
    ) {
        this.depositRepository = depositRepository;
        this.accountService = accountService;
        this.checkoutGateway = checkoutGateway;
        this.paymentsProperties = paymentsProperties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public Deposit createDeposit(CreateDepositCommand command, UUID requestingOwnerId) {
        if (requestingOwnerId == null) {
            throw new IllegalArgumentException("Authenticated owner ID is required");
        }

        validate(command);

        Deposit existing = depositRepository
                .findByIdempotencyKey(command.idempotencyKey())
                .orElse(null);

        if (existing != null) {
            return replay(existing, command, requestingOwnerId);
        }

        Account account = accountService.getOwnedAccount(
                command.accountId(),
                requestingOwnerId
        );

        assertDepositable(account, command);

        UUID depositId = depositId(requestingOwnerId, command.idempotencyKey());
        String currency = command.currency().toUpperCase(Locale.ROOT);

        CheckoutSession session = checkoutGateway.createCheckoutSession(
                new CreateCheckoutSession(
                        depositId,
                        MinorUnits.toMinorUnits(command.amount()),
                        currency,
                        providerIdempotencyKey(requestingOwnerId, command.idempotencyKey())
                )
        );

        Deposit deposit = new Deposit(
                depositId,
                account.getId(),
                requestingOwnerId,
                command.amount(),
                currency,
                command.idempotencyKey(),
                session.sessionId(),
                session.url(),
                session.paymentIntentId()
        );

        try {
            return transactionTemplate.execute(status ->
                    depositRepository.saveAndFlush(deposit)
            );
        } catch (DataIntegrityViolationException exception) {
            return recoverFromIdempotencyRace(command, requestingOwnerId, exception);
        }
    }

    /**
     * Owner-scoped read. A deposit of another owner yields the same 404 as a
     * nonexistent one.
     */
    public Deposit getOwnedDeposit(UUID depositId, UUID requestingOwnerId) {
        if (requestingOwnerId == null) {
            throw new IllegalArgumentException("Authenticated owner ID is required");
        }

        return depositRepository.findByIdAndOwnerId(depositId, requestingOwnerId)
                .orElseThrow(() -> new DepositNotFoundException(depositId));
    }

    /** Deterministic: the same owner and key always name the same deposit. */
    static UUID depositId(UUID ownerId, String idempotencyKey) {
        return UUID.nameUUIDFromBytes(
                ("deposit:" + ownerId + ":" + idempotencyKey)
                        .getBytes(StandardCharsets.UTF_8)
        );
    }

    /** Namespaced per owner so two owners reusing a key cannot collide. */
    static String providerIdempotencyKey(UUID ownerId, String idempotencyKey) {
        return "deposit:" + ownerId + ":" + idempotencyKey;
    }

    private Deposit replay(
            Deposit existing,
            CreateDepositCommand command,
            UUID requestingOwnerId
    ) {
        // Ownership first, so a foreign key gets 403 and the payload
        // comparison cannot be used to probe someone else's deposit.
        if (!requestingOwnerId.equals(existing.getOwnerId())) {
            throw new AccessDeniedException(
                    "You are not authorized to access this deposit"
            );
        }

        boolean sameAccount = existing.getAccountId().equals(command.accountId());
        boolean sameAmount = existing.getAmount().compareTo(command.amount()) == 0;
        boolean sameCurrency = existing.getCurrency()
                .equalsIgnoreCase(command.currency());

        if (!sameAccount || !sameAmount || !sameCurrency) {
            throw new IdempotencyKeyConflictException(
                    "Idempotency-Key was already used with different deposit data"
            );
        }

        return existing;
    }

    private Deposit recoverFromIdempotencyRace(
            CreateDepositCommand command,
            UUID requestingOwnerId,
            DataIntegrityViolationException cause
    ) {
        Deposit winner = depositRepository
                .findByIdempotencyKey(command.idempotencyKey())
                .orElseThrow(() -> cause);

        return replay(winner, command, requestingOwnerId);
    }

    private void validate(CreateDepositCommand command) {
        if (command == null) {
            throw new InvalidDepositException("Deposit request cannot be null");
        }

        String key = command.idempotencyKey();

        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header is required");
        }

        if (key.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "Idempotency-Key must not exceed "
                            + MAX_IDEMPOTENCY_KEY_LENGTH + " characters"
            );
        }

        if (command.accountId() == null) {
            throw new InvalidDepositException("Account ID cannot be null");
        }

        if (command.amount() == null || command.amount().signum() <= 0) {
            throw new InvalidDepositException(
                    "Deposit amount must be greater than zero"
            );
        }

        if (command.amount().stripTrailingZeros().scale() > MAX_SCALE) {
            throw new InvalidDepositException(
                    "Deposit amount must have at most 2 decimal places"
            );
        }

        if (command.amount().compareTo(paymentsProperties.minimumAmount()) < 0) {
            throw new InvalidDepositException(
                    "Deposit amount must be at least "
                            + paymentsProperties.minimumAmount().toPlainString()
            );
        }

        if (!paymentsProperties.supports(command.currency())) {
            throw new InvalidDepositException(
                    "Currency " + command.currency()
                            + " is not supported for deposits"
            );
        }
    }

    private void assertDepositable(Account account, CreateDepositCommand command) {
        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new InvalidDepositException(
                    "Deposits are only accepted on active accounts"
            );
        }

        if (!account.getCurrency().equalsIgnoreCase(command.currency())) {
            throw new InvalidDepositException(
                    "Deposit currency must match the account currency ("
                            + account.getCurrency() + ")"
            );
        }
    }
}
