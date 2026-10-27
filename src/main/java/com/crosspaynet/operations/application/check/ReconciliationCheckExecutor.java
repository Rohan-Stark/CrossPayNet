package com.crosspaynet.operations.application.check;

import com.crosspaynet.operations.domain.ReconciliationRunId;

import java.time.Instant;

/**
 * Strategy interface implemented by individual reconciliation check executors.
 */
public interface ReconciliationCheckExecutor {

    /**
     * Unique name of the check (e.g. STALE_PAYMENT_WORKFLOW_CHECK).
     */
    String getCheckName();

    /**
     * Executes the check against the relevant module's read-only contracts.
     *
     * @param runId        parent reconciliation run identifier
     * @param runStartedAt timestamp at which the reconciliation run was started
     * @return outcome containing scan count, discrepancy count, and status
     */
    CheckExecutionOutcome execute(ReconciliationRunId runId, Instant runStartedAt);
}
