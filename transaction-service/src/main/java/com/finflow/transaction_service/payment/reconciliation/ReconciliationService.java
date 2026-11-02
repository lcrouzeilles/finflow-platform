package com.finflow.transaction_service.payment.reconciliation;

import com.finflow.transaction_service.payment.domain.Deposit;
import com.finflow.transaction_service.payment.domain.DepositRepository;
import com.finflow.transaction_service.payment.domain.DepositStatus;
import com.finflow.transaction_service.payment.service.MinorUnits;
import com.finflow.transaction_service.payment.stripe.ProviderCheckoutSession;
import com.finflow.transaction_service.payment.stripe.StripeCheckoutGateway;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Compares what Stripe says with what the ledger says for a window of time
 * and records every difference as a {@link ReconciliationDiscrepancy}.
 *
 * <p><strong>Report only, never auto-correct.</strong> A paid session with
 * no completed deposit usually means a lost webhook, but it could also be
 * a session this service never created, a mismatch in amount, or a deposit
 * that was deliberately left pending; crediting a balance automatically on
 * that evidence would turn an observable inconsistency into a silent one.
 * A human (or a later, deliberately designed repair tool) decides.
 *
 * <p>Discrepancy types:
 * <ul>
 *   <li>{@code PAID_WITHOUT_LOCAL_COMPLETION}: the provider reports the
 *       session paid, locally there is no deposit or it is not COMPLETED.</li>
 *   <li>{@code COMPLETED_WITHOUT_PROVIDER_PAYMENT}: a deposit is COMPLETED
 *       locally but the provider has no payment for its session. Must never
 *       happen: it means a balance was credited without money.</li>
 *   <li>{@code AMOUNT_MISMATCH}: paid and completed, but the amounts or
 *       currencies differ.</li>
 * </ul>
 * Each new discrepancy increments {@code finflow.reconciliation.discrepancies}
 * (tag {@code type}) and is logged at WARN with its references.
 */
@Service
public class ReconciliationService {

    private static final Logger log =
            LoggerFactory.getLogger(ReconciliationService.class);

    static final String METRIC_DISCREPANCIES = "finflow.reconciliation.discrepancies";

    private final StripeCheckoutGateway checkoutGateway;
    private final DepositRepository depositRepository;
    private final DiscrepancyRecorder recorder;
    private final MeterRegistry meterRegistry;

    public ReconciliationService(
            StripeCheckoutGateway checkoutGateway,
            DepositRepository depositRepository,
            DiscrepancyRecorder recorder,
            MeterRegistry meterRegistry
    ) {
        this.checkoutGateway = checkoutGateway;
        this.depositRepository = depositRepository;
        this.recorder = recorder;
        this.meterRegistry = meterRegistry;
    }

