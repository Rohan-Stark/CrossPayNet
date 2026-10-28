package com.crosspaynet.operations.infrastructure.web;

import com.crosspaynet.operations.contracts.ReconciliationCheckExecutionView;
import com.crosspaynet.operations.contracts.ReconciliationRunView;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Public response DTO for detailed reconciliation run inspection, including check outcomes.
 */
public record ReconciliationRunDetailResponse(
        UUID runId,
        String status,
        Instant startedAt,
        Instant completedAt,
        int checksEvaluated,
        int discrepancyCount,
        String triggeredBy,
        String failureReason,
        Instant createdAt,
        Instant updatedAt,
        List<ReconciliationCheckExecutionResponse> checks
) {
    public ReconciliationRunDetailResponse {
        Objects.requireNonNull(runId, "runId cannot be null.");
        Objects.requireNonNull(status, "status cannot be null.");
        Objects.requireNonNull(startedAt, "startedAt cannot be null.");
        Objects.requireNonNull(triggeredBy, "triggeredBy cannot be null.");
        checks = checks != null ? List.copyOf(checks) : List.of();
    }

    public static ReconciliationRunDetailResponse from(
            ReconciliationRunView run,
            List<ReconciliationCheckExecutionView> checks
    ) {
        if (run == null) return null;
        List<ReconciliationCheckExecutionResponse> checkResponses = checks != null
                ? checks.stream().map(ReconciliationCheckExecutionResponse::from).toList()
                : List.of();

        return new ReconciliationRunDetailResponse(
                run.runId().getValue(),
                run.status().name(),
                run.startedAt(),
                run.completedAt(),
                run.checksEvaluated(),
                run.discrepancyCount(),
                run.triggeredBy(),
                run.failureReason(),
                run.createdAt(),
                run.updatedAt(),
                checkResponses
        );
    }
}
