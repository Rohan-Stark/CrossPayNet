package com.crosspaynet.operations;

import com.crosspaynet.PostgresTestContainerConfig;
import com.crosspaynet.banking.contracts.BankingReservationQueryPort;
import com.crosspaynet.banking.contracts.AccountId;
import com.crosspaynet.banking.contracts.PaymentReference;
import com.crosspaynet.banking.contracts.ReservationStatus;
import com.crosspaynet.banking.contracts.ReservationToken;
import com.crosspaynet.banking.contracts.ReservationView;
import com.crosspaynet.common.money.Currency;
import com.crosspaynet.common.money.Money;
import com.crosspaynet.events.contracts.EventPublicationQueryPort;
import com.crosspaynet.events.contracts.StalePublicationView;
import com.crosspaynet.ledger.LedgerPostingKey;
import com.crosspaynet.ledger.LedgerService;
import com.crosspaynet.ledger.LedgerTransactionId;
import com.crosspaynet.ledger.LedgerTransactionType;
import com.crosspaynet.ledger.LedgerTransactionView;
import com.crosspaynet.messaging.contracts.MessageId;
import com.crosspaynet.messaging.contracts.PaymentMessageQueryPort;
import com.crosspaynet.messaging.contracts.PaymentMessageStatus;
import com.crosspaynet.messaging.contracts.PaymentMessageType;
import com.crosspaynet.messaging.contracts.PaymentMessageView;
import com.crosspaynet.operations.application.ReconciliationServiceImpl;
import com.crosspaynet.operations.application.check.DiscrepancyRecorder;
import com.crosspaynet.operations.application.check.IncompleteEventPublicationCheck;
import com.crosspaynet.operations.application.check.PaymentReservationMismatchCheck;
import com.crosspaynet.operations.application.check.SettlementLedgerInconsistencyCheck;
import com.crosspaynet.operations.application.check.StalePaymentWorkflowCheck;
import com.crosspaynet.operations.contracts.ReconciliationCheckExecutionView;
import com.crosspaynet.operations.contracts.ReconciliationDiscrepancyView;
import com.crosspaynet.operations.contracts.ReconciliationRunView;
import com.crosspaynet.operations.contracts.TriggerReconciliationRunCommand;
import com.crosspaynet.operations.domain.DiscrepancyType;
import com.crosspaynet.operations.domain.OperationsConflictException;
import com.crosspaynet.operations.domain.ReconciliationCheckStatus;
import com.crosspaynet.operations.domain.ReconciliationRun;
import com.crosspaynet.operations.domain.ReconciliationRunId;
import com.crosspaynet.operations.domain.ReconciliationRunStatus;
import com.crosspaynet.operations.infrastructure.configuration.OperationsReconciliationProperties;
import com.crosspaynet.operations.infrastructure.persistence.OperationsMapper;
import com.crosspaynet.operations.infrastructure.persistence.ReconciliationCheckExecutionRepository;
import com.crosspaynet.operations.infrastructure.persistence.ReconciliationDiscrepancyEntity;
import com.crosspaynet.operations.infrastructure.persistence.ReconciliationDiscrepancyRepository;
import com.crosspaynet.operations.infrastructure.persistence.ReconciliationRunEntity;
import com.crosspaynet.operations.infrastructure.persistence.ReconciliationRunRepository;
import com.crosspaynet.payment.contracts.BicCode;
import com.crosspaynet.payment.contracts.PaymentId;
import com.crosspaynet.payment.contracts.PaymentReconciliationPage;
import com.crosspaynet.payment.contracts.PaymentReconciliationPort;
import com.crosspaynet.payment.contracts.PaymentReconciliationRecord;
import com.crosspaynet.payment.contracts.PaymentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({
        PostgresTestContainerConfig.class,
        OperationsMapper.class,
        DiscrepancyRecorder.class,
        OperationsReconciliationProperties.class,
        StalePaymentWorkflowCheck.class,
        PaymentReservationMismatchCheck.class,
        SettlementLedgerInconsistencyCheck.class,
        IncompleteEventPublicationCheck.class,
        ReconciliationServiceImpl.class
})
class ReconciliationEngineIntegrationTest {

    @Autowired
    private ReconciliationServiceImpl reconciliationService;

    @Autowired
    private ReconciliationRunRepository runRepository;

    @Autowired
    private ReconciliationCheckExecutionRepository checkExecutionRepository;

    @Autowired
    private ReconciliationDiscrepancyRepository discrepancyRepository;

