package com.crosspaynet.operations.infrastructure.web;

import com.crosspaynet.operations.contracts.ReconciliationRunView;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Public response DTO for reconciliation run summaries.
 */
public record ReconciliationRunResponse(
        UUID runId,
        String status,
        Instant startedAt,
        Instant completedAt,
        int checksEvaluated,
        int discrepancyCount,
        String triggeredBy,
        String failureReason,
        Instant createdAt,
        Instant updatedAt
) {
    public ReconciliationRunResponse {
        Objects.requireNonNull(runId, "runId cannot be null.");
        Objects.requireNonNull(status, "status cannot be null.");
        Objects.requireNonNull(startedAt, "startedAt cannot be null.");
        Objects.requireNonNull(triggeredBy, "triggeredBy cannot be null.");
    }

    public static ReconciliationRunResponse from(ReconciliationRunView view) {
        if (view == null) return null;
        return new ReconciliationRunResponse(
                view.runId().getValue(),
                view.status().name(),
                view.startedAt(),
                view.completedAt(),
                view.checksEvaluated(),
                view.discrepancyCount(),
                view.triggeredBy(),
                view.failureReason(),
                view.createdAt(),
                view.updatedAt()
        );
    }
}
