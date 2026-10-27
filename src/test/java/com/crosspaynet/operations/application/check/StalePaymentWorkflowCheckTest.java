package com.crosspaynet.operations.application.check;

import com.crosspaynet.banking.contracts.AccountId;
import com.crosspaynet.banking.contracts.ReservationToken;
import com.crosspaynet.common.money.Currency;
import com.crosspaynet.common.money.Money;
import com.crosspaynet.messaging.contracts.MessageId;
import com.crosspaynet.messaging.contracts.PaymentMessageQueryPort;
import com.crosspaynet.messaging.contracts.PaymentMessageStatus;
import com.crosspaynet.messaging.contracts.PaymentMessageType;
import com.crosspaynet.messaging.contracts.PaymentMessageView;
import com.crosspaynet.operations.domain.DiscrepancyType;
import com.crosspaynet.operations.domain.ReconciliationCheckStatus;
import com.crosspaynet.operations.domain.ReconciliationRunId;
import com.crosspaynet.operations.infrastructure.configuration.OperationsReconciliationProperties;
import com.crosspaynet.payment.contracts.BicCode;
import com.crosspaynet.payment.contracts.PaymentId;
import com.crosspaynet.payment.contracts.PaymentReconciliationCursor;
import com.crosspaynet.payment.contracts.PaymentReconciliationPage;
import com.crosspaynet.payment.contracts.PaymentReconciliationPort;
import com.crosspaynet.payment.contracts.PaymentReconciliationRecord;
import com.crosspaynet.payment.contracts.PaymentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StalePaymentWorkflowCheckTest {

    @Mock
    private PaymentReconciliationPort paymentReconciliationPort;

    @Mock
    private PaymentMessageQueryPort paymentMessageQueryPort;

    @Mock
    private DiscrepancyRecorder discrepancyRecorder;

    private OperationsReconciliationProperties properties;
    private StalePaymentWorkflowCheck check;

    @BeforeEach
    void setUp() {
        properties = new OperationsReconciliationProperties();
        properties.setStaleWorkflowThreshold(Duration.ofMinutes(30));
        properties.setGraceWindow(Duration.ofMinutes(5));
        properties.setPageSize(50);

        check = new StalePaymentWorkflowCheck(
                paymentReconciliationPort,
                paymentMessageQueryPort,
                discrepancyRecorder,
                properties
        );
    }

    @Test
    void shouldDetectStalePendingExternalPaymentWithTransmissionTimeout() {
        ReconciliationRunId runId = ReconciliationRunId.generate();
        Instant now = Instant.now();
        PaymentId paymentId = PaymentId.generate();
        Instant updatedAt = now.minus(45, ChronoUnit.MINUTES);

        PaymentReconciliationRecord record = new PaymentReconciliationRecord(
                paymentId,
                PaymentStatus.PENDING_EXTERNAL,
                AccountId.generate(),
                new Money(new BigDecimal("250.00"), new Currency("USD")),
                Optional.of(ReservationToken.generate()),
                Optional.of("ROUTE-US-1"),
                Optional.of("EXT-DISPATCH-999"),
                Optional.empty(),
                Optional.empty(),
                updatedAt.minus(5, ChronoUnit.MINUTES),
                updatedAt
        );

        when(paymentReconciliationPort.findPaymentsForReconciliation(any(), any(), any(), eq(50)))
                .thenReturn(new PaymentReconciliationPage(List.of(record), false, Optional.empty()));

        PaymentMessageView timeoutMessage = new PaymentMessageView(
                MessageId.generate(),
                paymentId,
                1,
                "IDEM-KEY-1",
                PaymentMessageType.PACS_008,
                BicCode.of("TESTUS33XXX"),
                BicCode.of("RECVUS33XXX"),
                "ROUTE-US-1",
                new Money(new BigDecimal("250.00"), new Currency("USD")),
                "Beneficiary",
                "ACC123",
                "<pacs008/>",
                "digest",
                PaymentMessageStatus.TIMEOUT,
                null,
                null,
                null,
                "Peer response timed out after 30s",
                updatedAt.minus(1, ChronoUnit.MINUTES),
                updatedAt
        );
        when(paymentMessageQueryPort.findMessagesForPayment(paymentId)).thenReturn(List.of(timeoutMessage));

        CheckExecutionOutcome outcome = check.execute(runId, now);

        assertThat(outcome.status()).isEqualTo(ReconciliationCheckStatus.COMPLETED);
        assertThat(outcome.itemsScanned()).isEqualTo(1);
        assertThat(outcome.discrepanciesFound()).isEqualTo(1);

        ArgumentCaptor<String> summaryCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> detailsCaptor = ArgumentCaptor.forClass(String.class);

        verify(discrepancyRecorder).recordDiscrepancy(
                eq(runId),
                eq(DiscrepancyType.STALE_IN_FLIGHT_WORKFLOW),
                eq(paymentId.getValue().toString()),
                summaryCaptor.capture(),
                detailsCaptor.capture(),
                eq(now)
        );

        assertThat(summaryCaptor.getValue()).contains("transmission timeout");
        assertThat(detailsCaptor.getValue()).contains("TIMEOUT");
        assertThat(detailsCaptor.getValue()).doesNotContain("Beneficiary"); // zero PII
    }

    @Test
    void shouldDetectStaleComplianceHoldPayment() {
        ReconciliationRunId runId = ReconciliationRunId.generate();
        Instant now = Instant.now();
        PaymentId paymentId = PaymentId.generate();
        Instant updatedAt = now.minus(50, ChronoUnit.MINUTES);

        PaymentReconciliationRecord record = new PaymentReconciliationRecord(
                paymentId,
                PaymentStatus.COMPLIANCE_HOLD,
                AccountId.generate(),
                new Money(new BigDecimal("1000.00"), new Currency("EUR")),
                Optional.of(ReservationToken.generate()),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                updatedAt,
                updatedAt
        );

        when(paymentReconciliationPort.findPaymentsForReconciliation(any(), any(), any(), eq(50)))
                .thenReturn(new PaymentReconciliationPage(List.of(record), false, Optional.empty()));

        CheckExecutionOutcome outcome = check.execute(runId, now);

        assertThat(outcome.status()).isEqualTo(ReconciliationCheckStatus.COMPLETED);
        assertThat(outcome.itemsScanned()).isEqualTo(1);
        assertThat(outcome.discrepanciesFound()).isEqualTo(1);

        verify(discrepancyRecorder).recordDiscrepancy(
                eq(runId),
                eq(DiscrepancyType.STALE_IN_FLIGHT_WORKFLOW),
                eq(paymentId.getValue().toString()),
                any(),
                any(),
                eq(now)
        );
    }

    @Test
    void shouldTraverseMultiplePagesWithKeysetCursor() {
        ReconciliationRunId runId = ReconciliationRunId.generate();
        Instant now = Instant.now();

        PaymentId p1 = PaymentId.generate();
        PaymentId p2 = PaymentId.generate();
        Instant t1 = now.minus(40, ChronoUnit.MINUTES);
        Instant t2 = now.minus(38, ChronoUnit.MINUTES);

        PaymentReconciliationRecord r1 = new PaymentReconciliationRecord(
                p1, PaymentStatus.COMPLIANCE_HOLD, AccountId.generate(),
                new Money(BigDecimal.TEN, new Currency("USD")), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), t1, t1
        );
        PaymentReconciliationRecord r2 = new PaymentReconciliationRecord(
                p2, PaymentStatus.COMPLIANCE_HOLD, AccountId.generate(),
                new Money(BigDecimal.TEN, new Currency("USD")), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), t2, t2
        );

        PaymentReconciliationCursor cursor1 = new PaymentReconciliationCursor(t1, p1);

        when(paymentReconciliationPort.findPaymentsForReconciliation(any(), any(), eq(null), eq(50)))
                .thenReturn(new PaymentReconciliationPage(List.of(r1), true, Optional.of(cursor1)));

        when(paymentReconciliationPort.findPaymentsForReconciliation(any(), any(), eq(cursor1), eq(50)))
                .thenReturn(new PaymentReconciliationPage(List.of(r2), false, Optional.empty()));

        CheckExecutionOutcome outcome = check.execute(runId, now);

        assertThat(outcome.status()).isEqualTo(ReconciliationCheckStatus.COMPLETED);
        assertThat(outcome.itemsScanned()).isEqualTo(2);
        assertThat(outcome.discrepanciesFound()).isEqualTo(2);
    }

    @Test
    void shouldHandleExceptionAndReturnFailureOutcome() {
        ReconciliationRunId runId = ReconciliationRunId.generate();
        Instant now = Instant.now();

        when(paymentReconciliationPort.findPaymentsForReconciliation(any(), any(), any(), anyInt()))
                .thenThrow(new RuntimeException("Database connectivity failure during scan"));

        CheckExecutionOutcome outcome = check.execute(runId, now);

        assertThat(outcome.status()).isEqualTo(ReconciliationCheckStatus.FAILED);
        assertThat(outcome.itemsScanned()).isZero();
        assertThat(outcome.discrepanciesFound()).isZero();
        assertThat(outcome.errorSummary()).contains("Database connectivity failure");
    }
}