    @Autowired
    private OperationsMapper operationsMapper;

    @MockitoBean
    private PaymentReconciliationPort paymentReconciliationPort;

    @MockitoBean
    private BankingReservationQueryPort bankingReservationQueryPort;

    @MockitoBean
    private LedgerService ledgerService;

    @MockitoBean
    private PaymentMessageQueryPort paymentMessageQueryPort;

    @MockitoBean
    private EventPublicationQueryPort eventPublicationQueryPort;

    @BeforeEach
    void cleanUp() {
        checkExecutionRepository.deleteAll();
        discrepancyRepository.deleteAll();
        runRepository.deleteAll();
    }

    @Test
    void shouldExecuteAllFourChecksAndRecordDiscrepanciesEndToEnd() {
        Instant now = Instant.now();

        // 1. Stale workflow fixture (Check A)
        PaymentId stalePaymentId = PaymentId.generate();
        PaymentReconciliationRecord staleRecord = new PaymentReconciliationRecord(
                stalePaymentId,
                PaymentStatus.PENDING_EXTERNAL,
                AccountId.generate(),
                new Money(new BigDecimal("100.00"), new Currency("USD")),
                Optional.of(ReservationToken.generate()),
                Optional.of("ROUTE-1"),
                Optional.of("EXT-1"),
                Optional.empty(),
                Optional.empty(),
                now.minus(40, ChronoUnit.MINUTES),
                now.minus(40, ChronoUnit.MINUTES)
        );

        when(paymentReconciliationPort.findPaymentsForReconciliation(
                argThat(statuses -> statuses != null && statuses.contains(PaymentStatus.PENDING_EXTERNAL)),
                any(), any(), anyInt()
        )).thenReturn(new PaymentReconciliationPage(List.of(staleRecord), false, Optional.empty()));

        PaymentMessageView timeoutMsg = new PaymentMessageView(
                MessageId.generate(), stalePaymentId, 1, "IDEM-1", PaymentMessageType.PACS_008,
                BicCode.of("TESTUS33XXX"), BicCode.of("RECVUS33XXX"), "ROUTE-1",
                new Money(new BigDecimal("100.00"), new Currency("USD")), "Ben", "123", "<payload/>", "dig",
                PaymentMessageStatus.TIMEOUT, null, null, null, "Timed out after 30s",
                now.minus(40, ChronoUnit.MINUTES), now.minus(40, ChronoUnit.MINUTES)
        );
        when(paymentMessageQueryPort.findMessagesForPayment(stalePaymentId)).thenReturn(List.of(timeoutMsg));

        // 2. Reservation mismatch fixture (Check B)
        PaymentId mismatchPaymentId = PaymentId.generate();
        ReservationToken resToken = ReservationToken.generate();
        PaymentReconciliationRecord mismatchRecord = new PaymentReconciliationRecord(
                mismatchPaymentId,
                PaymentStatus.SETTLED,
                AccountId.generate(),
                new Money(new BigDecimal("50.00"), new Currency("USD")),
                Optional.of(resToken),
                Optional.of("ROUTE-1"),
                Optional.of("EXT-2"),
                Optional.of(UUID.randomUUID()),
                Optional.empty(),
                now.minus(20, ChronoUnit.MINUTES),
                now.minus(20, ChronoUnit.MINUTES)
        );

        when(paymentReconciliationPort.findPaymentsForReconciliation(
                argThat(statuses -> statuses != null && statuses.contains(PaymentStatus.FAILED)),
                any(), any(), anyInt()
        )).thenReturn(new PaymentReconciliationPage(List.of(mismatchRecord), false, Optional.empty()));

        // Active reservation when payment is SETTLED -> mismatch!
        ReservationView resView = new ReservationView(
                resToken, AccountId.generate(), ReservationStatus.ACTIVE,
                new Money(new BigDecimal("50.00"), new Currency("USD")), PaymentReference.of("REF-MISMATCH")
        );
        when(bankingReservationQueryPort.findReservation(resToken)).thenReturn(Optional.of(resView));

        // 3. Settlement ledger inconsistency fixture (Check C)
        PaymentId ledgerBreakPaymentId = PaymentId.generate();
        PaymentReconciliationRecord settledRecord = new PaymentReconciliationRecord(
                ledgerBreakPaymentId,
                PaymentStatus.SETTLED,
                AccountId.generate(),
                new Money(new BigDecimal("300.00"), new Currency("USD")),
                Optional.of(ReservationToken.generate()),
                Optional.of("ROUTE-1"),
                Optional.of("EXT-3"),
                Optional.empty(),
                Optional.empty(),
                now.minus(15, ChronoUnit.MINUTES),
                now.minus(15, ChronoUnit.MINUTES)
        );

        when(paymentReconciliationPort.findPaymentsForReconciliation(
                argThat(statuses -> statuses != null && statuses.size() == 1 && statuses.contains(PaymentStatus.SETTLED)),
                any(), any(), anyInt()
        )).thenReturn(new PaymentReconciliationPage(List.of(settledRecord), false, Optional.empty()));

        LedgerPostingKey postingKey = LedgerPostingKey.of("PAYMENT_SETTLEMENT:" + ledgerBreakPaymentId.getValue());
        when(ledgerService.findByPostingKey(postingKey)).thenReturn(Optional.empty()); // missing ledger entry!

        // 4. Incomplete event publication fixture (Check D)
        UUID pubId = UUID.randomUUID();
        StalePublicationView pubView = new StalePublicationView(
                pubId, "CompliancePaymentListener", "PaymentInstructionReceived",
                now.minus(30, ChronoUnit.MINUTES), 2, Optional.empty()
        );
        when(eventPublicationQueryPort.findIncompletePublications(any(), any(), anyInt()))
                .thenReturn(new com.crosspaynet.events.contracts.EventPublicationPage(List.of(pubView), false, Optional.empty()));

        // Trigger Run 1
        ReconciliationRunView run1 = reconciliationService.triggerRun(new TriggerReconciliationRunCommand("OPERATOR_ALICE"));

        assertThat(run1.status()).isEqualTo(ReconciliationRunStatus.COMPLETED);
        assertThat(run1.checksEvaluated()).isEqualTo(4);
        assertThat(run1.discrepancyCount()).isEqualTo(4);

        List<ReconciliationCheckExecutionView> checks = reconciliationService.findCheckExecutions(run1.runId());
        assertThat(checks).hasSize(4);
        assertThat(checks).allMatch(c -> c.status() == ReconciliationCheckStatus.COMPLETED);

        List<ReconciliationDiscrepancyView> discrepancies = reconciliationService.findDiscrepanciesForRun(run1.runId());
        assertThat(discrepancies).hasSize(4);

        // Verify deduplication and repeat observation on Run 2
        ReconciliationRunView run2 = reconciliationService.triggerRun(new TriggerReconciliationRunCommand("AUTO_SCHEDULER"));
        assertThat(run2.status()).isEqualTo(ReconciliationRunStatus.COMPLETED);
        assertThat(run2.discrepancyCount()).isEqualTo(4);

        // Database total count of discrepancies should STILL be 4 (no duplicates)
        assertThat(discrepancyRepository.count()).isEqualTo(4);

        // Discrepancy consecutive detections must have incremented to 2
        ReconciliationDiscrepancyEntity staleDiscrepancy = discrepancyRepository
                .findByDiscrepancyKey(DiscrepancyType.STALE_IN_FLIGHT_WORKFLOW + ":" + stalePaymentId.getValue())
                .orElseThrow();
        assertThat(staleDiscrepancy.getConsecutiveDetections()).isEqualTo(2);
        assertThat(staleDiscrepancy.getFirstRunId()).isEqualTo(run1.runId().getValue());
        assertThat(staleDiscrepancy.getLatestRunId()).isEqualTo(run2.runId().getValue());
    }

