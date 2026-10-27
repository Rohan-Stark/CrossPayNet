package com.crosspaynet.operations.application.check;

import com.crosspaynet.banking.BankingService;
import com.crosspaynet.banking.contracts.AccountId;
import com.crosspaynet.banking.contracts.PaymentReference;
import com.crosspaynet.banking.contracts.ReservationStatus;
import com.crosspaynet.banking.contracts.ReservationToken;
import com.crosspaynet.banking.contracts.ReservationView;
import com.crosspaynet.common.money.Currency;
import com.crosspaynet.common.money.Money;
import com.crosspaynet.operations.domain.DiscrepancyType;
import com.crosspaynet.operations.domain.ReconciliationCheckStatus;
import com.crosspaynet.operations.domain.ReconciliationRunId;
import com.crosspaynet.operations.infrastructure.configuration.OperationsReconciliationProperties;
import com.crosspaynet.payment.contracts.PaymentId;
import com.crosspaynet.payment.contracts.PaymentReconciliationPage;
import com.crosspaynet.payment.contracts.PaymentReconciliationPort;
import com.crosspaynet.payment.contracts.PaymentReconciliationRecord;
import com.crosspaynet.payment.contracts.PaymentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentReservationMismatchCheckTest {

    @Mock
    private PaymentReconciliationPort paymentReconciliationPort;

    @Mock
    private BankingService bankingService;

    @Mock
    private DiscrepancyRecorder discrepancyRecorder;

    private OperationsReconciliationProperties properties;
    private PaymentReservationMismatchCheck check;

    @BeforeEach
    void setUp() {
        properties = new OperationsReconciliationProperties();
        properties.setGraceWindow(Duration.ofMinutes(5));
        properties.setPageSize(50);

        check = new PaymentReservationMismatchCheck(
                paymentReconciliationPort,
                bankingService,
                discrepancyRecorder,
                properties
        );
    }

    @Test
    void shouldDetectSettledPaymentWithActiveReservation() {
        ReconciliationRunId runId = ReconciliationRunId.generate();
        Instant now = Instant.now();
        PaymentId paymentId = PaymentId.generate();
        ReservationToken token = ReservationToken.generate();
        AccountId accountId = AccountId.generate();
        Money amount = new Money(new BigDecimal("100.00"), new Currency("USD"));

        PaymentReconciliationRecord payment = new PaymentReconciliationRecord(
                paymentId,
                PaymentStatus.SETTLED,
                accountId,
                amount,
                Optional.of(token),
                Optional.of("ROUTE-1"),
                Optional.of("DISP-1"),
                Optional.of(java.util.UUID.randomUUID()),
                Optional.empty(),
                now.minus(20, ChronoUnit.MINUTES),
                now.minus(10, ChronoUnit.MINUTES)
        );

        when(paymentReconciliationPort.findPaymentsForReconciliation(any(), any(), any(), eq(50)))
                .thenReturn(new PaymentReconciliationPage(List.of(payment), false, Optional.empty()));

        // Banking reports reservation is still ACTIVE instead of CONSUMED
        ReservationView reservation = new ReservationView(
                token,
                accountId,
                ReservationStatus.ACTIVE,
                amount,
                PaymentReference.of("REF-1")
        );
        when(bankingService.findReservation(token)).thenReturn(Optional.of(reservation));

        CheckExecutionOutcome outcome = check.execute(runId, now);

        assertThat(outcome.status()).isEqualTo(ReconciliationCheckStatus.COMPLETED);
        assertThat(outcome.itemsScanned()).isEqualTo(1);
        assertThat(outcome.discrepanciesFound()).isEqualTo(1);

        verify(discrepancyRecorder).recordDiscrepancy(
                eq(runId),
                eq(DiscrepancyType.RESERVATION_PAYMENT_MISMATCH),
                eq(paymentId.getValue().toString()),
                org.mockito.ArgumentMatchers.contains("expected CONSUMED, found ACTIVE"),
                any(),
                eq(now)
        );
    }

    @Test
    void shouldNotReportDiscrepancyWhenSettledPaymentHasConsumedReservation() {
        ReconciliationRunId runId = ReconciliationRunId.generate();
        Instant now = Instant.now();
        PaymentId paymentId = PaymentId.generate();
        ReservationToken token = ReservationToken.generate();
        AccountId accountId = AccountId.generate();
        Money amount = new Money(new BigDecimal("100.00"), new Currency("USD"));

        PaymentReconciliationRecord payment = new PaymentReconciliationRecord(
                paymentId,
                PaymentStatus.SETTLED,
                accountId,
                amount,
                Optional.of(token),
                Optional.of("ROUTE-1"),
                Optional.of("DISP-1"),
                Optional.of(java.util.UUID.randomUUID()),
                Optional.empty(),
                now.minus(20, ChronoUnit.MINUTES),
                now.minus(10, ChronoUnit.MINUTES)
        );

        when(paymentReconciliationPort.findPaymentsForReconciliation(any(), any(), any(), eq(50)))
                .thenReturn(new PaymentReconciliationPage(List.of(payment), false, Optional.empty()));

        ReservationView reservation = new ReservationView(
                token,
                accountId,
                ReservationStatus.CONSUMED,
                amount,
                PaymentReference.of("REF-1")
        );
        when(bankingService.findReservation(token)).thenReturn(Optional.of(reservation));

        CheckExecutionOutcome outcome = check.execute(runId, now);

        assertThat(outcome.status()).isEqualTo(ReconciliationCheckStatus.COMPLETED);
        assertThat(outcome.itemsScanned()).isEqualTo(1);
        assertThat(outcome.discrepanciesFound()).isZero();
    }

    @Test
    void shouldDetectFailedPaymentWithConsumedReservation() {
        ReconciliationRunId runId = ReconciliationRunId.generate();
        Instant now = Instant.now();
        PaymentId paymentId = PaymentId.generate();
        ReservationToken token = ReservationToken.generate();
        AccountId accountId = AccountId.generate();
        Money amount = new Money(new BigDecimal("100.00"), new Currency("USD"));

        PaymentReconciliationRecord payment = new PaymentReconciliationRecord(
                paymentId,
                PaymentStatus.FAILED,
                accountId,
                amount,
                Optional.of(token),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                now.minus(20, ChronoUnit.MINUTES),
                now.minus(10, ChronoUnit.MINUTES)
        );

        when(paymentReconciliationPort.findPaymentsForReconciliation(any(), any(), any(), eq(50)))
                .thenReturn(new PaymentReconciliationPage(List.of(payment), false, Optional.empty()));

        // Reservation should have been RELEASED, but is CONSUMED
        ReservationView reservation = new ReservationView(
                token,
                accountId,
                ReservationStatus.CONSUMED,
                amount,
                PaymentReference.of("REF-1")
        );
        when(bankingService.findReservation(token)).thenReturn(Optional.of(reservation));

        CheckExecutionOutcome outcome = check.execute(runId, now);

        assertThat(outcome.status()).isEqualTo(ReconciliationCheckStatus.COMPLETED);
        assertThat(outcome.itemsScanned()).isEqualTo(1);
        assertThat(outcome.discrepanciesFound()).isEqualTo(1);

        verify(discrepancyRecorder).recordDiscrepancy(
                eq(runId),
                eq(DiscrepancyType.RESERVATION_PAYMENT_MISMATCH),
                eq(paymentId.getValue().toString()),
                org.mockito.ArgumentMatchers.contains("expected RELEASED, found CONSUMED"),
                any(),
                eq(now)
        );
    }
}
