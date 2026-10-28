package com.crosspaynet.operations.infrastructure.web;

import com.crosspaynet.operations.contracts.ReconciliationDiscrepancyView;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Public response DTO for operational discrepancy findings.
 */
public record ReconciliationDiscrepancyResponse(
        UUID discrepancyId,
        String discrepancyKey,
        UUID firstRunId,
        UUID latestRunId,
        String discrepancyType,
        String operationalEntityId,
        String status,
        String summary,
        String details,
        Instant firstDetectedAt,
        Instant lastObservedAt,
        int consecutiveDetections,
        Instant resolvedAt,
        Instant createdAt,
        Instant updatedAt
) {
    public ReconciliationDiscrepancyResponse {
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
    }

    public static ReconciliationDiscrepancyResponse from(ReconciliationDiscrepancyView view) {
        if (view == null) return null;
        return new ReconciliationDiscrepancyResponse(
                view.discrepancyId().getValue(),
                view.discrepancyKey().getValue(),
                view.firstRunId().getValue(),
                view.latestRunId().getValue(),
                view.discrepancyType().name(),
                view.operationalEntityId(),
                view.status().name(),
                view.summary(),
                view.details(),
                view.firstDetectedAt(),
                view.lastObservedAt(),
                view.consecutiveDetections(),
                view.resolvedAt(),
                view.createdAt(),
                view.updatedAt()
        );
    }
}