    @Test
    void shouldMarkRunAsPartialFailureWhenOneCheckFailsWhileOthersSucceed() {
        Instant now = Instant.now();

        // Check A succeeds with 0 discrepancies
        when(paymentReconciliationPort.findPaymentsForReconciliation(
                argThat(statuses -> statuses != null && statuses.contains(PaymentStatus.PENDING_EXTERNAL)),
                any(), any(), anyInt()
        )).thenReturn(PaymentReconciliationPage.empty());

        // Check B throws an unexpected exception (e.g. database error in Banking)
        when(paymentReconciliationPort.findPaymentsForReconciliation(
                argThat(statuses -> statuses != null && statuses.contains(PaymentStatus.FAILED)),
                any(), any(), anyInt()
        )).thenThrow(new RuntimeException("Simulated connection timeout during Banking check"));

        // Check C succeeds with 0 discrepancies
        when(paymentReconciliationPort.findPaymentsForReconciliation(
                argThat(statuses -> statuses != null && statuses.size() == 1 && statuses.contains(PaymentStatus.SETTLED)),
                any(), any(), anyInt()
        )).thenReturn(PaymentReconciliationPage.empty());

        // Check D succeeds with 0 discrepancies
        when(eventPublicationQueryPort.findIncompletePublications(any(), any(), anyInt()))
                .thenReturn(com.crosspaynet.events.contracts.EventPublicationPage.empty());

        ReconciliationRunView run = reconciliationService.triggerRun(new TriggerReconciliationRunCommand("OPERATOR_BOB"));

        assertThat(run.status()).isEqualTo(ReconciliationRunStatus.PARTIAL_FAILURE);
        assertThat(run.checksEvaluated()).isEqualTo(4);
        assertThat(run.failureReason()).contains("One or more reconciliation checks encountered failures");

        List<ReconciliationCheckExecutionView> checks = reconciliationService.findCheckExecutions(run.runId());
        assertThat(checks).hasSize(4);

        ReconciliationCheckExecutionView failedCheck = checks.stream()
                .filter(c -> c.checkName().equals(PaymentReservationMismatchCheck.CHECK_NAME))
                .findFirst()
                .orElseThrow();
        assertThat(failedCheck.status()).isEqualTo(ReconciliationCheckStatus.FAILED);
        assertThat(failedCheck.errorSummary()).contains("Simulated connection timeout");
    }

