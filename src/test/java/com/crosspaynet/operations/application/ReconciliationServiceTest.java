package com.crosspaynet.operations.application;

import com.crosspaynet.operations.application.check.CheckExecutionOutcome;
import com.crosspaynet.operations.application.check.ReconciliationCheckExecutor;
import com.crosspaynet.operations.contracts.ReconciliationRunView;
import com.crosspaynet.operations.contracts.TriggerReconciliationRunCommand;
import com.crosspaynet.operations.domain.OperationsConflictException;
import com.crosspaynet.operations.domain.ReconciliationRunId;
import com.crosspaynet.operations.domain.ReconciliationRunStatus;
import com.crosspaynet.operations.infrastructure.persistence.OperationsMapper;
import com.crosspaynet.operations.infrastructure.persistence.ReconciliationCheckExecutionEntity;
import com.crosspaynet.operations.infrastructure.persistence.ReconciliationCheckExecutionRepository;
import com.crosspaynet.operations.infrastructure.persistence.ReconciliationDiscrepancyRepository;
import com.crosspaynet.operations.infrastructure.persistence.ReconciliationRunEntity;
import com.crosspaynet.operations.infrastructure.persistence.ReconciliationRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReconciliationServiceTest {

    @Mock
    private ReconciliationRunRepository runRepository;

    @Mock
    private ReconciliationCheckExecutionRepository checkExecutionRepository;

    @Mock
    private ReconciliationDiscrepancyRepository discrepancyRepository;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private ReconciliationCheckExecutor check1;

    @Mock
    private ReconciliationCheckExecutor check2;

    private OperationsMapper operationsMapper;
    private ReconciliationServiceImpl reconciliationService;

    @BeforeEach
    void setUp() {
        operationsMapper = new OperationsMapper();
        lenient().when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        reconciliationService = new ReconciliationServiceImpl(
                runRepository,
                checkExecutionRepository,
                discrepancyRepository,
                operationsMapper,
                List.of(check1, check2),
                transactionManager
        );
    }

    @Test
    void shouldCompleteRunSuccessfullyWhenAllChecksSucceed() {
        when(runRepository.findByStatus(ReconciliationRunStatus.IN_PROGRESS.name())).thenReturn(List.of());
        when(runRepository.existsByStatus(ReconciliationRunStatus.IN_PROGRESS.name())).thenReturn(false);

        when(check1.execute(any(), any())).thenReturn(CheckExecutionOutcome.success("CHECK_1", 10, 1));
        when(check2.execute(any(), any())).thenReturn(CheckExecutionOutcome.success("CHECK_2", 20, 2));

        Instant past = Instant.now().minusSeconds(10);
        when(runRepository.findById(any())).thenAnswer(invocation -> {
            UUID id = invocation.getArgument(0);
            return Optional.of(new ReconciliationRunEntity(
                    id, 0L, "IN_PROGRESS", past, null, 0, 0, "OPERATOR_TEST", null, past, past
            ));
        });

        ReconciliationRunView result = reconciliationService.triggerRun(new TriggerReconciliationRunCommand("OPERATOR_TEST"));

        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo(ReconciliationRunStatus.COMPLETED);
        assertThat(result.checksEvaluated()).isEqualTo(2);
        assertThat(result.discrepancyCount()).isEqualTo(3);
        assertThat(result.failureReason()).isNull();

        verify(checkExecutionRepository, times(2)).saveAndFlush(any(ReconciliationCheckExecutionEntity.class));
    }

    @Test
    void shouldMarkRunAsPartialFailureWhenOneCheckFails() {
        when(runRepository.findByStatus(ReconciliationRunStatus.IN_PROGRESS.name())).thenReturn(List.of());
        when(runRepository.existsByStatus(ReconciliationRunStatus.IN_PROGRESS.name())).thenReturn(false);

        when(check1.execute(any(), any())).thenReturn(CheckExecutionOutcome.success("CHECK_1", 10, 0));
        when(check2.execute(any(), any())).thenReturn(CheckExecutionOutcome.failure("CHECK_2", 5, 0, "Network timeout during check 2"));

        Instant past = Instant.now().minusSeconds(10);
        when(runRepository.findById(any())).thenAnswer(invocation -> {
            UUID id = invocation.getArgument(0);
            return Optional.of(new ReconciliationRunEntity(
                    id, 0L, "IN_PROGRESS", past, null, 0, 0, "OPERATOR_TEST", null, past, past
            ));
        });

        ReconciliationRunView result = reconciliationService.triggerRun(new TriggerReconciliationRunCommand("OPERATOR_TEST"));

        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo(ReconciliationRunStatus.PARTIAL_FAILURE);
        assertThat(result.checksEvaluated()).isEqualTo(2);
        assertThat(result.failureReason()).contains("One or more reconciliation checks encountered failures");

        verify(checkExecutionRepository, times(2)).saveAndFlush(any(ReconciliationCheckExecutionEntity.class));
    }

    @Test
    void shouldRecoverAbandonedRunAndTransitionItToFailed() {
        UUID abandonedRunId = UUID.randomUUID();
        Instant past = Instant.now().minusSeconds(3600);
        ReconciliationRunEntity abandonedEntity = new ReconciliationRunEntity(
                abandonedRunId, 0L, "IN_PROGRESS", past, null, 1, 0, "PREVIOUS_CRASHED_INSTANCE", null, past, past
        );

        when(runRepository.findByStatus(ReconciliationRunStatus.IN_PROGRESS.name()))
                .thenReturn(List.of(abandonedEntity));
        when(runRepository.findById(abandonedRunId)).thenReturn(Optional.of(abandonedEntity));

        int recovered = reconciliationService.recoverAbandonedRuns();

        assertThat(recovered).isEqualTo(1);
        ArgumentCaptor<ReconciliationRunEntity> captor = ArgumentCaptor.forClass(ReconciliationRunEntity.class);
        verify(runRepository).saveAndFlush(captor.capture());

        ReconciliationRunEntity saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(ReconciliationRunStatus.FAILED.name());
        assertThat(saved.getFailureReason()).contains("Abandoned run recovered");
        assertThat(saved.getCompletedAt()).isNotNull();
        assertThat(saved.getChecksEvaluated()).isEqualTo(1); // Historical checks preserved
    }

    @Test
    void shouldRejectConcurrentRunWhenRunLockIsHeld() throws Exception {
        when(runRepository.findByStatus(ReconciliationRunStatus.IN_PROGRESS.name())).thenReturn(List.of());
        when(runRepository.existsByStatus(ReconciliationRunStatus.IN_PROGRESS.name())).thenReturn(false);

        CountDownLatch checkStarted = new CountDownLatch(1);
        CountDownLatch checkHold = new CountDownLatch(1);

        when(check1.execute(any(), any())).thenAnswer(inv -> {
            checkStarted.countDown();
            checkHold.await();
            return CheckExecutionOutcome.success("CHECK_1", 1, 0);
        });
        when(check2.execute(any(), any())).thenReturn(CheckExecutionOutcome.success("CHECK_2", 1, 0));

        Instant past = Instant.now().minusSeconds(10);
        when(runRepository.findById(any())).thenAnswer(invocation -> {
            UUID id = invocation.getArgument(0);
            return Optional.of(new ReconciliationRunEntity(
                    id, 0L, "IN_PROGRESS", past, null, 0, 0, "OPERATOR_1", null, past, past
            ));
        });

        // Start first run in background
        AtomicBoolean concurrentFailedAsExpected = new AtomicBoolean(false);
        Thread background = new Thread(() -> {
            reconciliationService.triggerRun(new TriggerReconciliationRunCommand("OPERATOR_1"));
        });
        background.start();

        checkStarted.await();

        // While first run is executing, concurrent trigger should fail
        assertThatThrownBy(() -> reconciliationService.triggerRun(new TriggerReconciliationRunCommand("OPERATOR_2")))
                .isInstanceOf(OperationsConflictException.class)
                .hasMessageContaining("currently executing");

        // Release check
        checkHold.countDown();
        background.join();
    }
}
