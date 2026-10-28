package com.crosspaynet.operations.contracts;

import com.crosspaynet.operations.domain.DiscrepancyId;
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
     * Lists recent reconciliation runs using deterministic ordering with bounded pagination.
     *
     * @param page zero-based page index (must be >= 0)
     * @param size maximum records per page (between 1 and 100)
     * @return paginated result of reconciliation run views
     */
    PagedResult<ReconciliationRunView> findRuns(int page, int size);

    /**
     * Lists the check executions associated with a reconciliation run.
     *
     * @param runId run identifier
     * @return list of check execution views
     */
    List<ReconciliationCheckExecutionView> findCheckExecutions(ReconciliationRunId runId);

    /**
     * Finds an operational discrepancy by its unique identifier.
     *
     * @param discrepancyId discrepancy identifier
     * @return optional containing the discrepancy view if found
     */
    Optional<ReconciliationDiscrepancyView> findDiscrepancy(DiscrepancyId discrepancyId);

    /**
     * Queries operational discrepancies with optional filters and bounded pagination.
     *
     * @param filter query filters and pagination parameters
     * @return paginated result of discrepancy views
     */
    PagedResult<ReconciliationDiscrepancyView> findDiscrepancies(DiscrepancyQueryFilter filter);

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

    /**
     * Detects and recovers abandoned reconciliation runs left in an incomplete state
     * due to abnormal process termination or restarts.
     *
     * <p>Any {@code IN_PROGRESS} run that is not actively executing within the current
     * application instance is transitioned to {@code FAILED} with an audit failure reason,
     * preserving its existing check executions for investigation. Actively executing runs
     * are never modified.
     *
     * @return the number of abandoned runs recovered
     */
    int recoverAbandonedRuns();
}