    @Test
    void shouldRecoverPersistedAbandonedRunAndAllowSubsequentRunToExecute() {
        // Simulate a prior crashed application instance that left an IN_PROGRESS run in PostgreSQL
        UUID abandonedRunId = UUID.randomUUID();
        Instant past = Instant.now().minusSeconds(1800).truncatedTo(ChronoUnit.MICROS);
        ReconciliationRunEntity abandonedEntity = new ReconciliationRunEntity(
                abandonedRunId, null, ReconciliationRunStatus.IN_PROGRESS.name(),
                past, null, 1, 0, "CRASHED_INSTANCE_OPERATOR", null, past, past
        );
        runRepository.saveAndFlush(abandonedEntity);

        // Verify the abandoned run is in database as IN_PROGRESS
        ReconciliationRunEntity beforeTrigger = runRepository.findById(abandonedRunId).orElseThrow();
        assertThat(beforeTrigger.getStatus()).isEqualTo(ReconciliationRunStatus.IN_PROGRESS.name());

        // Setup clean mocks for the subsequent run
        when(paymentReconciliationPort.findPaymentsForReconciliation(any(), any(), any(), anyInt()))
                .thenReturn(PaymentReconciliationPage.empty());
        when(eventPublicationQueryPort.findIncompletePublications(any(), any(), anyInt()))
                .thenReturn(com.crosspaynet.events.contracts.EventPublicationPage.empty());

        // Trigger new run: should recover abandoned run first, then execute and complete new run
        ReconciliationRunView newRun = reconciliationService.triggerRun(new TriggerReconciliationRunCommand("NEW_OPERATOR"));

        assertThat(newRun.status()).isEqualTo(ReconciliationRunStatus.COMPLETED);
        assertThat(newRun.runId().getValue()).isNotEqualTo(abandonedRunId);

        // Verify the abandoned run was recovered as FAILED with audit reason
        ReconciliationRunEntity afterTrigger = runRepository.findById(abandonedRunId).orElseThrow();
        assertThat(afterTrigger.getStatus()).isEqualTo(ReconciliationRunStatus.FAILED.name());
        assertThat(afterTrigger.getFailureReason()).contains("Abandoned run recovered");
        assertThat(afterTrigger.getCompletedAt()).isNotNull();
        assertThat(afterTrigger.getChecksEvaluated()).isEqualTo(1); // Historical check count preserved
    }

