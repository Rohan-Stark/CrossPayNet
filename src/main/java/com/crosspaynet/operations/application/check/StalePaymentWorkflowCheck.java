package com.crosspaynet.operations.application.check;

import com.crosspaynet.messaging.contracts.PaymentMessageQueryPort;
import com.crosspaynet.messaging.contracts.PaymentMessageStatus;
import com.crosspaynet.messaging.contracts.PaymentMessageView;
import com.crosspaynet.operations.domain.DiscrepancyType;
import com.crosspaynet.operations.domain.ReconciliationRunId;
import com.crosspaynet.operations.infrastructure.configuration.OperationsReconciliationProperties;
import com.crosspaynet.payment.contracts.PaymentId;
import com.crosspaynet.payment.contracts.PaymentReconciliationCursor;
import com.crosspaynet.payment.contracts.PaymentReconciliationPage;
import com.crosspaynet.payment.contracts.PaymentReconciliationPort;
import com.crosspaynet.payment.contracts.PaymentReconciliationRecord;
import com.crosspaynet.payment.contracts.PaymentStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Check A: Identifies stale in-flight payment workflows (PENDING_EXTERNAL or COMPLIANCE_HOLD)
 * older than the configured workflow age threshold and grace window.
 */
@Component
public class StalePaymentWorkflowCheck implements ReconciliationCheckExecutor {

    public static final String CHECK_NAME = "STALE_PAYMENT_WORKFLOW_CHECK";
    private static final Set<PaymentStatus> STALE_STATUSES = Set.of(
            PaymentStatus.PENDING_EXTERNAL,
            PaymentStatus.COMPLIANCE_HOLD
    );

    private final PaymentReconciliationPort paymentReconciliationPort;
    private final PaymentMessageQueryPort paymentMessageQueryPort;
    private final DiscrepancyRecorder discrepancyRecorder;
    private final OperationsReconciliationProperties properties;

    public StalePaymentWorkflowCheck(
            PaymentReconciliationPort paymentReconciliationPort,
            PaymentMessageQueryPort paymentMessageQueryPort,
            DiscrepancyRecorder discrepancyRecorder,
            OperationsReconciliationProperties properties
    ) {
        this.paymentReconciliationPort = Objects.requireNonNull(paymentReconciliationPort, "paymentReconciliationPort cannot be null.");
        this.paymentMessageQueryPort = Objects.requireNonNull(paymentMessageQueryPort, "paymentMessageQueryPort cannot be null.");
        this.discrepancyRecorder = Objects.requireNonNull(discrepancyRecorder, "discrepancyRecorder cannot be null.");
        this.properties = Objects.requireNonNull(properties, "properties cannot be null.");
    }

    @Override
    public String getCheckName() {
        return CHECK_NAME;
    }

    @Override
    public CheckExecutionOutcome execute(ReconciliationRunId runId, Instant runStartedAt) {
        Instant cutoff = runStartedAt.minus(properties.getStaleWorkflowThreshold()).minus(properties.getGraceWindow());
        int itemsScanned = 0;
        int discrepanciesFound = 0;
        PaymentReconciliationCursor cursor = null;
        boolean hasMore = true;

        try {
            while (hasMore) {
                PaymentReconciliationPage page = paymentReconciliationPort.findPaymentsForReconciliation(
                        STALE_STATUSES,
                        cutoff,
                        cursor,
                        properties.getPageSize()
                );

                for (PaymentReconciliationRecord payment : page.items()) {
                    itemsScanned++;
                    discrepanciesFound++;
                    recordStalePayment(runId, payment, runStartedAt);
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

    private void recordStalePayment(ReconciliationRunId runId, PaymentReconciliationRecord payment, Instant observedAt) {
        String entityId = payment.paymentId().getValue().toString();
        String summary;
        StringBuilder details = new StringBuilder("{");
        details.append("\"paymentId\":\"").append(entityId).append("\",");
        details.append("\"status\":\"").append(payment.status().name()).append("\",");
        details.append("\"amount\":").append(payment.amount().getAmount()).append(",");
        details.append("\"currency\":\"").append(payment.amount().getCurrency().getCode()).append("\",");
        details.append("\"updatedAt\":\"").append(payment.updatedAt()).append("\"");

        if (payment.status() == PaymentStatus.PENDING_EXTERNAL) {
            List<PaymentMessageView> messages = paymentMessageQueryPort.findMessagesForPayment(payment.paymentId());
            boolean hasTimeout = false;
            PaymentMessageView latestMessage = messages.stream()
                    .max(Comparator.comparingInt(PaymentMessageView::attemptNumber))
                    .orElse(null);

            if (latestMessage != null) {
                details.append(",\"messageAttempts\":").append(messages.size());
                details.append(",\"latestMessageStatus\":\"").append(latestMessage.status().name()).append("\"");
                if (latestMessage.status() == PaymentMessageStatus.TIMEOUT) {
                    hasTimeout = true;
                    if (latestMessage.timeoutReason() != null) {
                        details.append(",\"latestMessageTimeoutReason\":\"")
                                .append(escapeJson(latestMessage.timeoutReason()))
                                .append("\"");
                    }
                }
            }

            if (hasTimeout) {
                summary = "Payment in PENDING_EXTERNAL with transmission timeout since " + payment.updatedAt();
            } else {
                summary = "Payment in PENDING_EXTERNAL awaiting network response since " + payment.updatedAt();
            }
        } else {
            summary = "Payment in COMPLIANCE_HOLD exceeding workflow threshold since " + payment.updatedAt();
        }

        details.append("}");

        discrepancyRecorder.recordDiscrepancy(
                runId,
                DiscrepancyType.STALE_IN_FLIGHT_WORKFLOW,
                entityId,
                summary,
                details.toString(),
                observedAt
        );
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\"", "\\\"").replace("\n", " ").replace("\r", "");
    }

    private static String sanitizeException(Exception ex) {
        String msg = ex.getMessage();
        if (msg == null || msg.isBlank()) {
            msg = ex.getClass().getSimpleName();
        }
        return msg.length() > 500 ? msg.substring(0, 500) : msg;
    }
}
