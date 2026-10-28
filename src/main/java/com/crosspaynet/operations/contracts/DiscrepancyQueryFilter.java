package com.crosspaynet.operations.contracts;

import com.crosspaynet.operations.domain.DiscrepancyStatus;
import com.crosspaynet.operations.domain.DiscrepancyType;
import com.crosspaynet.operations.domain.ReconciliationRunId;

/**
 * Filter and pagination parameters for querying operational discrepancies.
 *
 * @param status optional discrepancy lifecycle status filter
 * @param type optional discrepancy category filter
 * @param runId optional run reference filter (matches first or latest run)
 * @param page zero-based page number (must be >= 0)
 * @param size maximum items per page (must be between 1 and 100)
 */
public record DiscrepancyQueryFilter(
        DiscrepancyStatus status,
        DiscrepancyType type,
        ReconciliationRunId runId,
        int page,
        int size
) {
    public DiscrepancyQueryFilter {
        if (page < 0) {
            throw new IllegalArgumentException("page must be >= 0");
        }
        if (size <= 0 || size > 100) {
            throw new IllegalArgumentException("size must be between 1 and 100");
        }
    }
}