    public ReconciliationReport reconcile(ReconciliationWindow window) {
        List<ProviderCheckoutSession> providerSessions =
                checkoutGateway.listCheckoutSessions(window.from(), window.to());

        Map<String, ProviderCheckoutSession> providerBySession = providerSessions.stream()
                .collect(Collectors.toMap(
                        ProviderCheckoutSession::sessionId,
                        Function.identity(),
                        (first, second) -> first
                ));

        List<Deposit> localDeposits =
                depositRepository.findAllByCreatedAtBetween(window.from(), window.to());

        Map<String, Deposit> localBySession = localDeposits.stream()
                .filter(deposit -> deposit.getStripeSessionId() != null)
                .collect(Collectors.toMap(
                        Deposit::getStripeSessionId,
                        Function.identity(),
                        (first, second) -> first
                ));

        List<Finding> findings = new ArrayList<>();

        // Provider -> local: every paid session must be a COMPLETED deposit
        // with the same amount.
        for (ProviderCheckoutSession session : providerSessions) {
            if (!session.isPaid()) {
                continue;
            }

            Deposit deposit = localBySession.get(session.sessionId());

            if (deposit == null) {
                findings.add(new Finding(
                        DiscrepancyType.PAID_WITHOUT_LOCAL_COMPLETION, null, session.sessionId(),
                        "paid at the provider (amount_total=" + session.amountTotal()
                                + " " + session.currency() + ") but no local deposit exists"));
            } else if (deposit.getStatus() != DepositStatus.COMPLETED) {
                findings.add(new Finding(
                        DiscrepancyType.PAID_WITHOUT_LOCAL_COMPLETION, deposit.getId(), session.sessionId(),
                        "paid at the provider but local deposit is " + deposit.getStatus()
                                + " (webhook lost?)"));
            } else if (!amountMatches(deposit, session)) {
                findings.add(amountMismatch(deposit, session));
            }
        }

        // Local -> provider: every COMPLETED deposit must be paid at the
        // provider. A session missing from the listing is looked up before
        // being flagged (it may fall outside the provider's window).
        for (Deposit deposit : localDeposits) {
            if (deposit.getStatus() != DepositStatus.COMPLETED
                    || deposit.getStripeSessionId() == null) {
                continue;
            }

            ProviderCheckoutSession session = providerBySession.get(deposit.getStripeSessionId());
            boolean fetched = false;

            if (session == null) {
                session = checkoutGateway.findCheckoutSession(deposit.getStripeSessionId())
                        .orElse(null);
                fetched = true;
            }

            if (session == null) {
                findings.add(new Finding(
                        DiscrepancyType.COMPLETED_WITHOUT_PROVIDER_PAYMENT, deposit.getId(),
                        deposit.getStripeSessionId(),
                        "deposit is COMPLETED locally but the provider has no such session"));
            } else if (!session.isPaid()) {
                findings.add(new Finding(
                        DiscrepancyType.COMPLETED_WITHOUT_PROVIDER_PAYMENT, deposit.getId(),
                        deposit.getStripeSessionId(),
                        "deposit is COMPLETED locally but the provider reports payment_status="
                                + session.paymentStatus()));
            } else if (fetched && !amountMatches(deposit, session)) {
                findings.add(amountMismatch(deposit, session));
            }
        }

        return record(window, providerSessions.size(), localDeposits.size(), findings);
    }

    private ReconciliationReport record(
            ReconciliationWindow window,
            int providerSessions,
            int localDeposits,
            List<Finding> findings
    ) {
        Map<DiscrepancyType, Integer> found = new EnumMap<>(DiscrepancyType.class);
        Set<String> seen = new HashSet<>();
        int recorded = 0;

        for (Finding finding : findings) {
            if (!seen.add(finding.type() + ":" + finding.sessionId())) {
                continue;
            }

            found.merge(finding.type(), 1, Integer::sum);

            boolean isNew;
            try {
                isNew = recorder.record(
                        finding.type(), finding.depositId(), finding.sessionId(), finding.detail());
            } catch (DataIntegrityViolationException exception) {
                // Recorded concurrently (another run or the webhook handler).
                isNew = false;
            }

            if (isNew) {
                recorded++;
                meterRegistry.counter(METRIC_DISCREPANCIES, "type", finding.type().name())
                        .increment();
            }

            log.warn("reconciliation discrepancy type={} depositId={} sessionId={} new={} detail={}",
                    finding.type(), finding.depositId(), finding.sessionId(), isNew, finding.detail());
        }

        ReconciliationReport report = new ReconciliationReport(
                window, providerSessions, localDeposits, found, recorded);

        log.info("reconciliation run from={} to={} providerSessions={} localDeposits={} found={} recorded={}",
                window.from(), window.to(), providerSessions, localDeposits, report.totalFound(), recorded);

        return report;
    }

    private static boolean amountMatches(Deposit deposit, ProviderCheckoutSession session) {
        if (session.amountTotal() == null || session.currency() == null) {
            return false;
        }

        BigDecimal paid = MinorUnits.fromMinorUnits(session.amountTotal());

        return paid.compareTo(deposit.getAmount()) == 0
                && deposit.getCurrency().equalsIgnoreCase(session.currency());
    }

    private static Finding amountMismatch(Deposit deposit, ProviderCheckoutSession session) {
        return new Finding(
                DiscrepancyType.AMOUNT_MISMATCH, deposit.getId(), session.sessionId(),
                "local " + deposit.getAmount().toPlainString() + " " + deposit.getCurrency()
                        + " vs provider amount_total=" + session.amountTotal()
                        + " " + session.currency());
    }

    private record Finding(DiscrepancyType type, UUID depositId, String sessionId, String detail) {
    }
}
