package com.crosspaynet.operations.application.check;

import com.crosspaynet.banking.contracts.BankingReservationQueryPort;
import com.crosspaynet.banking.contracts.ReservationStatus;
import com.crosspaynet.banking.contracts.ReservationToken;
import com.crosspaynet.banking.contracts.ReservationView;
import com.crosspaynet.operations.domain.DiscrepancyType;
import com.crosspaynet.operations.domain.ReconciliationRunId;
import com.crosspaynet.operations.infrastructure.configuration.OperationsReconciliationProperties;
import com.crosspaynet.payment.contracts.PaymentReconciliationCursor;
import com.crosspaynet.payment.contracts.PaymentReconciliationPage;
import com.crosspaynet.payment.contracts.PaymentReconciliationPort;
import com.crosspaynet.payment.contracts.PaymentReconciliationRecord;
import com.crosspaynet.payment.contracts.PaymentStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Check B: Reconciles terminal payment states against their associated Banking reservations.
 *
 * <p>Expected mappings:
 * <ul>
 *   <li>{@code SETTLED} -> reservation must be {@code CONSUMED}</li>
 *   <li>{@code FAILED}, {@code REJECTED}, {@code CANCELLED} -> reservation must be {@code RELEASED}</li>
 * </ul>
 */
@Component
public class PaymentReservationMismatchCheck implements ReconciliationCheckExecutor {

    public static final String CHECK_NAME = "PAYMENT_RESERVATION_MISMATCH_CHECK";
    private static final Set<PaymentStatus> TERMINAL_STATUSES = Set.of(
            PaymentStatus.SETTLED,
            PaymentStatus.FAILED,
            PaymentStatus.REJECTED,
            PaymentStatus.CANCELLED
    );

    private final PaymentReconciliationPort paymentReconciliationPort;
    private final BankingReservationQueryPort bankingReservationQueryPort;
    private final DiscrepancyRecorder discrepancyRecorder;
    private final OperationsReconciliationProperties properties;

    public PaymentReservationMismatchCheck(
            PaymentReconciliationPort paymentReconciliationPort,
            BankingReservationQueryPort bankingReservationQueryPort,
            DiscrepancyRecorder discrepancyRecorder,
            OperationsReconciliationProperties properties
    ) {
        this.paymentReconciliationPort = Objects.requireNonNull(paymentReconciliationPort, "paymentReconciliationPort cannot be null.");
        this.bankingReservationQueryPort = Objects.requireNonNull(bankingReservationQueryPort, "bankingReservationQueryPort cannot be null.");
        this.discrepancyRecorder = Objects.requireNonNull(discrepancyRecorder, "discrepancyRecorder cannot be null.");
        this.properties = Objects.requireNonNull(properties, "properties cannot be null.");
    }

    @Override
    public String getCheckName() {
        return CHECK_NAME;
    }

    @Override
    public CheckExecutionOutcome execute(ReconciliationRunId runId, Instant runStartedAt) {
        Instant cutoff = runStartedAt.minus(properties.getGraceWindow());
        int itemsScanned = 0;
        int discrepanciesFound = 0;
        PaymentReconciliationCursor cursor = null;
        boolean hasMore = true;

        try {
            while (hasMore) {
                PaymentReconciliationPage page = paymentReconciliationPort.findPaymentsForReconciliation(
                        TERMINAL_STATUSES,
                        cutoff,
                        cursor,
                        properties.getPageSize()
                );

                for (PaymentReconciliationRecord payment : page.items()) {
                    itemsScanned++;
                    boolean mismatch = inspectReservation(runId, payment, runStartedAt);
                    if (mismatch) {
                        discrepanciesFound++;
                    }
                }

                hasMore = page.hasMore() && page.nextCursor().isPresent();
                cursor = page.nextCursor().orElse(null);
            }

            return CheckExecutionOutcome.success(CHECK_NAME, itemsScanned, discrepanciesFound);
        } catch (Exception ex) {
            String errorSummary = sanitizeException(ex);
            return CheckExecutionOutcome.failure(CHECK_NAME, itemsScanned, discrepanciesFound, errorSummary);
        }
    }

    private boolean inspectReservation(ReconciliationRunId runId, PaymentReconciliationRecord payment, Instant observedAt) {
        String entityId = payment.paymentId().getValue().toString();
        Optional<ReservationToken> tokenOpt = payment.reservationToken();

        if (tokenOpt.isEmpty()) {
            String summary = "Terminal payment " + payment.status() + " is missing associated reservation token";
            String details = String.format(
                    "{\"paymentId\":\"%s\",\"paymentStatus\":\"%s\",\"issue\":\"MISSING_RESERVATION_TOKEN\"}",
                    entityId, payment.status().name()
            );
            discrepancyRecorder.recordDiscrepancy(
                    runId, DiscrepancyType.RESERVATION_PAYMENT_MISMATCH, entityId, summary, details, observedAt
            );
            return true;
        }

        ReservationToken token = tokenOpt.get();
        Optional<ReservationView> reservationOpt = bankingReservationQueryPort.findReservation(token);

        if (reservationOpt.isEmpty()) {
            String summary = "Terminal payment " + payment.status() + " references non-existent reservation " + token.getValue();
            String details = String.format(
                    "{\"paymentId\":\"%s\",\"paymentStatus\":\"%s\",\"reservationToken\":\"%s\",\"issue\":\"RESERVATION_NOT_FOUND\"}",
                    entityId, payment.status().name(), token.getValue()
            );
            discrepancyRecorder.recordDiscrepancy(
                    runId, DiscrepancyType.RESERVATION_PAYMENT_MISMATCH, entityId, summary, details, observedAt
            );
            return true;
        }

        ReservationView reservation = reservationOpt.get();
        ReservationStatus expectedStatus = payment.status() == PaymentStatus.SETTLED
                ? ReservationStatus.CONSUMED
                : ReservationStatus.RELEASED;

        if (reservation.status() != expectedStatus) {
            String summary = String.format(
                    "Terminal payment %s references reservation with unexpected status: expected %s, found %s",
                    payment.status().name(), expectedStatus.name(), reservation.status().name()
            );
            String details = String.format(
                    "{\"paymentId\":\"%s\",\"paymentStatus\":\"%s\",\"reservationToken\":\"%s\",\"expectedStatus\":\"%s\",\"actualStatus\":\"%s\"}",
                    entityId, payment.status().name(), token.getValue(), expectedStatus.name(), reservation.status().name()
            );
            discrepancyRecorder.recordDiscrepancy(
                    runId, DiscrepancyType.RESERVATION_PAYMENT_MISMATCH, entityId, summary, details, observedAt
            );
            return true;
        }

        return false;
    }

    private static String sanitizeException(Exception ex) {
        String msg = ex.getMessage();
        if (msg == null || msg.isBlank()) {
            msg = ex.getClass().getSimpleName();
        }
        return msg.length() > 500 ? msg.substring(0, 500) : msg;
    }
}
