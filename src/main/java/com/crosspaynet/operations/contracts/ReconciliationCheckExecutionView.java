package com.crosspaynet.operations.contracts;

import com.crosspaynet.operations.domain.ReconciliationCheckExecutionId;
import com.crosspaynet.operations.domain.ReconciliationCheckStatus;
import com.crosspaynet.operations.domain.ReconciliationRunId;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable read-only view of a check execution within a reconciliation run.
 */
public record ReconciliationCheckExecutionView(
        ReconciliationCheckExecutionId checkExecutionId,
        ReconciliationRunId runId,
        String checkName,
        ReconciliationCheckStatus status,
        int itemsScanned,
        int discrepanciesFound,
        String errorSummary,
        Instant executedAt
) {

    public ReconciliationCheckExecutionView {
        Objects.requireNonNull(checkExecutionId, "checkExecutionId cannot be null.");
        Objects.requireNonNull(runId, "runId cannot be null.");
        Objects.requireNonNull(checkName, "checkName cannot be null.");
        Objects.requireNonNull(status, "status cannot be null.");
        Objects.requireNonNull(executedAt, "executedAt cannot be null.");
    }
}