    @Test
    void shouldNotMarkActivelyExecutingRunAsFailedDuringRecovery() throws Exception {
        CountDownLatch checkStarted = new CountDownLatch(1);
        CountDownLatch checkHold = new CountDownLatch(1);

        when(paymentReconciliationPort.findPaymentsForReconciliation(
                argThat(statuses -> statuses != null && statuses.contains(PaymentStatus.PENDING_EXTERNAL)),
                any(), any(), anyInt()
        )).thenAnswer(inv -> {
            checkStarted.countDown();
            checkHold.await();
            return PaymentReconciliationPage.empty();
        });

        when(paymentReconciliationPort.findPaymentsForReconciliation(
                argThat(statuses -> statuses != null && !statuses.contains(PaymentStatus.PENDING_EXTERNAL)),
                any(), any(), anyInt()
        )).thenReturn(PaymentReconciliationPage.empty());

        when(eventPublicationQueryPort.findIncompletePublications(any(), any(), anyInt()))
                .thenReturn(com.crosspaynet.events.contracts.EventPublicationPage.empty());

        // Start active run on background thread
        Thread activeThread = new Thread(() -> {
            reconciliationService.triggerRun(new TriggerReconciliationRunCommand("ACTIVE_OPERATOR"));
        });
        activeThread.start();

        checkStarted.await();

        // While active run is executing in this instance, attempt recovery
        int recovered = reconciliationService.recoverAbandonedRuns();
        assertThat(recovered).isEqualTo(0);

        // Verify active run in PostgreSQL is still IN_PROGRESS and not marked FAILED
        List<ReconciliationRunEntity> inProgress = runRepository.findByStatus(ReconciliationRunStatus.IN_PROGRESS.name());
        assertThat(inProgress).hasSize(1);
        assertThat(inProgress.get(0).getStatus()).isEqualTo(ReconciliationRunStatus.IN_PROGRESS.name());

        // Release check to complete
        checkHold.countDown();
        activeThread.join();

        // Verify run finished COMPLETED
        ReconciliationRunEntity completed = runRepository.findById(inProgress.get(0).getRunId()).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(ReconciliationRunStatus.COMPLETED.name());
    }

    @Test
    void shouldRejectConcurrentTriggersWhileRunIsExecuting() throws Exception {
        CountDownLatch checkStarted = new CountDownLatch(1);
        CountDownLatch checkHold = new CountDownLatch(1);

        when(paymentReconciliationPort.findPaymentsForReconciliation(
                argThat(statuses -> statuses != null && statuses.contains(PaymentStatus.PENDING_EXTERNAL)),
                any(), any(), anyInt()
        )).thenAnswer(inv -> {
            checkStarted.countDown();
            checkHold.await();
            return PaymentReconciliationPage.empty();
        });

        when(paymentReconciliationPort.findPaymentsForReconciliation(
                argThat(statuses -> statuses != null && !statuses.contains(PaymentStatus.PENDING_EXTERNAL)),
                any(), any(), anyInt()
        )).thenReturn(PaymentReconciliationPage.empty());

        when(eventPublicationQueryPort.findIncompletePublications(any(), any(), anyInt()))
                .thenReturn(com.crosspaynet.events.contracts.EventPublicationPage.empty());

        Thread activeThread = new Thread(() -> {
            reconciliationService.triggerRun(new TriggerReconciliationRunCommand("RUNNER_1"));
        });
        activeThread.start();

        checkStarted.await();

        // Concurrent trigger while runner 1 is executing should be rejected
        assertThatThrownBy(() -> reconciliationService.triggerRun(new TriggerReconciliationRunCommand("RUNNER_2")))
                .isInstanceOf(OperationsConflictException.class)
                .hasMessageContaining("currently executing");

        checkHold.countDown();
        activeThread.join();
    }

    @Test
    void shouldRecordTerminalFailureOutcomeOnRecoveryWithoutReportingSuccess() {
        UUID abandonedRunId = UUID.randomUUID();
        Instant past = Instant.now().minusSeconds(3600).truncatedTo(ChronoUnit.MICROS);
        ReconciliationRunEntity abandonedEntity = new ReconciliationRunEntity(
                abandonedRunId, null, ReconciliationRunStatus.IN_PROGRESS.name(),
                past, null, 2, 1, "ABANDONED_OP", null, past, past
        );
        runRepository.saveAndFlush(abandonedEntity);

        int recovered = reconciliationService.recoverAbandonedRuns();
        assertThat(recovered).isEqualTo(1);

        ReconciliationRunEntity recoveredEntity = runRepository.findById(abandonedRunId).orElseThrow();
        assertThat(recoveredEntity.getStatus()).isEqualTo(ReconciliationRunStatus.FAILED.name());
        assertThat(recoveredEntity.getFailureReason()).isEqualTo("Abandoned run recovered: execution interrupted by process termination or restart");
        assertThat(recoveredEntity.getCompletedAt()).isNotNull();
        assertThat(recoveredEntity.getChecksEvaluated()).isEqualTo(2); // Retains 2 checks
        assertThat(recoveredEntity.getDiscrepancyCount()).isEqualTo(1); // Retains 1 discrepancy
    }
}
