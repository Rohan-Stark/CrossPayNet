package com.crosspaynet.operations.application.check;

import com.crosspaynet.operations.domain.ReconciliationCheckStatus;

import java.util.Objects;

/**
 * Outcome resulting from executing a single reconciliation check.
 */
public record CheckExecutionOutcome(
        String checkName,
        ReconciliationCheckStatus status,
        int itemsScanned,
        int discrepanciesFound,
        String errorSummary
) {

    public CheckExecutionOutcome {
        Objects.requireNonNull(checkName, "checkName cannot be null.");
        Objects.requireNonNull(status, "status cannot be null.");
    }

    public static CheckExecutionOutcome success(String checkName, int itemsScanned, int discrepanciesFound) {
        return new CheckExecutionOutcome(checkName, ReconciliationCheckStatus.COMPLETED, itemsScanned, discrepanciesFound, null);
    }

    public static CheckExecutionOutcome failure(String checkName, int itemsScanned, int discrepanciesFound, String errorSummary) {
        return new CheckExecutionOutcome(checkName, ReconciliationCheckStatus.FAILED, itemsScanned, discrepanciesFound, errorSummary);
    }
}
