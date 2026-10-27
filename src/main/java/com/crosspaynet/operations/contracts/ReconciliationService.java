package com.crosspaynet.operations.contracts;

import com.crosspaynet.operations.domain.ReconciliationRunId;

import java.util.List;
import java.util.Optional;

/**
 * Public service interface for executing and inspecting operational reconciliation runs.
 */
public interface ReconciliationService {

    /**
     * Triggers a new operational reconciliation run executing all configured checks.
     *
     * @param command trigger command containing operator or trigger source information
     * @return view of the executed reconciliation run
     */
    ReconciliationRunView triggerRun(TriggerReconciliationRunCommand command);

    /**
     * Finds a reconciliation run by identifier.
     *
     * @param runId run identifier
     * @return optional containing the run view if found
     */
    Optional<ReconciliationRunView> findRun(ReconciliationRunId runId);

    /**
     * Lists the check executions associated with a reconciliation run.
     *
     * @param runId run identifier
     * @return list of check execution views
     */
    List<ReconciliationCheckExecutionView> findCheckExecutions(ReconciliationRunId runId);

    /**
     * Lists the discrepancies detected or updated during a reconciliation run.
     *
     * @param runId run identifier
     * @return list of discrepancy views
     */
    List<ReconciliationDiscrepancyView> findDiscrepanciesForRun(ReconciliationRunId runId);

    /**
     * Returns the latest reconciliation run if one has been executed.
     *
     * @return optional containing the latest run view
     */
    Optional<ReconciliationRunView> findLatestRun();
}
