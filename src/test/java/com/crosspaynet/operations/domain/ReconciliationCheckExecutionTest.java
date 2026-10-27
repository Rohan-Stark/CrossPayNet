package com.crosspaynet.operations.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReconciliationCheckExecutionTest {

    @Test
    void shouldCreateValidCheckExecution() {
        ReconciliationCheckExecutionId checkId = ReconciliationCheckExecutionId.generate();
        ReconciliationRunId runId = ReconciliationRunId.generate();
        Instant executedAt = Instant.now();

        ReconciliationCheckExecution check = ReconciliationCheckExecution.create(
                checkId,
                runId,
                "PAYMENT_RESERVATION_ALIGNMENT",
                ReconciliationCheckStatus.COMPLETED,
                50,
                0,
                null,
                executedAt
        );

        assertThat(check.getCheckExecutionId()).isEqualTo(checkId);
        assertThat(check.getRunId()).isEqualTo(runId);
        assertThat(check.getCheckName()).isEqualTo("PAYMENT_RESERVATION_ALIGNMENT");
        assertThat(check.getStatus()).isEqualTo(ReconciliationCheckStatus.COMPLETED);
        assertThat(check.getItemsScanned()).isEqualTo(50);
        assertThat(check.getDiscrepanciesFound()).isZero();
        assertThat(check.getErrorSummary()).isNull();
        assertThat(check.getExecutedAt()).isEqualTo(executedAt);
    }

    @Test
    void shouldRejectNegativeCountsAndBlankNames() {
        ReconciliationCheckExecutionId checkId = ReconciliationCheckExecutionId.generate();
        ReconciliationRunId runId = ReconciliationRunId.generate();
        Instant now = Instant.now();

        assertThatThrownBy(() -> ReconciliationCheckExecution.create(
                checkId, runId, "CHECK1", ReconciliationCheckStatus.COMPLETED, -1, 0, null, now
        )).isInstanceOf(OperationsValidationException.class).hasMessageContaining("itemsScanned");

        assertThatThrownBy(() -> ReconciliationCheckExecution.create(
                checkId, runId, "CHECK1", ReconciliationCheckStatus.COMPLETED, 10, -1, null, now
        )).isInstanceOf(OperationsValidationException.class).hasMessageContaining("discrepanciesFound");

        assertThatThrownBy(() -> ReconciliationCheckExecution.create(
                checkId, runId, "  ", ReconciliationCheckStatus.COMPLETED, 10, 0, null, now
        )).isInstanceOf(OperationsValidationException.class).hasMessageContaining("checkName");
    }
}
