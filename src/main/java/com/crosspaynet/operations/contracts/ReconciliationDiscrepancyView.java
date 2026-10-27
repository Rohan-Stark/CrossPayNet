package com.crosspaynet.operations.contracts;

import com.crosspaynet.operations.domain.DiscrepancyId;
import com.crosspaynet.operations.domain.DiscrepancyKey;
import com.crosspaynet.operations.domain.DiscrepancyStatus;
import com.crosspaynet.operations.domain.DiscrepancyType;
import com.crosspaynet.operations.domain.ReconciliationRunId;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable read-only view of an operational discrepancy.
 */
public record ReconciliationDiscrepancyView(
        DiscrepancyId discrepancyId,
        DiscrepancyKey discrepancyKey,
        ReconciliationRunId firstRunId,
        ReconciliationRunId latestRunId,
        DiscrepancyType discrepancyType,
        String operationalEntityId,
        DiscrepancyStatus status,
        String summary,
        String details,
        Instant firstDetectedAt,
        Instant lastObservedAt,
        int consecutiveDetections,
        Instant resolvedAt,
        Instant createdAt,
        Instant updatedAt
) {

    public ReconciliationDiscrepancyView {
        Objects.requireNonNull(discrepancyId, "discrepancyId cannot be null.");
        Objects.requireNonNull(discrepancyKey, "discrepancyKey cannot be null.");
        Objects.requireNonNull(firstRunId, "firstRunId cannot be null.");
        Objects.requireNonNull(latestRunId, "latestRunId cannot be null.");
        Objects.requireNonNull(discrepancyType, "discrepancyType cannot be null.");
        Objects.requireNonNull(operationalEntityId, "operationalEntityId cannot be null.");
        Objects.requireNonNull(status, "status cannot be null.");
        Objects.requireNonNull(summary, "summary cannot be null.");
        Objects.requireNonNull(firstDetectedAt, "firstDetectedAt cannot be null.");
        Objects.requireNonNull(lastObservedAt, "lastObservedAt cannot be null.");
        Objects.requireNonNull(createdAt, "createdAt cannot be null.");
        Objects.requireNonNull(updatedAt, "updatedAt cannot be null.");
    }
}
