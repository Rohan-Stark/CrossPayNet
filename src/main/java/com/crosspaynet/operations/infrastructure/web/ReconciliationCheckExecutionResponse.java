package com.crosspaynet.operations.infrastructure.web;

import com.crosspaynet.operations.contracts.ReconciliationCheckExecutionView;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Public response DTO for individual check execution results within a run.
 */
public record ReconciliationCheckExecutionResponse(
        UUID checkExecutionId,
        UUID runId,
        String checkName,
        String status,
        int itemsScanned,
        int discrepanciesFound,
        String errorSummary,
        Instant executedAt
) {
    public ReconciliationCheckExecutionResponse {
        Objects.requireNonNull(checkExecutionId, "checkExecutionId cannot be null.");
        Objects.requireNonNull(runId, "runId cannot be null.");
        Objects.requireNonNull(checkName, "checkName cannot be null.");
        Objects.requireNonNull(status, "status cannot be null.");
        Objects.requireNonNull(executedAt, "executedAt cannot be null.");
    }

    public static ReconciliationCheckExecutionResponse from(ReconciliationCheckExecutionView view) {
        if (view == null) return null;
        return new ReconciliationCheckExecutionResponse(
                view.checkExecutionId().getValue(),
                view.runId().getValue(),
                view.checkName(),
                view.status().name(),
                view.itemsScanned(),
                view.discrepanciesFound(),
                view.errorSummary(),
                view.executedAt()
        );
    }
}
