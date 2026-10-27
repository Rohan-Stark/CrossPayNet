package com.crosspaynet.operations.application.check;

import com.crosspaynet.ledger.LedgerPostingKey;
import com.crosspaynet.ledger.LedgerService;
import com.crosspaynet.ledger.LedgerTransactionView;
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
import java.util.UUID;

/**
 * Check C: Validates that settled payments have a corresponding, consistent ledger entry
 * matching the canonical settlement posting key and exact amount/currency expectations.
 */
@Component
public class SettlementLedgerInconsistencyCheck implements ReconciliationCheckExecutor {

    public static final String CHECK_NAME = "SETTLEMENT_LEDGER_INCONSISTENCY_CHECK";
    public static final String SETTLEMENT_POSTING_KEY_PREFIX = "PAYMENT_SETTLEMENT:";
    private static final Set<PaymentStatus> SETTLED_STATUS = Set.of(PaymentStatus.SETTLED);

    private final PaymentReconciliationPort paymentReconciliationPort;
    private final LedgerService ledgerService;
    private final DiscrepancyRecorder discrepancyRecorder;
    private final OperationsReconciliationProperties properties;

    public SettlementLedgerInconsistencyCheck(
            PaymentReconciliationPort paymentReconciliationPort,
            LedgerService ledgerService,
            DiscrepancyRecorder discrepancyRecorder,
            OperationsReconciliationProperties properties
    ) {
        this.paymentReconciliationPort = Objects.requireNonNull(paymentReconciliationPort, "paymentReconciliationPort cannot be null.");
        this.ledgerService = Objects.requireNonNull(ledgerService, "ledgerService cannot be null.");
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
                        SETTLED_STATUS,
                        cutoff,
                        cursor,
                        properties.getPageSize()
                );

                for (PaymentReconciliationRecord payment : page.items()) {
                    itemsScanned++;
                    boolean mismatch = inspectSettlementLedger(runId, payment, runStartedAt);
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

    private boolean inspectSettlementLedger(ReconciliationRunId runId, PaymentReconciliationRecord payment, Instant observedAt) {
        String entityId = payment.paymentId().getValue().toString();
        LedgerPostingKey postingKey = LedgerPostingKey.of(SETTLEMENT_POSTING_KEY_PREFIX + entityId);
        Optional<LedgerTransactionView> transactionOpt = ledgerService.findByPostingKey(postingKey);

        if (transactionOpt.isEmpty()) {
            String summary = "Settled payment has no matching ledger transaction with posting key " + postingKey.getValue();
            String details = String.format(
                    "{\"paymentId\":\"%s\",\"expectedPostingKey\":\"%s\",\"issue\":\"POSTING_KEY_NOT_FOUND\"}",
                    entityId, postingKey.getValue()
            );
            discrepancyRecorder.recordDiscrepancy(
                    runId, DiscrepancyType.PAYMENT_LEDGER_BREAK, entityId, summary, details, observedAt
            );
            return true;
        }

        LedgerTransactionView transaction = transactionOpt.get();

        if (!transaction.totalAmount().equals(payment.amount())) {
            String summary = String.format(
                    "Settled payment amount/currency mismatch with ledger transaction: expected %s, found %s",
                    payment.amount(), transaction.totalAmount()
            );
            String details = String.format(
                    "{\"paymentId\":\"%s\",\"expectedPostingKey\":\"%s\",\"expectedAmount\":%s,\"expectedCurrency\":\"%s\",\"actualAmount\":%s,\"actualCurrency\":\"%s\",\"issue\":\"AMOUNT_CURRENCY_MISMATCH\"}",
                    entityId, postingKey.getValue(),
                    payment.amount().getAmount(), payment.amount().getCurrency().getCode(),
                    transaction.totalAmount().getAmount(), transaction.totalAmount().getCurrency().getCode()
            );
            discrepancyRecorder.recordDiscrepancy(
                    runId, DiscrepancyType.PAYMENT_LEDGER_BREAK, entityId, summary, details, observedAt
            );
            return true;
        }

        Optional<UUID> paymentTxIdOpt = payment.ledgerTransactionId();
        if (paymentTxIdOpt.isPresent() && !paymentTxIdOpt.get().equals(transaction.transactionId().getValue())) {
            String summary = String.format(
                    "Settled payment ledgerTransactionId mismatch: payment has %s, ledger transaction has %s",
                    paymentTxIdOpt.get(), transaction.transactionId().getValue()
            );
            String details = String.format(
                    "{\"paymentId\":\"%s\",\"expectedPostingKey\":\"%s\",\"recordedTransactionId\":\"%s\",\"actualTransactionId\":\"%s\",\"issue\":\"TRANSACTION_ID_MISMATCH\"}",
                    entityId, postingKey.getValue(), paymentTxIdOpt.get(), transaction.transactionId().getValue()
            );
            discrepancyRecorder.recordDiscrepancy(
                    runId, DiscrepancyType.PAYMENT_LEDGER_BREAK, entityId, summary, details, observedAt
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
