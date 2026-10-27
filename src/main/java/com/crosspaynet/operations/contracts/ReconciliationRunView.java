package com.crosspaynet.operations.contracts;

import com.crosspaynet.operations.domain.ReconciliationRunId;
import com.crosspaynet.operations.domain.ReconciliationRunStatus;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable read-only view of a reconciliation run.
 */
public record ReconciliationRunView(
        ReconciliationRunId runId,
        ReconciliationRunStatus status,
        Instant startedAt,
        Instant completedAt,
        int checksEvaluated,
        int discrepancyCount,
        String triggeredBy,
        String failureReason,
        Instant createdAt,
        Instant updatedAt
) {

    public ReconciliationRunView {
        Objects.requireNonNull(runId, "runId cannot be null.");
        Objects.requireNonNull(status, "status cannot be null.");
        Objects.requireNonNull(startedAt, "startedAt cannot be null.");
        Objects.requireNonNull(triggeredBy, "triggeredBy cannot be null.");
        Objects.requireNonNull(createdAt, "createdAt cannot be null.");
        Objects.requireNonNull(updatedAt, "updatedAt cannot be null.");
    }
}
