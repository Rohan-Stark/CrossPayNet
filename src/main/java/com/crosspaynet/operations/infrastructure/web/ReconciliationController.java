package com.crosspaynet.operations.infrastructure.web;

import com.crosspaynet.operations.contracts.DiscrepancyQueryFilter;
import com.crosspaynet.operations.contracts.PagedResult;
import com.crosspaynet.operations.contracts.ReconciliationCheckExecutionView;
import com.crosspaynet.operations.contracts.ReconciliationDiscrepancyView;
import com.crosspaynet.operations.contracts.ReconciliationRunView;
import com.crosspaynet.operations.contracts.ReconciliationService;
import com.crosspaynet.operations.contracts.TriggerReconciliationRunCommand;
import com.crosspaynet.operations.domain.DiscrepancyId;
import com.crosspaynet.operations.domain.DiscrepancyStatus;
import com.crosspaynet.operations.domain.DiscrepancyType;
import com.crosspaynet.operations.domain.OperationsNotFoundException;
import com.crosspaynet.operations.domain.ReconciliationRunId;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Secured REST API controller for operational reconciliation runs and discrepancy findings.
 *
 * <p>Exposes synchronous run triggering, paginated run history, and discrepancy inspection
 * under {@code /api/v1/operations/reconciliation/**}. All endpoints require {@code ROLE_OPERATIONS}
 * or {@code ROLE_ADMIN}.
 */
@RestController
@RequestMapping("/api/v1/operations/reconciliation")
@PreAuthorize("hasAnyRole('OPERATIONS', 'ADMIN')")
public class ReconciliationController {

    private final ReconciliationService reconciliationService;

    public ReconciliationController(ReconciliationService reconciliationService) {
        this.reconciliationService = Objects.requireNonNull(reconciliationService, "reconciliationService cannot be null.");
    }

    /**
     * Synchronously triggers a new operational reconciliation run.
     *
     * <p>Operator identity is derived strictly from the authenticated Spring Security principal.
     */
    @PostMapping("/runs")
    public ResponseEntity<ReconciliationRunDetailResponse> triggerRun(Authentication authentication) {
        String operator = (authentication != null && authentication.getName() != null && !authentication.getName().isBlank())
                ? authentication.getName()
                : "OPERATIONS";

        ReconciliationRunView run = reconciliationService.triggerRun(new TriggerReconciliationRunCommand(operator));
        List<ReconciliationCheckExecutionView> checks = reconciliationService.findCheckExecutions(run.runId());

        URI location = URI.create("/api/v1/operations/reconciliation/runs/" + run.runId().getValue());
        return ResponseEntity.created(location)
                .body(ReconciliationRunDetailResponse.from(run, checks));
    }

    /**
     * Retrieves recent reconciliation runs with bounded pagination and deterministic ordering.
     */
    @GetMapping("/runs")
    public ResponseEntity<PagedResponse<ReconciliationRunResponse>> listRuns(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size
    ) {
        PagedResult<ReconciliationRunView> result = reconciliationService.findRuns(page, size);
        List<ReconciliationRunResponse> content = result.content().stream()
                .map(ReconciliationRunResponse::from)
                .toList();

        return ResponseEntity.ok(new PagedResponse<>(
                content,
                result.page(),
                result.size(),
                result.totalElements(),
                result.totalPages()
        ));
    }

    /**
     * Retrieves detailed outcomes for a single reconciliation run by identifier.
     */
    @GetMapping("/runs/{runId}")
    public ResponseEntity<ReconciliationRunDetailResponse> getRun(@PathVariable("runId") UUID runId) {
        ReconciliationRunId id = ReconciliationRunId.of(runId);
        ReconciliationRunView run = reconciliationService.findRun(id)
                .orElseThrow(() -> new OperationsNotFoundException("Reconciliation run not found: " + runId));

        List<ReconciliationCheckExecutionView> checks = reconciliationService.findCheckExecutions(id);
        return ResponseEntity.ok(ReconciliationRunDetailResponse.from(run, checks));
    }

    /**
     * Retrieves operational discrepancy findings with optional lifecycle, category, and run filters.
     */
    @GetMapping("/discrepancies")
    public ResponseEntity<PagedResponse<ReconciliationDiscrepancyResponse>> listDiscrepancies(
            @RequestParam(name = "status", required = false) String statusStr,
            @RequestParam(name = "type", required = false) String typeStr,
            @RequestParam(name = "runId", required = false) UUID runId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size
    ) {
        DiscrepancyStatus status = null;
        if (statusStr != null && !statusStr.isBlank()) {
            try {
                status = DiscrepancyStatus.valueOf(statusStr.trim().toUpperCase());
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("Invalid discrepancy status '" + statusStr + "'. Allowed: " + Arrays.toString(DiscrepancyStatus.values()));
            }
        }

        DiscrepancyType type = null;
        if (typeStr != null && !typeStr.isBlank()) {
            try {
                type = DiscrepancyType.valueOf(typeStr.trim().toUpperCase());
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("Invalid discrepancy type '" + typeStr + "'. Allowed: " + Arrays.toString(DiscrepancyType.values()));
            }
        }

        ReconciliationRunId run = runId != null ? ReconciliationRunId.of(runId) : null;
        DiscrepancyQueryFilter filter = new DiscrepancyQueryFilter(status, type, run, page, size);

        PagedResult<ReconciliationDiscrepancyView> result = reconciliationService.findDiscrepancies(filter);
        List<ReconciliationDiscrepancyResponse> content = result.content().stream()
                .map(ReconciliationDiscrepancyResponse::from)
                .toList();

        return ResponseEntity.ok(new PagedResponse<>(
                content,
                result.page(),
                result.size(),
                result.totalElements(),
                result.totalPages()
        ));
    }

    /**
     * Retrieves an individual operational discrepancy by identifier.
     */
    @GetMapping("/discrepancies/{discrepancyId}")
    public ResponseEntity<ReconciliationDiscrepancyResponse> getDiscrepancy(@PathVariable("discrepancyId") UUID discrepancyId) {
        DiscrepancyId id = DiscrepancyId.of(discrepancyId);
        ReconciliationDiscrepancyView discrepancy = reconciliationService.findDiscrepancy(id)
                .orElseThrow(() -> new OperationsNotFoundException("Discrepancy not found: " + discrepancyId));

        return ResponseEntity.ok(ReconciliationDiscrepancyResponse.from(discrepancy));
    }
}
