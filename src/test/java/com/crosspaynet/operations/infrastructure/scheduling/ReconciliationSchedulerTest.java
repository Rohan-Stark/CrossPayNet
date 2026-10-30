package com.crosspaynet.operations.infrastructure.scheduling;

import com.crosspaynet.operations.contracts.ReconciliationRunView;
import com.crosspaynet.operations.contracts.ReconciliationService;
import com.crosspaynet.operations.contracts.TriggerReconciliationRunCommand;
import com.crosspaynet.operations.domain.OperationsConflictException;
import com.crosspaynet.operations.domain.ReconciliationRunId;
import com.crosspaynet.operations.domain.ReconciliationRunStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReconciliationSchedulerTest {

    private ReconciliationService reconciliationService;
    private ReconciliationScheduler scheduler;

    @BeforeEach
    void setUp() {
        reconciliationService = mock(ReconciliationService.class);
        scheduler = new ReconciliationScheduler(reconciliationService);
    }

    @Test
    @DisplayName("Should reject null ReconciliationService in constructor")
    void shouldRejectNullReconciliationServiceInConstructor() {
        assertThatThrownBy(() -> new ReconciliationScheduler(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("reconciliationService cannot be null");
    }

    @Test
    @DisplayName("Scheduled run triggers reconciliation service with SYSTEM_SCHEDULER operator")
    void shouldTriggerRunSuccessfullyWithSystemSchedulerOperator() {
        ReconciliationRunId runId = ReconciliationRunId.generate();
        Instant now = Instant.now();
        ReconciliationRunView runView = new ReconciliationRunView(
                runId,
                ReconciliationRunStatus.COMPLETED,
                now,
                now,
                4,
                0,
                ReconciliationScheduler.TRIGGER_SOURCE,
                null,
                now,
                now
        );
        when(reconciliationService.triggerRun(any(TriggerReconciliationRunCommand.class))).thenReturn(runView);

        scheduler.scheduledReconciliationRun();

        ArgumentCaptor<TriggerReconciliationRunCommand> captor = ArgumentCaptor.forClass(TriggerReconciliationRunCommand.class);
        verify(reconciliationService, times(1)).triggerRun(captor.capture());
        assertThat(captor.getValue().triggeredBy()).isEqualTo("SYSTEM_SCHEDULER");
    }

    @Test
    @DisplayName("Should catch and handle OperationsConflictException gracefully without rethrowing")
    void shouldCatchAndHandleOperationsConflictExceptionGracefully() {
        when(reconciliationService.triggerRun(any(TriggerReconciliationRunCommand.class)))
                .thenThrow(new OperationsConflictException("Another reconciliation run is currently active"));

        assertThatCode(() -> scheduler.scheduledReconciliationRun())
                .doesNotThrowAnyException();

        verify(reconciliationService, times(1)).triggerRun(any(TriggerReconciliationRunCommand.class));
    }

    @Test
    @DisplayName("Should catch and handle unexpected Exception gracefully without rethrowing")
    void shouldCatchAndHandleUnexpectedExceptionGracefully() {
        when(reconciliationService.triggerRun(any(TriggerReconciliationRunCommand.class)))
                .thenThrow(new RuntimeException("Simulated unexpected database failure"));

        assertThatCode(() -> scheduler.scheduledReconciliationRun())
                .doesNotThrowAnyException();

        verify(reconciliationService, times(1)).triggerRun(any(TriggerReconciliationRunCommand.class));
    }
}
