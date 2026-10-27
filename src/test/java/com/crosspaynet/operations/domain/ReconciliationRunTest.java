package com.crosspaynet.operations.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReconciliationRunTest {

    @Test
    void shouldStartRunInInProgressStateWithZeroCounts() {
        ReconciliationRunId runId = ReconciliationRunId.generate();
        Instant now = Instant.now();

        ReconciliationRun run = ReconciliationRun.start(runId, "OPERATOR_ALICE", now);

        assertThat(run.getRunId()).isEqualTo(runId);
        assertThat(run.getStatus()).isEqualTo(ReconciliationRunStatus.IN_PROGRESS);
        assertThat(run.getTriggeredBy()).isEqualTo("OPERATOR_ALICE");
        assertThat(run.getChecksEvaluated()).isZero();
        assertThat(run.getDiscrepancyCount()).isZero();
        assertThat(run.getCompletedAt()).isNull();
        assertThat(run.getFailureReason()).isNull();
        assertThat(run.getStartedAt()).isEqualTo(now);
        assertThat(run.getVersion()).isZero();
    }

    @Test
    void shouldCompleteRunSuccessfully() {
        ReconciliationRunId runId = ReconciliationRunId.generate();
        Instant start = Instant.now().minus(5, ChronoUnit.MINUTES);
        Instant end = Instant.now();

        ReconciliationRun run = ReconciliationRun.start(runId, "OPERATOR_ALICE", start);
        run.complete(4, 2, end);

        assertThat(run.getStatus()).isEqualTo(ReconciliationRunStatus.COMPLETED);
        assertThat(run.getChecksEvaluated()).isEqualTo(4);
        assertThat(run.getDiscrepancyCount()).isEqualTo(2);
        assertThat(run.getCompletedAt()).isEqualTo(end);
        assertThat(run.getFailureReason()).isNull();
        assertThat(run.getUpdatedAt()).isEqualTo(end);
    }

    @Test
    void shouldMarkRunAsPartialFailure() {
        ReconciliationRunId runId = ReconciliationRunId.generate();
        Instant start = Instant.now().minus(5, ChronoUnit.MINUTES);
        Instant end = Instant.now();

        ReconciliationRun run = ReconciliationRun.start(runId, "OPERATOR_ALICE", start);
        run.markPartialFailure(3, 1, "Banking check timed out", end);

        assertThat(run.getStatus()).isEqualTo(ReconciliationRunStatus.PARTIAL_FAILURE);
        assertThat(run.getChecksEvaluated()).isEqualTo(3);
        assertThat(run.getDiscrepancyCount()).isEqualTo(1);
        assertThat(run.getFailureReason()).isEqualTo("Banking check timed out");
        assertThat(run.getCompletedAt()).isEqualTo(end);
    }

    @Test
    void shouldFailRunWithReason() {
        ReconciliationRunId runId = ReconciliationRunId.generate();
        Instant start = Instant.now().minus(5, ChronoUnit.MINUTES);
        Instant end = Instant.now();

        ReconciliationRun run = ReconciliationRun.start(runId, "OPERATOR_ALICE", start);
        run.fail("Fatal database connection error during run", end);

        assertThat(run.getStatus()).isEqualTo(ReconciliationRunStatus.FAILED);
        assertThat(run.getFailureReason()).isEqualTo("Fatal database connection error during run");
        assertThat(run.getCompletedAt()).isEqualTo(end);
    }

    @Test
    void shouldRejectTransitionFromTerminalStatus() {
        ReconciliationRunId runId = ReconciliationRunId.generate();
        Instant now = Instant.now();

        ReconciliationRun run = ReconciliationRun.start(runId, "OPERATOR_ALICE", now);
        run.complete(1, 0, now.plus(1, ChronoUnit.SECONDS));

        assertThatThrownBy(() -> run.complete(2, 0, now.plus(2, ChronoUnit.SECONDS)))
                .isInstanceOf(InvalidOperationsStateException.class)
                .hasMessageContaining("terminal status");

        assertThatThrownBy(() -> run.markPartialFailure(1, 0, "err", now.plus(2, ChronoUnit.SECONDS)))
                .isInstanceOf(InvalidOperationsStateException.class);

        assertThatThrownBy(() -> run.fail("err", now.plus(2, ChronoUnit.SECONDS)))
                .isInstanceOf(InvalidOperationsStateException.class);
    }

    @Test
    void shouldRejectInvalidCountsAndTimes() {
        ReconciliationRunId runId = ReconciliationRunId.generate();
        Instant start = Instant.now();

        ReconciliationRun run = ReconciliationRun.start(runId, "OPERATOR_ALICE", start);

        assertThatThrownBy(() -> run.complete(-1, 0, start.plus(1, ChronoUnit.MINUTES)))
                .isInstanceOf(OperationsValidationException.class)
                .hasMessageContaining("checksEvaluated cannot be negative");

        assertThatThrownBy(() -> run.complete(1, -1, start.plus(1, ChronoUnit.MINUTES)))
                .isInstanceOf(OperationsValidationException.class)
                .hasMessageContaining("discrepancyCount cannot be negative");

        assertThatThrownBy(() -> run.complete(1, 0, start.minus(1, ChronoUnit.SECONDS)))
                .isInstanceOf(OperationsValidationException.class)
                .hasMessageContaining("cannot be before startedAt");

        assertThatThrownBy(() -> run.fail("   ", start.plus(1, ChronoUnit.SECONDS)))
                .isInstanceOf(OperationsValidationException.class)
                .hasMessageContaining("failureReason cannot be blank");
    }

    @Test
    void shouldEnforceInvariantsOnDirectConstruction() {
        Instant now = Instant.now();
        ReconciliationRunId runId = ReconciliationRunId.generate();

        assertThatThrownBy(() -> new ReconciliationRun(
                runId, -1L, ReconciliationRunStatus.IN_PROGRESS, now, null, 0, 0, "OP", null, now, now
        )).isInstanceOf(OperationsValidationException.class).hasMessageContaining("version");

        assertThatThrownBy(() -> new ReconciliationRun(
                runId, 0L, ReconciliationRunStatus.COMPLETED, now, null, 0, 0, "OP", null, now, now
        )).isInstanceOf(OperationsValidationException.class).hasMessageContaining("completedAt cannot be null");
    }
}
