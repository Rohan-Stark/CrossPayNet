package com.crosspaynet.operations.application.check;

import com.crosspaynet.banking.contracts.AccountId;
import com.crosspaynet.banking.contracts.ReservationToken;
import com.crosspaynet.common.money.Currency;
import com.crosspaynet.common.money.Money;
import com.crosspaynet.ledger.EntryDirection;
import com.crosspaynet.ledger.LedgerAccountRef;
import com.crosspaynet.ledger.LedgerAccountType;
import com.crosspaynet.ledger.LedgerEntryView;
import com.crosspaynet.ledger.LedgerPostingKey;
import com.crosspaynet.ledger.LedgerService;
import com.crosspaynet.ledger.LedgerTransactionId;
import com.crosspaynet.ledger.LedgerTransactionType;
import com.crosspaynet.ledger.LedgerTransactionView;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SettlementLedgerInconsistencyCheckTest {

    @Mock
    private PaymentReconciliationPort paymentReconciliationPort;

    @Mock
    private LedgerService ledgerService;

    @Mock
    private DiscrepancyRecorder discrepancyRecorder;

    private OperationsReconciliationProperties properties;
    private SettlementLedgerInconsistencyCheck check;

    @BeforeEach
    void setUp() {
        properties = new OperationsReconciliationProperties();
        properties.setGraceWindow(Duration.ofMinutes(5));
        properties.setPageSize(50);

        check = new SettlementLedgerInconsistencyCheck(
                paymentReconciliationPort,
                ledgerService,
                discrepancyRecorder,
                properties
        );
    }

    @Test
    void shouldDetectSettledPaymentMissingLedgerTransaction() {
        ReconciliationRunId runId = ReconciliationRunId.generate();
        Instant now = Instant.now();
        PaymentId paymentId = PaymentId.generate();

        PaymentReconciliationRecord payment = new PaymentReconciliationRecord(
                paymentId,
                PaymentStatus.SETTLED,
                AccountId.generate(),
                new Money(new BigDecimal("500.00"), new Currency("USD")),
                Optional.of(ReservationToken.generate()),
                Optional.of("ROUTE-US"),
                Optional.of("DISP-1"),
                Optional.empty(),
                Optional.empty(),
                now.minus(20, ChronoUnit.MINUTES),
                now.minus(10, ChronoUnit.MINUTES)
        );

        when(paymentReconciliationPort.findPaymentsForReconciliation(any(), any(), any(), eq(50)))
                .thenReturn(new PaymentReconciliationPage(List.of(payment), false, Optional.empty()));

        LedgerPostingKey postingKey = LedgerPostingKey.of("PAYMENT_SETTLEMENT:" + paymentId.getValue());
        when(ledgerService.findByPostingKey(postingKey)).thenReturn(Optional.empty());

        CheckExecutionOutcome outcome = check.execute(runId, now);

        assertThat(outcome.status()).isEqualTo(ReconciliationCheckStatus.COMPLETED);
        assertThat(outcome.itemsScanned()).isEqualTo(1);
        assertThat(outcome.discrepanciesFound()).isEqualTo(1);

        verify(discrepancyRecorder).recordDiscrepancy(
                eq(runId),
                eq(DiscrepancyType.PAYMENT_LEDGER_BREAK),
                eq(paymentId.getValue().toString()),
                org.mockito.ArgumentMatchers.contains("no matching ledger transaction"),
                any(),
                eq(now)
        );
    }

    @Test
    void shouldDetectSettledPaymentWithAmountMismatchInLedger() {
        ReconciliationRunId runId = ReconciliationRunId.generate();
        Instant now = Instant.now();
        PaymentId paymentId = PaymentId.generate();
        Money paymentAmount = new Money(new BigDecimal("500.00"), new Currency("USD"));
        Money ledgerAmount = new Money(new BigDecimal("400.00"), new Currency("USD"));

        PaymentReconciliationRecord payment = new PaymentReconciliationRecord(
                paymentId,
                PaymentStatus.SETTLED,
                AccountId.generate(),
                paymentAmount,
                Optional.of(ReservationToken.generate()),
                Optional.of("ROUTE-US"),
                Optional.of("DISP-1"),
                Optional.empty(),
                Optional.empty(),
                now.minus(20, ChronoUnit.MINUTES),
                now.minus(10, ChronoUnit.MINUTES)
        );

        when(paymentReconciliationPort.findPaymentsForReconciliation(any(), any(), any(), eq(50)))
                .thenReturn(new PaymentReconciliationPage(List.of(payment), false, Optional.empty()));

        LedgerPostingKey postingKey = LedgerPostingKey.of("PAYMENT_SETTLEMENT:" + paymentId.getValue());
        LedgerTransactionView ledgerTx = new LedgerTransactionView(
                LedgerTransactionId.generate(),
                LedgerTransactionType.STANDARD,
                postingKey,
                new Currency("USD"),
                ledgerAmount,
                "EXT-REF",
                "Settlement transaction",
                null,
                now.minus(10, ChronoUnit.MINUTES),
                sampleEntries(ledgerAmount)
        );
        when(ledgerService.findByPostingKey(postingKey)).thenReturn(Optional.of(ledgerTx));

        CheckExecutionOutcome outcome = check.execute(runId, now);

        assertThat(outcome.status()).isEqualTo(ReconciliationCheckStatus.COMPLETED);
        assertThat(outcome.itemsScanned()).isEqualTo(1);
        assertThat(outcome.discrepanciesFound()).isEqualTo(1);

        verify(discrepancyRecorder).recordDiscrepancy(
                eq(runId),
                eq(DiscrepancyType.PAYMENT_LEDGER_BREAK),
                eq(paymentId.getValue().toString()),
                org.mockito.ArgumentMatchers.contains("amount/currency mismatch"),
                any(),
                eq(now)
        );
    }

    @Test
    void shouldNotReportDiscrepancyWhenSettledPaymentMatchesLedgerTransaction() {
        ReconciliationRunId runId = ReconciliationRunId.generate();
        Instant now = Instant.now();
        PaymentId paymentId = PaymentId.generate();
        Money amount = new Money(new BigDecimal("500.00"), new Currency("USD"));
        UUID txId = UUID.randomUUID();

        PaymentReconciliationRecord payment = new PaymentReconciliationRecord(
                paymentId,
                PaymentStatus.SETTLED,
                AccountId.generate(),
                amount,
                Optional.of(ReservationToken.generate()),
                Optional.of("ROUTE-US"),
                Optional.of("DISP-1"),
                Optional.of(txId),
                Optional.empty(),
                now.minus(20, ChronoUnit.MINUTES),
                now.minus(10, ChronoUnit.MINUTES)
        );

        when(paymentReconciliationPort.findPaymentsForReconciliation(any(), any(), any(), eq(50)))
                .thenReturn(new PaymentReconciliationPage(List.of(payment), false, Optional.empty()));

        LedgerPostingKey postingKey = LedgerPostingKey.of("PAYMENT_SETTLEMENT:" + paymentId.getValue());
        LedgerTransactionView ledgerTx = new LedgerTransactionView(
                new LedgerTransactionId(txId),
                LedgerTransactionType.STANDARD,
                postingKey,
                new Currency("USD"),
                amount,
                "EXT-REF",
                "Settlement transaction",
                null,
                now.minus(10, ChronoUnit.MINUTES),
                sampleEntries(amount)
        );
        when(ledgerService.findByPostingKey(postingKey)).thenReturn(Optional.of(ledgerTx));

        CheckExecutionOutcome outcome = check.execute(runId, now);

        assertThat(outcome.status()).isEqualTo(ReconciliationCheckStatus.COMPLETED);
        assertThat(outcome.itemsScanned()).isEqualTo(1);
        assertThat(outcome.discrepanciesFound()).isZero();
    }

    private List<LedgerEntryView> sampleEntries(Money amount) {
        return List.of(
                new LedgerEntryView(UUID.randomUUID(), 1, LedgerAccountRef.of(LedgerAccountType.BANKING_ACCOUNT, "SRC-1"), EntryDirection.DEBIT, amount),
                new LedgerEntryView(UUID.randomUUID(), 2, LedgerAccountRef.of(LedgerAccountType.INTERNAL, "CLR-1"), EntryDirection.CREDIT, amount)
        );
    }
}
